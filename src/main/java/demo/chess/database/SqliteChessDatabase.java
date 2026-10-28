package demo.chess.database;

import static demo.chess.database.SqliteDatabaseSupport.bindNullableInteger;
import static demo.chess.database.SqliteDatabaseSupport.bindNullableLong;
import static demo.chess.database.SqliteDatabaseSupport.bindParameters;
import static demo.chess.database.SqliteDatabaseSupport.decodeTags;
import static demo.chess.database.SqliteDatabaseSupport.encodeTags;
import static demo.chess.database.SqliteDatabaseSupport.normalizeLookupPlayer;
import static demo.chess.database.SqliteDatabaseSupport.normalizePlayerName;
import static demo.chess.database.SqliteDatabaseSupport.normalizeResult;
import static demo.chess.database.SqliteDatabaseSupport.normalizeTag;
import static demo.chess.database.SqliteDatabaseSupport.nullableInteger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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

    public static final int SCHEMA_VERSION = 5;
    public static final int HASH_VERSION = 1;
    public static final int MOVE_CODEC_VERSION = 1;

    private final Path databasePath;
    private final GameLoader gameLoader = new GameLoader();
    private final GameSaver gameSaver = new GameSaver();
    private final SqlitePgnImporter pgnImporter;

    /**
     * Opens or creates the local SQLite chess database at the supplied path.
     *
     * @param databasePath database file path
     * @throws SQLException when schema initialization fails
     * @throws IOException when the parent directory cannot be created
     */
    public SqliteChessDatabase(Path databasePath) throws SQLException, IOException {
        if (databasePath == null) throw new IllegalArgumentException("databasePath must not be null");
        this.databasePath = databasePath.toAbsolutePath().normalize();
        Path parent = this.databasePath.getParent();
        if (parent != null) Files.createDirectories(parent);
        this.pgnImporter = new SqlitePgnImporter(this.databasePath, gameLoader);
        initialize();
    }

    /**
     * Returns the configured database path or the default path below the user home.
     *
     * @return normalized database path
     */
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
        return pgnImporter.importPgn(
                importId,
                inputStream,
                totalBytes,
                progressConsumer,
                cancellationRequested);
    }

    @Override
    public List<GameSummary> findGames(GameSearch search) throws SQLException {
        GameSearch criteria = search == null
                ? new GameSearch(null, null, null, null, null, null, null, null, 200)
                : search;
        GameSearchQueryBuilder.Query query = GameSearchQueryBuilder.build(criteria);

        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(query.sql())) {
            bindParameters(statement, query.parameters());
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
        if (pgn == null || pgn.isBlank()) {
            throw new IllegalArgumentException("Annotated PGN must not be blank.");
        }

        final Map<String, String> tags;
        final ChessStartingPosition parsedStartingPosition;
        final List<String> parsedMoves;
        try {
            tags = gameLoader.parsePgnTags(pgn);
            parsedStartingPosition = gameLoader.parsePgnStartingPosition(pgn);
            parsedMoves = LegacyDatabaseMoveAdapter.toStorage(
                    parsedStartingPosition,
                    gameLoader.parsePgnMoveList(pgn));
        } catch (NoMoveFoundException | IOException | IllegalArgumentException e) {
            throw new SQLException("Could not validate annotated PGN metadata.", e);
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                StoredMetadata original = readStoredMetadata(connection, id);
                if (original.startingPositionId() != parsedStartingPosition.getId()
                        || !original.moves().equals(parsedMoves)) {
                    throw new IllegalArgumentException(
                            "Annotated PGN metadata update must not change the starting position or move history.");
                }

                Map<String, Long> playerCache = createPlayerCache();
                try (PreparedStatement insertPlayer = connection.prepareStatement(
                            "INSERT OR IGNORE INTO player(name, normalized_name) VALUES (?, ?)");
                        PreparedStatement selectPlayer = connection.prepareStatement(
                            "SELECT id FROM player WHERE normalized_name = ?");
                        PreparedStatement updateGame = connection.prepareStatement(
                            """
                            UPDATE game SET
                                white_player_id = ?,
                                black_player_id = ?,
                                white_elo = ?,
                                black_elo = ?,
                                event = ?,
                                site = ?,
                                game_date = ?,
                                game_year = ?,
                                round = ?,
                                result = ?,
                                eco = ?,
                                tags = ?
                            WHERE id = ? AND import_id IS NULL
                            """);
                        PreparedStatement upsert = connection.prepareStatement(
                            ImportedPgnAnnotations.SAVE_PGN)) {
                    Long whiteId = findOrCreatePlayer(
                            insertPlayer, selectPlayer, playerCache, tags.get("White"));
                    Long blackId = findOrCreatePlayer(
                            insertPlayer, selectPlayer, playerCache, tags.get("Black"));
                    String date = normalizeTag(tags.get("Date"));
                    String newResult = normalizeResult(tags.get("Result"));

                    if (!normalizeResult(original.result()).equals(newResult)) {
                        adjustPositionResultStatistics(
                                connection,
                                parsedStartingPosition,
                                parsedMoves,
                                normalizeResult(original.result()),
                                newResult);
                    }

                    bindNullableLong(updateGame, 1, whiteId);
                    bindNullableLong(updateGame, 2, blackId);
                    bindNullableInteger(updateGame, 3, parseInteger(tags.get("WhiteElo")));
                    bindNullableInteger(updateGame, 4, parseInteger(tags.get("BlackElo")));
                    updateGame.setString(5, normalizeTag(tags.get("Event")));
                    updateGame.setString(6, normalizeTag(tags.get("Site")));
                    updateGame.setString(7, date);
                    bindNullableInteger(updateGame, 8, parseYear(date));
                    updateGame.setString(9, normalizeTag(tags.get("Round")));
                    updateGame.setString(10, newResult);
                    updateGame.setString(11, normalizeTag(tags.get("ECO")));
                    updateGame.setString(12, encodeTags(tags));
                    updateGame.setLong(13, id);
                    if (updateGame.executeUpdate() != 1) {
                        throw new NoSuchElementException("Chess database game not found: " + id);
                    }

                    upsert.setLong(1, id);
                    upsert.setString(2, pgn);
                    upsert.executeUpdate();
                }

                cleanupOrphanPlayers(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private StoredMetadata readStoredMetadata(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT result, starting_position_id, moves
                FROM game
                WHERE id = ? AND import_id IS NULL
                """)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new NoSuchElementException("Chess database game not found: " + id);
                }
                return new StoredMetadata(
                        rs.getString(1),
                        rs.getInt(2),
                        MoveCodec.decodeMoves(rs.getBytes(3)));
            }
        }
    }

    private void adjustPositionResultStatistics(
            Connection connection,
            ChessStartingPosition startingPosition,
            List<String> moves,
            String oldResult,
            String newResult) throws SQLException {
        ResultContribution oldContribution = resultContribution(oldResult);
        ResultContribution newContribution = resultContribution(newResult);
        int whiteDelta = newContribution.whiteWins() - oldContribution.whiteWins();
        int drawDelta = newContribution.draws() - oldContribution.draws();
        int blackDelta = newContribution.blackWins() - oldContribution.blackWins();
        if (whiteDelta == 0 && drawDelta == 0 && blackDelta == 0) return;

        ZobristPositionHasher.Cursor cursor = ZobristPositionHasher.newCursor(startingPosition);
        try (PreparedStatement update = connection.prepareStatement(
                """
                UPDATE position_move
                SET white_wins = white_wins + ?,
                    draws = draws + ?,
                    black_wins = black_wins + ?
                WHERE hash_hi = ? AND hash_lo = ? AND move_code = ?
                """)) {
            for (String move : moves) {
                PositionHash hash = cursor.hash();
                update.setInt(1, whiteDelta);
                update.setInt(2, drawDelta);
                update.setInt(3, blackDelta);
                update.setLong(4, hash.high());
                update.setLong(5, hash.low());
                update.setInt(6, MoveCodec.encode(move));
                if (update.executeUpdate() != 1) {
                    throw new SQLException(
                            "Missing position aggregate while updating game result metadata.");
                }
                cursor.apply(move);
            }
        }
    }

    private ResultContribution resultContribution(String result) {
        return switch (normalizeResult(result)) {
            case "1-0" -> new ResultContribution(1, 0, 0);
            case "0-1" -> new ResultContribution(0, 0, 1);
            case "1/2-1/2" -> new ResultContribution(0, 1, 0);
            default -> new ResultContribution(0, 0, 0);
        };
    }

    private void cleanupOrphanPlayers(Connection connection) throws SQLException {
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
            ImportedPgnAnnotations.createSchema(statement);
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
            statement.executeUpdate("DELETE FROM game_annotation_stage");
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

    private Connection openConnection() throws SQLException {
        return SqliteDatabaseSupport.openConnection(databasePath);
    }

    private void appendPlayerFilter(StringBuilder sql, List<Object> parameters, String column, String value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append(" LIKE ? ESCAPE '\\'");
        parameters.add(containsPattern(value));
    }

    private String containsPattern(String value) {
        return "%" + normalizePlayerName(value).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
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

    private record StoredMetadata(String result, int startingPositionId, List<String> moves) { }
    private record ResultContribution(int whiteWins, int draws, int blackWins) { }
}
