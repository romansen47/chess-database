package demo.chess.database;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import demo.chess.definitions.ChessStartingPosition;
import demo.chess.definitions.engines.impl.NoMoveFoundException;
import demo.chess.game.impl.Simulation;
import demo.chess.load.GameLoader;
import demo.chess.save.GameSaver;

/** SQLite-backed implementation of the local chess database. */
public class SqliteChessDatabase implements ChessDatabase {

    public static final int SCHEMA_VERSION = 4;
    public static final int HASH_VERSION = 1;
    public static final int MOVE_CODEC_VERSION = 1;

    private static final int COMMIT_GAME_BATCH = 10_000;
    private static final int POSITION_BATCH_SIZE = 5_000;
    private static final int POSITION_AGGREGATION_LIMIT = 100_000;
    private static final int PLAYER_CACHE_SIZE = 10_000;

    private final Path databasePath;
    private final GameLoader gameLoader = new GameLoader();
    private final GameSaver gameSaver = new GameSaver();

    public SqliteChessDatabase(Path databasePath) throws SQLException, IOException {
        if (databasePath == null) throw new IllegalArgumentException("databasePath must not be null");
        this.databasePath = databasePath.toAbsolutePath().normalize();
        Path parent = this.databasePath.getParent();
        if (parent != null) Files.createDirectories(parent);
        initialize();
    }

    public static Path defaultPath() {
        String configured = System.getProperty("chess.database.path");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".chess", "database", "chess.db")
                .toAbsolutePath().normalize();
    }

    @Override
    public ChessDatabaseStatus getStatus() throws SQLException, IOException {
        try (Connection connection = openConnection()) {
            long count;
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM game WHERE import_id IS NULL")) {
                count = resultSet.next() ? resultSet.getLong(1) : 0L;
            }
            return new ChessDatabaseStatus(
                    databasePath.toString(),
                    readInfo(connection, "name", "Chess Database"),
                    Integer.parseInt(readInfo(connection, "schema_version", Integer.toString(SCHEMA_VERSION))),
                    count,
                    Files.exists(databasePath) ? Files.size(databasePath) : 0L);
        }
    }

    @Override
    public ImportResult importPgn(
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
        CountingInputStream countingInputStream = new CountingInputStream(inputStream);
        publishProgress(progress, countingInputStream, totalBytes, processedGames, importedGames, skippedGames, totalPlies, startedAt);

        try {
            try (Connection connection = openConnection();
                    PgnStreamReader pgnReader = new PgnStreamReader(new InputStreamReader(countingInputStream, StandardCharsets.UTF_8));
                    PreparedStatement insertPlayer = connection.prepareStatement("INSERT OR IGNORE INTO player(name, normalized_name) VALUES (?, ?)");
                    PreparedStatement selectPlayer = connection.prepareStatement("SELECT id FROM player WHERE normalized_name = ?");
                    PreparedStatement findDuplicateGame = connection.prepareStatement(
                            """
                            SELECT 1 FROM game
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
                                        positionUpdates.add(new PositionMoveKey(hash.high(), hash.low(), MoveCodec.encode(move)));
                                        cursor.apply(move);
                                    }
                                } finally {
                                    positionNanos += System.nanoTime() - positionStarted;
                                }

                                String result = normalizeResult(tags.get("Result"));
                                boolean duplicate;
                                long dbStarted = System.nanoTime();
                                try {
                                    Long whiteId = findOrCreatePlayer(insertPlayer, selectPlayer, playerCache, tags.get("White"));
                                    Long blackId = findOrCreatePlayer(insertPlayer, selectPlayer, playerCache, tags.get("Black"));
                                    String date = normalizeTag(tags.get("Date"));
                                    String round = normalizeTag(tags.get("Round"));
                                    duplicate = isDuplicateGame(
                                            findDuplicateGame,
                                            whiteId,
                                            blackId,
                                            date,
                                            round,
                                            result,
                                            startingPosition.getId(),
                                            moves.size(),
                                            encodedMoves);

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
                                    }
                                } finally {
                                    databaseNanos += System.nanoTime() - dbStarted;
                                }

                                if (!duplicate) {
                                    int whiteWin = "1-0".equals(result) ? 1 : 0;
                                    int draw = "1/2-1/2".equals(result) ? 1 : 0;
                                    int blackWin = "0-1".equals(result) ? 1 : 0;
                                    long aggregateStarted = System.nanoTime();
                                    try {
                                        aggregatePositionUpdates(positionAggregation, positionUpdates, whiteWin, draw, blackWin);
                                    } finally {
                                        positionNanos += System.nanoTime() - aggregateStarted;
                                    }
                                    importedGames++;
                                    totalPlies += moves.size();
                                }
                            }
                        } catch (NoMoveFoundException | IllegalArgumentException e) {
                            skippedGames++;
                        }

                        if (positionAggregation.size() >= POSITION_AGGREGATION_LIMIT
                                || processedGames % COMMIT_GAME_BATCH == 0) {
                            long dbStarted = System.nanoTime();
                            try {
                                flushPositionAggregation(upsertPosition, importId, positionAggregation);
                                if (processedGames % COMMIT_GAME_BATCH == 0) connection.commit();
                            } finally {
                                databaseNanos += System.nanoTime() - dbStarted;
                            }
                        }
                        publishProgress(progress, countingInputStream, totalBytes, processedGames, importedGames, skippedGames, totalPlies, startedAt);
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
                        finalizeImport(connection, importId);
                        connection.commit();
                    } finally {
                        finalizeNanos += System.nanoTime() - finalizeStarted;
                    }
                } catch (SQLException | IOException | RuntimeException e) {
                    connection.rollback();
                    throw e;
                }
            }
        } catch (ImportCancelledException e) {
            cleanupImportAfterFailure(importId, e);
            throw e;
        } catch (SQLException | IOException | RuntimeException e) {
            cleanupImportAfterFailure(importId, e);
            throw e;
        }

        long elapsed = Duration.between(startedAt, Instant.now()).toMillis();
        logImportProfile(
                System.nanoTime() - startedNanos,
                parsingNanos,
                positionNanos,
                databaseNanos,
                finalizeNanos,
                importedGames,
                skippedGames,
                totalPlies);
        publishProgress(progress, countingInputStream, totalBytes, processedGames, importedGames, skippedGames, totalPlies, startedAt);
        return new ImportResult(importedGames, skippedGames, totalPlies, elapsed);
    }

    private void aggregatePositionUpdates(
            Map<PositionMoveKey, PositionAggregate> aggregation,
            List<PositionMoveKey> updates,
            int whiteWin,
            int draw,
            int blackWin) {
        for (PositionMoveKey update : updates) {
            aggregation.computeIfAbsent(update, ignored -> new PositionAggregate()).add(whiteWin, draw, blackWin);
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
        System.out.printf(
                Locale.ROOT,
                "Chess database import profile: total=%.3f s; PGN/SAN=%.3f s; position/index=%.3f s; SQLite=%.3f s; finalize=%.3f s; other=%.3f s; imported=%d; skipped=%d; plies=%d%n",
                seconds(totalNanos), seconds(parsingNanos), seconds(positionNanos), seconds(databaseNanos),
                seconds(finalizeNanos), seconds(otherNanos), importedGames, skippedGames, totalPlies);
    }

    private double seconds(long nanos) {
        return nanos / 1_000_000_000.0d;
    }

    @Override
    public List<GameSummary> findGames(GameSearch search) throws SQLException {
        GameSearch criteria = search == null
                ? new GameSearch(null, null, null, null, null, null, null, 200)
                : search;
        StringBuilder sql = new StringBuilder(
                """
                SELECT g.id, g.game_date, COALESCE(w.name, '?'), COALESCE(b.name, '?'),
                       g.white_elo, g.black_elo, g.result, g.event, g.eco, g.ply_count
                FROM game g
                LEFT JOIN player w ON w.id = g.white_player_id
                LEFT JOIN player b ON b.id = g.black_player_id
                WHERE g.import_id IS NULL
                """);
        List<Object> parameters = new ArrayList<>();
        appendPlayerFilter(sql, parameters, "w.normalized_name", criteria.white());
        appendPlayerFilter(sql, parameters, "b.normalized_name", criteria.black());
        if (criteria.player() != null) {
            sql.append(" AND (w.normalized_name LIKE ? ESCAPE '\\' OR b.normalized_name LIKE ? ESCAPE '\\')");
            String pattern = containsPattern(criteria.player());
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (criteria.fromYear() != null) { sql.append(" AND g.game_year >= ?"); parameters.add(criteria.fromYear()); }
        if (criteria.toYear() != null) { sql.append(" AND g.game_year <= ?"); parameters.add(criteria.toYear()); }
        if (criteria.result() != null) { sql.append(" AND g.result = ?"); parameters.add(criteria.result()); }
        if (criteria.minElo() != null) {
            sql.append(" AND g.white_elo >= ? AND g.black_elo >= ?");
            parameters.add(criteria.minElo());
            parameters.add(criteria.minElo());
        }
        sql.append(" ORDER BY g.game_year DESC, g.game_date DESC, g.id DESC LIMIT ?");
        parameters.add(criteria.limit());

        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, parameters);
            List<GameSummary> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new GameSummary(
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            nullableInteger(rs, 5), nullableInteger(rs, 6), rs.getString(7),
                            rs.getString(8), rs.getString(9), rs.getInt(10)));
                }
            }
            return result;
        }
    }

    @Override
    public StoredGame getGame(long id) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tags, moves, starting_position_id FROM game WHERE id = ? AND import_id IS NULL")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Chess database game not found: " + id);
                int startingPositionId = rs.getInt(3);
                ChessStartingPosition startingPosition =
                        ChessStartingPosition.of(startingPositionId);
                try {
                    List<String> protocolMoves = LegacyDatabaseMoveAdapter.fromStorage(
                            startingPosition,
                            MoveCodec.decodeMoves(rs.getBytes(2)));
                    return new StoredGame(
                            id,
                            decodeTags(rs.getString(1)),
                            protocolMoves,
                            startingPositionId);
                } catch (NoMoveFoundException | IOException e) {
                    throw new SQLException(
                            "Could not decode stored game " + id
                                    + " for starting position " + startingPositionId,
                            e);
                }
            }
        }
    }

    @Override
    public String getGameAsPgn(long id) throws SQLException, IOException, NoMoveFoundException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT annotated_pgn FROM game_annotation WHERE game_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        StoredGame stored = getGame(id);
        Simulation simulation = Simulation.createSimulation(ChessStartingPosition.of(stored.startingPositionId()));
        gameLoader.loadGame(stored.uciMoves(), simulation);
        return gameSaver.toPgn(simulation.getMoveList(), stored.tags());
    }

    @Override
    public long findGameId(String pgn) throws SQLException, IOException, NoMoveFoundException {
        Map<String, String> tags = gameLoader.parsePgnTags(pgn);
        ChessStartingPosition startingPosition = gameLoader.parsePgnStartingPosition(pgn);
        List<String> moves = LegacyDatabaseMoveAdapter.toStorage(
                startingPosition,
                gameLoader.parsePgnMoveList(pgn));
        byte[] encodedMoves = MoveCodec.encodeMoves(moves);
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT g.id FROM game g
                        LEFT JOIN player w ON w.id = g.white_player_id
                        LEFT JOIN player b ON b.id = g.black_player_id
                        WHERE g.import_id IS NULL
                          AND COALESCE(w.normalized_name, '') = ?
                          AND COALESCE(b.normalized_name, '') = ?
                          AND g.game_date IS ? AND g.round IS ? AND g.result = ?
                          AND g.starting_position_id = ? AND g.ply_count = ? AND g.moves = ?
                        ORDER BY g.id DESC LIMIT 1
                        """)) {
            statement.setString(1, normalizeLookupPlayer(tags.get("White")));
            statement.setString(2, normalizeLookupPlayer(tags.get("Black")));
            statement.setString(3, normalizeTag(tags.get("Date")));
            statement.setString(4, normalizeTag(tags.get("Round")));
            statement.setString(5, normalizeResult(tags.get("Result")));
            statement.setInt(6, startingPosition.getId());
            statement.setInt(7, moves.size());
            statement.setBytes(8, encodedMoves);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Imported chess database game could not be resolved.");
                return rs.getLong(1);
            }
        }
    }

    @Override
    public void saveAnnotatedPgn(long id, String pgn) throws SQLException {
        if (pgn == null || pgn.isBlank()) throw new IllegalArgumentException("Annotated PGN must not be blank.");
        try (Connection connection = openConnection();
                PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM game WHERE id = ? AND import_id IS NULL");
                PreparedStatement upsert = connection.prepareStatement(
                        """
                        INSERT INTO game_annotation(game_id, annotated_pgn, updated_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT(game_id) DO UPDATE SET
                            annotated_pgn = excluded.annotated_pgn,
                            updated_at = CURRENT_TIMESTAMP
                        """)) {
            exists.setLong(1, id);
            try (ResultSet rs = exists.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Chess database game not found: " + id);
            }
            upsert.setLong(1, id);
            upsert.setString(2, pgn);
            upsert.executeUpdate();
        }
    }

    @Override
    public PositionStatistics findPosition(int startingPositionId, List<String> uciMoves, int ply) throws SQLException {
        ChessStartingPosition startingPosition = ChessStartingPosition.of(startingPositionId);
        List<String> protocolMoves = uciMoves == null ? List.of() : List.copyOf(uciMoves);
        int safePly = Math.max(0, Math.min(ply, protocolMoves.size()));
        List<String> moves;
        try {
            moves = LegacyDatabaseMoveAdapter.toStorage(startingPosition, protocolMoves);
        } catch (NoMoveFoundException | IOException e) {
            throw new SQLException("Could not encode position lookup moves", e);
        }
        PositionHash hash = ZobristPositionHasher.hashAfterMoves(startingPositionId, moves, safePly);
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT move_code, games, white_wins, draws, black_wins
                        FROM position_move
                        WHERE hash_hi = ? AND hash_lo = ?
                        ORDER BY games DESC, move_code ASC
                        """)) {
            statement.setLong(1, hash.high());
            statement.setLong(2, hash.low());
            List<PositionMoveStatistics> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String storageMove = MoveCodec.decode(rs.getInt(1));
                    String protocolMove;
                    try {
                        protocolMove = LegacyDatabaseMoveAdapter.nextMoveFromStorage(
                                startingPosition,
                                protocolMoves.subList(0, safePly),
                                storageMove);
                    } catch (NoMoveFoundException | IOException e) {
                        throw new SQLException(
                                "Could not decode position continuation " + storageMove,
                                e);
                    }
                    result.add(new PositionMoveStatistics(
                            protocolMove,
                            rs.getLong(2),
                            rs.getLong(3),
                            rs.getLong(4),
                            rs.getLong(5)));
                }
            }
            return new PositionStatistics(hash, result);
        }
    }

    private void initialize() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("CREATE TABLE IF NOT EXISTS database_info(key TEXT PRIMARY KEY, value TEXT NOT NULL)");

            int storedSchemaVersion = Integer.parseInt(readInfo(connection, "schema_version", "0"));
            if (storedSchemaVersion > SCHEMA_VERSION) {
                throw new SQLException("Unsupported chess database schema version " + storedSchemaVersion + "; expected at most " + SCHEMA_VERSION);
            }
            String storedHashVersion = readInfo(connection, "hash_version", null);
            if (storedHashVersion != null && Integer.parseInt(storedHashVersion) != HASH_VERSION) {
                throw new SQLException("Unsupported chess database hash version " + storedHashVersion + "; expected " + HASH_VERSION);
            }
            String storedMoveCodecVersion = readInfo(connection, "move_codec_version", null);
            if (storedMoveCodecVersion != null && Integer.parseInt(storedMoveCodecVersion) != MOVE_CODEC_VERSION) {
                throw new SQLException("Unsupported chess database move codec version " + storedMoveCodecVersion + "; expected " + MOVE_CODEC_VERSION);
            }

            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS player(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        normalized_name TEXT NOT NULL UNIQUE
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS game(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        white_player_id INTEGER,
                        black_player_id INTEGER,
                        white_elo INTEGER,
                        black_elo INTEGER,
                        event TEXT,
                        site TEXT,
                        game_date TEXT,
                        game_year INTEGER,
                        round TEXT,
                        result TEXT,
                        eco TEXT,
                        starting_position_id INTEGER NOT NULL DEFAULT 518,
                        ply_count INTEGER NOT NULL,
                        moves BLOB NOT NULL,
                        tags TEXT NOT NULL,
                        import_id TEXT,
                        FOREIGN KEY(white_player_id) REFERENCES player(id),
                        FOREIGN KEY(black_player_id) REFERENCES player(id)
                    )
                    """);
            ensureColumn(connection, "game", "import_id", "TEXT");
            ensureColumn(connection, "game", "starting_position_id", "INTEGER NOT NULL DEFAULT 518");
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS game_annotation(
                        game_id INTEGER PRIMARY KEY,
                        annotated_pgn TEXT NOT NULL,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(game_id) REFERENCES game(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS position_move(
                        hash_hi INTEGER NOT NULL,
                        hash_lo INTEGER NOT NULL,
                        move_code INTEGER NOT NULL,
                        games INTEGER NOT NULL,
                        white_wins INTEGER NOT NULL,
                        draws INTEGER NOT NULL,
                        black_wins INTEGER NOT NULL,
                        PRIMARY KEY(hash_hi, hash_lo, move_code)
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS position_move_stage(
                        import_id TEXT NOT NULL,
                        hash_hi INTEGER NOT NULL,
                        hash_lo INTEGER NOT NULL,
                        move_code INTEGER NOT NULL,
                        games INTEGER NOT NULL,
                        white_wins INTEGER NOT NULL,
                        draws INTEGER NOT NULL,
                        black_wins INTEGER NOT NULL,
                        PRIMARY KEY(import_id, hash_hi, hash_lo, move_code)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_white_player ON game(white_player_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_black_player ON game(black_player_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_year ON game(game_year)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_result ON game(result)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_eco ON game(eco)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_import_id ON game(import_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_start_position ON game(starting_position_id)");
            statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_game_duplicate_lookup_v4
                    ON game(white_player_id, black_player_id, game_date, round, result, starting_position_id, ply_count)
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_position_stage_import_id ON position_move_stage(import_id)");
            writeInfoIfAbsent(connection, "hash_version", Integer.toString(HASH_VERSION));
            writeInfoIfAbsent(connection, "move_codec_version", Integer.toString(MOVE_CODEC_VERSION));
            writeInfoIfAbsent(connection, "name", "Chess Database");
            writeInfo(connection, "schema_version", Integer.toString(SCHEMA_VERSION));
            cleanupStaleImports(connection);
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) throws SQLException {
        boolean found = false;
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) { found = true; break; }
            }
        }
        if (!found) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private void cleanupStaleImports(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM position_move_stage");
            statement.executeUpdate("DELETE FROM game WHERE import_id IS NOT NULL");
            statement.executeUpdate(
                    """
                    DELETE FROM player
                    WHERE NOT EXISTS (
                        SELECT 1 FROM game
                        WHERE game.white_player_id = player.id OR game.black_player_id = player.id
                    )
                    """);
        }
    }

    private void finalizeImport(Connection connection, String importId) throws SQLException {
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
                PreparedStatement publish = connection.prepareStatement("UPDATE game SET import_id = NULL WHERE import_id = ?");
                PreparedStatement delete = connection.prepareStatement("DELETE FROM position_move_stage WHERE import_id = ?")) {
            merge.setString(1, importId); merge.executeUpdate();
            publish.setString(1, importId); publish.executeUpdate();
            delete.setString(1, importId); delete.executeUpdate();
        }
    }

    private void cleanupImport(String importId) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement deletePositions = connection.prepareStatement("DELETE FROM position_move_stage WHERE import_id = ?");
                    PreparedStatement deleteGames = connection.prepareStatement("DELETE FROM game WHERE import_id = ?")) {
                deletePositions.setString(1, importId); deletePositions.executeUpdate();
                deleteGames.setString(1, importId); deleteGames.executeUpdate();
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
        try { cleanupImport(importId); } catch (SQLException cleanupFailure) { original.addSuppressed(cleanupFailure); }
    }

    private void checkCancellation(BooleanSupplier cancellationRequested) throws ImportCancelledException {
        if (cancellationRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new ImportCancelledException("Chess database import cancelled.");
        }
    }

    private void publishProgress(
            Consumer<ImportProgress> progressConsumer,
            CountingInputStream inputStream,
            long totalBytes,
            long processedGames,
            long importedGames,
            long skippedGames,
            long totalPlies,
            Instant startedAt) {
        try {
            progressConsumer.accept(new ImportProgress(
                    inputStream.bytesRead(), totalBytes, processedGames, importedGames, skippedGames,
                    totalPlies, Duration.between(startedAt, Instant.now()).toMillis()));
        } catch (RuntimeException ignored) { }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private boolean isSupportedGame(Map<String, String> tags) {
        String variant = normalizeTag(tags.get("Variant"));
        if (variant == null) return true;
        String normalized = variant.toLowerCase(Locale.ROOT).replace(" ", "");
        return "standard".equals(normalized) || "chess".equals(normalized)
                || "chess960".equals(normalized) || "fischerrandom".equals(normalized);
    }

    private Long findOrCreatePlayer(
            PreparedStatement insertPlayer,
            PreparedStatement selectPlayer,
            Map<String, Long> playerCache,
            String rawName) throws SQLException {
        String displayName = normalizeTag(rawName);
        if (displayName == null || "?".equals(displayName)) return null;
        String normalized = normalizePlayerName(displayName);
        Long cached = playerCache.get(normalized);
        if (cached != null) return cached;
        insertPlayer.setString(1, displayName);
        insertPlayer.setString(2, normalized);
        insertPlayer.executeUpdate();
        selectPlayer.setString(1, normalized);
        try (ResultSet rs = selectPlayer.executeQuery()) {
            if (!rs.next()) throw new SQLException("Could not resolve player after insert: " + displayName);
            long id = rs.getLong(1);
            playerCache.put(normalized, id);
            return id;
        }
    }

    private boolean isDuplicateGame(
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
        try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
    }

    private Map<String, Long> createPlayerCache() {
        return new LinkedHashMap<>(PLAYER_CACHE_SIZE + 1, 0.75f, true) {
            private static final long serialVersionUID = 1L;
            @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) { return size() > PLAYER_CACHE_SIZE; }
        };
    }

    private void appendPlayerFilter(StringBuilder sql, List<Object> parameters, String column, String value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append(" LIKE ? ESCAPE '\\'");
        parameters.add(containsPattern(value));
    }

    private String containsPattern(String value) {
        return "%" + normalizePlayerName(value).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private String normalizePlayerName(String value) { return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private String normalizeLookupPlayer(String value) {
        String normalized = normalizeTag(value);
        return normalized == null || "?".equals(normalized) ? "" : normalizePlayerName(normalized);
    }
    private String normalizeTag(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String normalizeResult(String value) {
        return "1-0".equals(value) || "0-1".equals(value) || "1/2-1/2".equals(value) ? value : "*";
    }
    private Integer parseInteger(String value) {
        if (value == null || value.isBlank() || "?".equals(value.trim())) return null;
        try { return Integer.valueOf(value.trim()); } catch (NumberFormatException e) { return null; }
    }
    private Integer parseYear(String date) {
        if (date == null || date.length() < 4) return null;
        String year = date.substring(0, 4);
        return year.matches("\\d{4}") ? Integer.valueOf(year) : null;
    }

    private void bindNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }
    private void bindNullableInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
    }
    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value instanceof Integer integer) statement.setInt(index + 1, integer);
            else if (value instanceof Long longValue) statement.setLong(index + 1, longValue);
            else statement.setString(index + 1, String.valueOf(value));
        }
    }
    private Integer nullableInteger(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private void writeInfoIfAbsent(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO database_info(key, value) VALUES (?, ?)")) {
            statement.setString(1, key); statement.setString(2, value); statement.executeUpdate();
        }
    }
    private void writeInfo(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO database_info(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key); statement.setString(2, value); statement.executeUpdate();
        }
    }
    private String readInfo(Connection connection, String key, String fallback) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM database_info WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getString(1) : fallback; }
        }
    }

    private String encodeTags(Map<String, String> tags) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (result.length() > 0) result.append('\n');
            result.append(encoder.encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)))
                    .append(':')
                    .append(encoder.encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        return result.toString();
    }

    private Map<String, String> decodeTags(String encoded) {
        Map<String, String> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return result;
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String line : encoded.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            result.put(
                    new String(decoder.decode(line.substring(0, separator)), StandardCharsets.UTF_8),
                    new String(decoder.decode(line.substring(separator + 1)), StandardCharsets.UTF_8));
        }
        return result;
    }

    private record PositionMoveKey(long hashHigh, long hashLow, int moveCode) { }

    private static final class PositionAggregate {
        private long games;
        private long whiteWins;
        private long draws;
        private long blackWins;
        private void add(int whiteWin, int draw, int blackWin) {
            games++; whiteWins += whiteWin; draws += draw; blackWins += blackWin;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long bytesRead;
        private CountingInputStream(InputStream inputStream) { super(inputStream); }
        private long bytesRead() { return bytesRead; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) bytesRead++;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) bytesRead += count;
            return count;
        }
        @Override public long skip(long count) throws IOException {
            long skipped = super.skip(count);
            bytesRead += skipped;
            return skipped;
        }
    }
}
