package demo.chess.database;

import static demo.chess.database.SqliteDatabaseSupport.bindNullableInteger;
import static demo.chess.database.SqliteDatabaseSupport.bindNullableLong;
import static demo.chess.database.SqliteDatabaseSupport.createPlayerCache;
import static demo.chess.database.SqliteDatabaseSupport.encodeTags;
import static demo.chess.database.SqliteDatabaseSupport.findOrCreatePlayer;
import static demo.chess.database.SqliteDatabaseSupport.normalizeResult;
import static demo.chess.database.SqliteDatabaseSupport.normalizeTag;
import static demo.chess.database.SqliteDatabaseSupport.openConnection;
import static demo.chess.database.SqliteDatabaseSupport.parseInteger;
import static demo.chess.database.SqliteDatabaseSupport.parseYear;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import demo.chess.definitions.ChessStartingPosition;
import demo.chess.definitions.engines.impl.NoMoveFoundException;
import demo.chess.load.GameLoader;

/** Streaming PGN ingestion and atomic publication for the SQLite database. */
final class SqlitePgnImporter {

    private static final int COMMIT_GAME_BATCH = 10_000;
    private static final int POSITION_BATCH_SIZE = 5_000;
    private static final int POSITION_AGGREGATION_LIMIT = 100_000;
    private final Path databasePath;
    private final GameLoader gameLoader;

    SqlitePgnImporter(Path databasePath, GameLoader gameLoader) {
        this.databasePath = databasePath;
        this.gameLoader = gameLoader;
    }

    ImportResult importPgn(
            String importId,
            InputStream inputStream,
            long totalBytes,
            Consumer<ImportProgress> progressConsumer,
            BooleanSupplier cancellationRequested) throws SQLException, IOException {
        if (importId == null || importId.isBlank()) throw new IllegalArgumentException("importId must not be blank");
        if (inputStream == null) throw new IllegalArgumentException("inputStream must not be null");

        Consumer<ImportProgress> progress = progressConsumer == null ? ignored -> { } : progressConsumer;
        BooleanSupplier cancelled = cancellationRequested == null ? () -> false : cancellationRequested;
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        long processedGames = 0L;
        long importedGames = 0L;
        long skippedGames = 0L;
        long totalPlies = 0L;
        long parsingNanos = 0L;
        long positionNanos = 0L;
        long databaseNanos = 0L;
        long finalizeNanos = 0L;
        Map<String, Long> playerCache = createPlayerCache();
        Map<PositionMoveKey, PositionAggregate> positionAggregation = new HashMap<>(131_072);
        CountingInputStream countedInput = new CountingInputStream(inputStream);
        publishProgress(progress, countedInput, totalBytes, processedGames, importedGames,
                skippedGames, totalPlies, startedAt);

        try {
            try (Connection connection = openConnection(databasePath);
                    ImportedPgnAnnotations annotations = new ImportedPgnAnnotations(connection);
                    PgnStreamReader pgnReader = new PgnStreamReader(
                            new InputStreamReader(countedInput, StandardCharsets.UTF_8));
                    PreparedStatement insertPlayer = connection.prepareStatement(
                            "INSERT OR IGNORE INTO player(name, normalized_name) VALUES (?, ?)");
                    PreparedStatement selectPlayer = connection.prepareStatement(
                            "SELECT id FROM player WHERE normalized_name = ?");
                    PreparedStatement findDuplicateGame = connection.prepareStatement(
                            """
                            SELECT id FROM game
                            WHERE white_player_id IS ? AND black_player_id IS ?
                              AND game_date IS ? AND round IS ? AND result = ?
                              AND starting_position_id = ? AND ply_count = ? AND moves = ?
                            LIMIT 1
                            """);
                    PreparedStatement insertGame = connection.prepareStatement(
                            """
                            INSERT INTO game(
                                white_player_id, black_player_id, white_elo, black_elo,
                                event, site, game_date, game_year, round, result, eco,
                                starting_position_id, ply_count, moves, tags, import_id
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """);
                    PreparedStatement upsertPosition = connection.prepareStatement(
                            """
                            INSERT INTO position_move_stage(
                                import_id, hash_hi, hash_lo, move_code,
                                games, white_wins, draws, black_wins
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(import_id, hash_hi, hash_lo, move_code) DO UPDATE SET
                                games = games + excluded.games,
                                white_wins = white_wins + excluded.white_wins,
                                draws = draws + excluded.draws,
                                black_wins = black_wins + excluded.black_wins
                            """)) {
                connection.setAutoCommit(false);
                try {
                    while (true) {
                        long readStarted = System.nanoTime();
                        String pgn = pgnReader.nextGame();
                        parsingNanos += System.nanoTime() - readStarted;
                        if (pgn == null) break;

                        checkCancellation(cancelled);
                        processedGames++;
                        try {
                            Map<String, String> tags;
                            List<String> moves;
                            ChessStartingPosition startingPosition;
                            long parseStarted = System.nanoTime();
                            try {
                                tags = gameLoader.parsePgnTags(pgn);
                                if (!isSupportedGame(tags)) {
                                    moves = null;
                                    startingPosition = null;
                                } else {
                                    startingPosition = gameLoader.parsePgnStartingPosition(pgn);
                                    moves = LegacyDatabaseMoveAdapter.toStorage(
                                            startingPosition,
                                            gameLoader.parsePgnMoveList(pgn));
                                }
                            } finally {
                                parsingNanos += System.nanoTime() - parseStarted;
                            }

                            if (moves == null || moves.isEmpty() || startingPosition == null) {
                                skippedGames++;
                            } else {
                                List<PositionMoveKey> positionUpdates = new ArrayList<>(moves.size());
                                byte[] encodedMoves;
                                long positionStarted = System.nanoTime();
                                try {
                                    encodedMoves = MoveCodec.encodeMoves(moves);
                                    ZobristPositionHasher.Cursor cursor = ZobristPositionHasher.newCursor(startingPosition);
                                    for (String move : moves) {
                                        PositionHash hash = cursor.hash();
                                        positionUpdates.add(new PositionMoveKey(
                                                hash.high(), hash.low(), MoveCodec.encode(move)));
                                        cursor.apply(move);
                                    }
                                } finally {
                                    positionNanos += System.nanoTime() - positionStarted;
                                }

                                String result = normalizeResult(tags.get("Result"));
                                boolean duplicate;
                                long databaseStarted = System.nanoTime();
                                try {
                                    Long whiteId = findOrCreatePlayer(
                                            insertPlayer, selectPlayer, playerCache, tags.get("White"));
                                    Long blackId = findOrCreatePlayer(
                                            insertPlayer, selectPlayer, playerCache, tags.get("Black"));
                                    String date = normalizeTag(tags.get("Date"));
                                    String round = normalizeTag(tags.get("Round"));
                                    Long gameId = findDuplicateGameId(
                                            findDuplicateGame,
                                            whiteId,
                                            blackId,
                                            date,
                                            round,
                                            result,
                                            startingPosition.getId(),
                                            moves.size(),
                                            encodedMoves);

                                    duplicate = gameId != null;
                                    if (duplicate) {
                                        skippedGames++;
                                    } else {
                                        bindNullableLong(insertGame, 1, whiteId);
                                        bindNullableLong(insertGame, 2, blackId);
                                        bindNullableInteger(insertGame, 3, parseInteger(tags.get("WhiteElo")));
                                        bindNullableInteger(insertGame, 4, parseInteger(tags.get("BlackElo")));
                                        insertGame.setString(5, normalizeTag(tags.get("Event")));
                                        insertGame.setString(6, normalizeTag(tags.get("Site")));
                                        insertGame.setString(7, date);
                                        bindNullableInteger(insertGame, 8, parseYear(date));
                                        insertGame.setString(9, round);
                                        insertGame.setString(10, result);
                                        insertGame.setString(11, normalizeTag(tags.get("ECO")));
                                        insertGame.setInt(12, startingPosition.getId());
                                        insertGame.setInt(13, moves.size());
                                        insertGame.setBytes(14, encodedMoves);
                                        insertGame.setString(15, encodeTags(tags));
                                        insertGame.setString(16, importId);
                                        insertGame.executeUpdate();
                                        try (Statement idStatement = connection.createStatement();
                                                ResultSet idResult = idStatement.executeQuery(
                                                        "SELECT last_insert_rowid()")) {
                                            if (!idResult.next()) throw new SQLException("Missing inserted game id");
                                            gameId = idResult.getLong(1);
                                        }
                                    }
                                    annotations.stage(importId, gameId, pgn);
                                } finally {
                                    databaseNanos += System.nanoTime() - databaseStarted;
                                }

                                if (!duplicate) {
                                    int whiteWin = "1-0".equals(result) ? 1 : 0;
                                    int draw = "1/2-1/2".equals(result) ? 1 : 0;
                                    int blackWin = "0-1".equals(result) ? 1 : 0;
                                    long aggregateStarted = System.nanoTime();
                                    try {
                                        aggregatePositionUpdates(
                                                positionAggregation,
                                                positionUpdates,
                                                whiteWin,
                                                draw,
                                                blackWin);
                                    } finally {
                                        positionNanos += System.nanoTime() - aggregateStarted;
                                    }
                                    importedGames++;
                                    totalPlies += moves.size();
                                }
                            }
                        } catch (NoMoveFoundException | IllegalArgumentException ignored) {
                            skippedGames++;
                        }

                        if (positionAggregation.size() >= POSITION_AGGREGATION_LIMIT
                                || processedGames % COMMIT_GAME_BATCH == 0) {
                            long databaseStarted = System.nanoTime();
                            try {
                                flushPositionAggregation(upsertPosition, importId, positionAggregation);
                                if (processedGames % COMMIT_GAME_BATCH == 0) connection.commit();
                            } finally {
                                databaseNanos += System.nanoTime() - databaseStarted;
                            }
                        }
                        publishProgress(progress, countedInput, totalBytes, processedGames,
                                importedGames, skippedGames, totalPlies, startedAt);
                    }

                    checkCancellation(cancelled);
                    long writeStarted = System.nanoTime();
                    try {
                        flushPositionAggregation(upsertPosition, importId, positionAggregation);
                    } finally {
                        databaseNanos += System.nanoTime() - writeStarted;
                    }
                    long finalizeStarted = System.nanoTime();
                    try {
                        finalizeImport(connection, importId, cancelled);
                        checkCancellation(cancelled);
                        connection.commit();
                    } finally {
                        finalizeNanos += System.nanoTime() - finalizeStarted;
                    }
                } catch (SQLException | IOException | RuntimeException exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        } catch (ImportCancelledException exception) {
            cleanupImportAfterFailure(importId, exception);
            throw exception;
        } catch (SQLException | IOException | RuntimeException exception) {
            cleanupImportAfterFailure(importId, exception);
            throw exception;
        }

        long elapsed = Duration.between(startedAt, Instant.now()).toMillis();
        logImportProfile(System.nanoTime() - startedNanos, parsingNanos, positionNanos,
                databaseNanos, finalizeNanos, importedGames, skippedGames, totalPlies);
        publishProgress(progress, countedInput, totalBytes, processedGames, importedGames,
                skippedGames, totalPlies, startedAt);
        return new ImportResult(importedGames, skippedGames, totalPlies, elapsed);
    }

    private void aggregatePositionUpdates(
            Map<PositionMoveKey, PositionAggregate> aggregation,
            List<PositionMoveKey> updates,
            int whiteWin,
            int draw,
            int blackWin) {
        for (PositionMoveKey update : updates) {
            aggregation.computeIfAbsent(update, ignored -> new PositionAggregate())
                    .add(whiteWin, draw, blackWin);
        }
    }

    private void flushPositionAggregation(
            PreparedStatement statement,
            String importId,
            Map<PositionMoveKey, PositionAggregate> aggregation) throws SQLException {
        if (aggregation.isEmpty()) return;
        int pending = 0;
        for (Map.Entry<PositionMoveKey, PositionAggregate> entry : aggregation.entrySet()) {
            PositionMoveKey key = entry.getKey();
            PositionAggregate aggregate = entry.getValue();
            statement.setString(1, importId);
            statement.setLong(2, key.hashHigh());
            statement.setLong(3, key.hashLow());
            statement.setInt(4, key.moveCode());
            statement.setLong(5, aggregate.games);
            statement.setLong(6, aggregate.whiteWins);
            statement.setLong(7, aggregate.draws);
            statement.setLong(8, aggregate.blackWins);
            statement.addBatch();
            if (++pending >= POSITION_BATCH_SIZE) {
                statement.executeBatch();
                pending = 0;
            }
        }
        if (pending > 0) statement.executeBatch();
        aggregation.clear();
    }

    private void finalizeImport(
            Connection connection,
            String importId,
            BooleanSupplier cancelled) throws SQLException, IOException {
        ImportedPgnAnnotations.publish(connection, importId, cancelled);
        try (PreparedStatement merge = connection.prepareStatement(
                    """
                    INSERT INTO position_move(hash_hi, hash_lo, move_code, games, white_wins, draws, black_wins)
                    SELECT hash_hi, hash_lo, move_code, games, white_wins, draws, black_wins
                    FROM position_move_stage WHERE import_id = ?
                    ON CONFLICT(hash_hi, hash_lo, move_code) DO UPDATE SET
                        games = games + excluded.games,
                        white_wins = white_wins + excluded.white_wins,
                        draws = draws + excluded.draws,
                        black_wins = black_wins + excluded.black_wins
                    """);
                PreparedStatement publish = connection.prepareStatement(
                        "UPDATE game SET import_id = NULL WHERE import_id = ?");
                PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM position_move_stage WHERE import_id = ?")) {
            merge.setString(1, importId);
            merge.executeUpdate();
            publish.setString(1, importId);
            publish.executeUpdate();
            delete.setString(1, importId);
            delete.executeUpdate();
        }
    }

    private void cleanupImport(String importId) throws SQLException {
        try (Connection connection = openConnection(databasePath)) {
            connection.setAutoCommit(false);
            ImportedPgnAnnotations.cleanup(connection, importId);
            try (PreparedStatement deletePositions = connection.prepareStatement(
                        "DELETE FROM position_move_stage WHERE import_id = ?");
                    PreparedStatement deleteGames = connection.prepareStatement(
                        "DELETE FROM game WHERE import_id = ?")) {
                deletePositions.setString(1, importId);
                deletePositions.executeUpdate();
                deleteGames.setString(1, importId);
                deleteGames.executeUpdate();
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        """
                        DELETE FROM player
                        WHERE NOT EXISTS (
                            SELECT 1 FROM game
                            WHERE game.white_player_id = player.id OR game.black_player_id = player.id
                        )
                        """);
            }
            connection.commit();
        }
    }

    private void cleanupImportAfterFailure(String importId, Exception original) {
        try {
            cleanupImport(importId);
        } catch (SQLException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private boolean isSupportedGame(Map<String, String> tags) {
        String variant = normalizeTag(tags.get("Variant"));
        if (variant == null) return true;
        String normalized = variant.toLowerCase(Locale.ROOT).replace(" ", "");
        return "standard".equals(normalized)
                || "chess".equals(normalized)
                || "chess960".equals(normalized)
                || "fischerrandom".equals(normalized);
    }

    private Long findDuplicateGameId(
            PreparedStatement statement,
            Long whiteId,
            Long blackId,
            String date,
            String round,
            String result,
            int startingPositionId,
            int plyCount,
            byte[] encodedMoves) throws SQLException {
        bindNullableLong(statement, 1, whiteId);
        bindNullableLong(statement, 2, blackId);
        statement.setString(3, date);
        statement.setString(4, round);
        statement.setString(5, result);
        statement.setInt(6, startingPositionId);
        statement.setInt(7, plyCount);
        statement.setBytes(8, encodedMoves);
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : null;
        }
    }

    private void checkCancellation(BooleanSupplier cancellationRequested) throws ImportCancelledException {
        if (cancellationRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new ImportCancelledException("Chess database import cancelled.");
        }
    }

    private void publishProgress(
            Consumer<ImportProgress> consumer,
            CountingInputStream input,
            long totalBytes,
            long processedGames,
            long importedGames,
            long skippedGames,
            long totalPlies,
            Instant startedAt) {
        try {
            consumer.accept(new ImportProgress(
                    input.bytesRead(),
                    totalBytes,
                    processedGames,
                    importedGames,
                    skippedGames,
                    totalPlies,
                    Duration.between(startedAt, Instant.now()).toMillis()));
        } catch (RuntimeException ignored) {
            // Progress listeners are observational and must not abort an import.
        }
    }

    private void logImportProfile(
            long totalNanos,
            long parsingNanos,
            long positionNanos,
            long databaseNanos,
            long finalizeNanos,
            long importedGames,
            long skippedGames,
            long totalPlies) {
        long measuredNanos = parsingNanos + positionNanos + databaseNanos + finalizeNanos;
        long otherNanos = Math.max(0L, totalNanos - measuredNanos);
        System.out.printf(Locale.ROOT,
                "Chess database import profile: total=%.3f s; PGN/SAN=%.3f s; "
                        + "position/index=%.3f s; SQLite=%.3f s; finalize=%.3f s; "
                        + "other=%.3f s; imported=%d; skipped=%d; plies=%d%n",
                seconds(totalNanos),
                seconds(parsingNanos),
                seconds(positionNanos),
                seconds(databaseNanos),
                seconds(finalizeNanos),
                seconds(otherNanos),
                importedGames,
                skippedGames,
                totalPlies);
    }

    private double seconds(long nanos) {
        return nanos / 1_000_000_000.0d;
    }

    private record PositionMoveKey(long hashHigh, long hashLow, int moveCode) { }

    private static final class PositionAggregate {
        private long games;
        private long whiteWins;
        private long draws;
        private long blackWins;

        private void add(int whiteWin, int draw, int blackWin) {
            games++;
            whiteWins += whiteWin;
            draws += draw;
            blackWins += blackWin;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long bytesRead;

        private CountingInputStream(InputStream inputStream) {
            super(inputStream);
        }

        private long bytesRead() {
            return bytesRead;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) bytesRead += count;
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(count);
            bytesRead += skipped;
            return skipped;
        }
    }
}
