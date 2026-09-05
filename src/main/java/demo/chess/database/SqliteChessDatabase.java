package demo.chess.database;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import demo.chess.definitions.engines.impl.NoMoveFoundException;
import demo.chess.game.impl.Simulation;
import demo.chess.load.GameLoader;
import demo.chess.save.GameSaver;

/**
 * SQLite-backed implementation of the local chess database.
 */
public class SqliteChessDatabase implements ChessDatabase {

    public static final int SCHEMA_VERSION = 1;
    public static final int HASH_VERSION = 1;
    public static final int MOVE_CODEC_VERSION = 1;

    private static final int COMMIT_GAME_BATCH = 1_000;
    private static final int POSITION_BATCH_SIZE = 5_000;
    private static final int PLAYER_CACHE_SIZE = 10_000;

    private final Path databasePath;
    private final GameLoader gameLoader = new GameLoader();
    private final GameSaver gameSaver = new GameSaver();

    /**
     * Creates or opens a SQLite chess database.
     *
     * @param databasePath database file path
     */
    public SqliteChessDatabase(Path databasePath) throws SQLException, IOException {
        if (databasePath == null) {
            throw new IllegalArgumentException("databasePath must not be null");
        }
        this.databasePath = databasePath.toAbsolutePath().normalize();
        Path parent = this.databasePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        initialize();
    }

    /**
     * Returns the default database file path.
     *
     * @return default database path
     */
    public static Path defaultPath() {
        String configured = System.getProperty("chess.database.path");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".chess", "database", "chess.db")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Returns database status information.
     *
     * @return database status
     */
    @Override
    public ChessDatabaseStatus getStatus() throws SQLException, IOException {
        try (Connection connection = openConnection()) {
            long count;
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM game")) {
                count = resultSet.next() ? resultSet.getLong(1) : 0L;
            }

            String name = readInfo(connection, "name", "Chess Database");
            int schemaVersion = Integer.parseInt(readInfo(
                    connection,
                    "schema_version",
                    Integer.toString(SCHEMA_VERSION)));
            long size = Files.exists(databasePath) ? Files.size(databasePath) : 0L;

            return new ChessDatabaseStatus(
                    databasePath.toString(),
                    name,
                    schemaVersion,
                    count,
                    size);
        }
    }

    /**
     * Imports one or more PGN games from a stream.
     *
     * @param inputStream PGN stream
     * @return import summary
     */
    @Override
    public ImportResult importPgn(InputStream inputStream) throws SQLException, IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream must not be null");
        }

        Instant startedAt = Instant.now();
        long importedGames = 0L;
        long skippedGames = 0L;
        long totalPlies = 0L;
        int pendingPositions = 0;
        Map<String, Long> playerCache = createPlayerCache();

        try (Connection connection = openConnection();
                PgnStreamReader pgnReader = new PgnStreamReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                PreparedStatement insertPlayer = connection.prepareStatement(
                        "INSERT OR IGNORE INTO player(name, normalized_name) VALUES (?, ?)");
                PreparedStatement selectPlayer = connection.prepareStatement(
                        "SELECT id FROM player WHERE normalized_name = ?");
                PreparedStatement insertGame = connection.prepareStatement(
                        """
                        INSERT INTO game(
                            white_player_id, black_player_id, white_elo, black_elo,
                            event, site, game_date, game_year, round, result, eco,
                            ply_count, moves, tags
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                PreparedStatement upsertPosition = connection.prepareStatement(
                        """
                        INSERT INTO position_move(
                            hash_hi, hash_lo, move_code, games, white_wins, draws, black_wins
                        ) VALUES (?, ?, ?, 1, ?, ?, ?)
                        ON CONFLICT(hash_hi, hash_lo, move_code) DO UPDATE SET
                            games = games + 1,
                            white_wins = white_wins + excluded.white_wins,
                            draws = draws + excluded.draws,
                            black_wins = black_wins + excluded.black_wins
                        """)) {

            connection.setAutoCommit(false);

            try {
                String pgn;
                while ((pgn = pgnReader.nextGame()) != null) {
                    try {
                        Map<String, String> tags = gameLoader.parsePgnTags(pgn);
                        if (!isSupportedStandardGame(tags)) {
                            skippedGames++;
                            continue;
                        }

                        List<String> moves = gameLoader.parsePgnMoveList(pgn);
                        if (moves.isEmpty()) {
                            skippedGames++;
                            continue;
                        }

                        List<PositionUpdate> positionUpdates = new ArrayList<>(moves.size());
                        ZobristPositionHasher.Cursor cursor = ZobristPositionHasher.newCursor();
                        for (String move : moves) {
                            positionUpdates.add(new PositionUpdate(cursor.hash(), MoveCodec.encode(move)));
                            cursor.apply(move);
                        }

                        Long whitePlayerId = findOrCreatePlayer(
                                insertPlayer,
                                selectPlayer,
                                playerCache,
                                tags.get("White"));
                        Long blackPlayerId = findOrCreatePlayer(
                                insertPlayer,
                                selectPlayer,
                                playerCache,
                                tags.get("Black"));

                        bindNullableLong(insertGame, 1, whitePlayerId);
                        bindNullableLong(insertGame, 2, blackPlayerId);
                        bindNullableInteger(insertGame, 3, parseInteger(tags.get("WhiteElo")));
                        bindNullableInteger(insertGame, 4, parseInteger(tags.get("BlackElo")));
                        insertGame.setString(5, normalizeTag(tags.get("Event")));
                        insertGame.setString(6, normalizeTag(tags.get("Site")));

                        String date = normalizeTag(tags.get("Date"));
                        insertGame.setString(7, date);
                        bindNullableInteger(insertGame, 8, parseYear(date));

                        insertGame.setString(9, normalizeTag(tags.get("Round")));
                        String result = normalizeResult(tags.get("Result"));
                        insertGame.setString(10, result);
                        insertGame.setString(11, normalizeTag(tags.get("ECO")));
                        insertGame.setInt(12, moves.size());
                        insertGame.setBytes(13, MoveCodec.encodeMoves(moves));
                        insertGame.setString(14, encodeTags(tags));
                        insertGame.executeUpdate();

                        int whiteWin = "1-0".equals(result) ? 1 : 0;
                        int draw = "1/2-1/2".equals(result) ? 1 : 0;
                        int blackWin = "0-1".equals(result) ? 1 : 0;

                        for (PositionUpdate update : positionUpdates) {
                            upsertPosition.setLong(1, update.hash().high());
                            upsertPosition.setLong(2, update.hash().low());
                            upsertPosition.setInt(3, update.moveCode());
                            upsertPosition.setInt(4, whiteWin);
                            upsertPosition.setInt(5, draw);
                            upsertPosition.setInt(6, blackWin);
                            upsertPosition.addBatch();
                            pendingPositions++;

                            if (pendingPositions >= POSITION_BATCH_SIZE) {
                                upsertPosition.executeBatch();
                                pendingPositions = 0;
                            }
                        }

                        importedGames++;
                        totalPlies += moves.size();

                        if (importedGames % COMMIT_GAME_BATCH == 0) {
                            if (pendingPositions > 0) {
                                upsertPosition.executeBatch();
                                pendingPositions = 0;
                            }
                            connection.commit();
                        }
                    } catch (NoMoveFoundException | IllegalArgumentException e) {
                        skippedGames++;
                    }
                }

                if (pendingPositions > 0) {
                    upsertPosition.executeBatch();
                }
                connection.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }

        long elapsed = Duration.between(startedAt, Instant.now()).toMillis();
        return new ImportResult(importedGames, skippedGames, totalPlies, elapsed);
    }

    /**
     * Searches stored games using indexed metadata.
     *
     * @param search search criteria
     * @return matching game summaries
     */
    @Override
    public List<GameSummary> findGames(GameSearch search) throws SQLException {
        GameSearch criteria = search == null
                ? new GameSearch(null, null, null, null, null, null, null, 200)
                : search;

        StringBuilder sql = new StringBuilder(
                """
                SELECT
                    g.id, g.game_date, COALESCE(w.name, '?'), COALESCE(b.name, '?'),
                    g.white_elo, g.black_elo, g.result, g.event, g.eco, g.ply_count
                FROM game g
                LEFT JOIN player w ON w.id = g.white_player_id
                LEFT JOIN player b ON b.id = g.black_player_id
                WHERE 1 = 1
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
        if (criteria.fromYear() != null) {
            sql.append(" AND g.game_year >= ?");
            parameters.add(criteria.fromYear());
        }
        if (criteria.toYear() != null) {
            sql.append(" AND g.game_year <= ?");
            parameters.add(criteria.toYear());
        }
        if (criteria.result() != null) {
            sql.append(" AND g.result = ?");
            parameters.add(criteria.result());
        }
        if (criteria.minElo() != null) {
            sql.append(" AND g.white_elo >= ? AND g.black_elo >= ?");
            parameters.add(criteria.minElo());
            parameters.add(criteria.minElo());
        }

        sql.append(" ORDER BY g.game_year DESC, g.game_date DESC, g.id DESC LIMIT ?");
        parameters.add(criteria.limit());

        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, parameters);

            List<GameSummary> result = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new GameSummary(
                            resultSet.getLong(1),
                            resultSet.getString(2),
                            resultSet.getString(3),
                            resultSet.getString(4),
                            nullableInteger(resultSet, 5),
                            nullableInteger(resultSet, 6),
                            resultSet.getString(7),
                            resultSet.getString(8),
                            resultSet.getString(9),
                            resultSet.getInt(10)));
                }
            }
            return result;
        }
    }

    /**
     * Loads a complete stored game.
     *
     * @param id game identifier
     * @return stored game
     */
    @Override
    public StoredGame getGame(long id) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tags, moves FROM game WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new NoSuchElementException("Chess database game not found: " + id);
                }
                return new StoredGame(
                        id,
                        decodeTags(resultSet.getString(1)),
                        MoveCodec.decodeMoves(resultSet.getBytes(2)));
            }
        }
    }

    /**
     * Recreates a PGN document for a stored game.
     *
     * @param id game identifier
     * @return PGN text
     */
    @Override
    public String getGameAsPgn(long id) throws SQLException, IOException, NoMoveFoundException {
        StoredGame storedGame = getGame(id);
        Simulation simulation = Simulation.createSimulation();
        gameLoader.loadGame(storedGame.uciMoves(), simulation);
        return gameSaver.toPgn(simulation.getMoveList(), storedGame.tags());
    }

    /**
     * Returns aggregated move statistics for a position.
     *
     * @param uciMoves game moves from the initial position
     * @param ply number of moves to apply
     * @return position statistics
     */
    @Override
    public PositionStatistics findPosition(List<String> uciMoves, int ply) throws SQLException {
        List<String> moves = uciMoves == null ? List.of() : uciMoves;
        int safePly = Math.max(0, Math.min(ply, moves.size()));
        PositionHash hash = ZobristPositionHasher.hashAfterMoves(moves, safePly);

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

            List<PositionMoveStatistics> statistics = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    statistics.add(new PositionMoveStatistics(
                            MoveCodec.decode(resultSet.getInt(1)),
                            resultSet.getLong(2),
                            resultSet.getLong(3),
                            resultSet.getLong(4),
                            resultSet.getLong(5)));
                }
            }
            return new PositionStatistics(hash, statistics);
        }
    }

    /**
     * Initializes schema and format metadata.
     */
    private void initialize() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");

            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS database_info(
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
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
                        ply_count INTEGER NOT NULL,
                        moves BLOB NOT NULL,
                        tags TEXT NOT NULL,
                        FOREIGN KEY(white_player_id) REFERENCES player(id),
                        FOREIGN KEY(black_player_id) REFERENCES player(id)
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

            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_white_player ON game(white_player_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_black_player ON game(black_player_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_year ON game(game_year)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_result ON game(result)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_game_eco ON game(eco)");

            writeInfoIfAbsent(connection, "schema_version", Integer.toString(SCHEMA_VERSION));
            writeInfoIfAbsent(connection, "hash_version", Integer.toString(HASH_VERSION));
            writeInfoIfAbsent(connection, "move_codec_version", Integer.toString(MOVE_CODEC_VERSION));
            writeInfoIfAbsent(connection, "name", "Chess Database");

            int storedSchemaVersion = Integer.parseInt(readInfo(
                    connection,
                    "schema_version",
                    Integer.toString(SCHEMA_VERSION)));
            if (storedSchemaVersion != SCHEMA_VERSION) {
                throw new SQLException(
                        "Unsupported chess database schema version "
                                + storedSchemaVersion
                                + "; expected "
                                + SCHEMA_VERSION);
            }

            int storedHashVersion = Integer.parseInt(readInfo(
                    connection,
                    "hash_version",
                    Integer.toString(HASH_VERSION)));
            if (storedHashVersion != HASH_VERSION) {
                throw new SQLException(
                        "Unsupported chess database hash version "
                                + storedHashVersion
                                + "; expected "
                                + HASH_VERSION);
            }

            int storedMoveCodecVersion = Integer.parseInt(readInfo(
                    connection,
                    "move_codec_version",
                    Integer.toString(MOVE_CODEC_VERSION)));
            if (storedMoveCodecVersion != MOVE_CODEC_VERSION) {
                throw new SQLException(
                        "Unsupported chess database move codec version "
                                + storedMoveCodecVersion
                                + "; expected "
                                + MOVE_CODEC_VERSION);
            }
        }
    }

    /**
     * Opens a configured SQLite connection.
     *
     * @return open connection
     */
    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    /**
     * Returns whether the PGN can be represented by the standard initial-position model.
     *
     * @param tags PGN tags
     * @return true for supported standard games
     */
    private boolean isSupportedStandardGame(Map<String, String> tags) {
        String variant = tags.get("Variant");
        if (variant != null
                && !variant.isBlank()
                && !"standard".equalsIgnoreCase(variant)
                && !"chess".equalsIgnoreCase(variant)) {
            return false;
        }

        return !tags.containsKey("FEN")
                && !"1".equals(tags.get("SetUp"));
    }

    /**
     * Finds or creates one normalized player.
     *
     * @param insertPlayer insert statement
     * @param selectPlayer select statement
     * @param playerCache bounded player cache
     * @param rawName source name
     * @return player id or null
     */
    private Long findOrCreatePlayer(
            PreparedStatement insertPlayer,
            PreparedStatement selectPlayer,
            Map<String, Long> playerCache,
            String rawName) throws SQLException {
        String displayName = normalizeTag(rawName);
        if (displayName == null || "?".equals(displayName)) {
            return null;
        }

        String normalized = normalizePlayerName(displayName);
        Long cached = playerCache.get(normalized);
        if (cached != null) {
            return cached;
        }

        insertPlayer.setString(1, displayName);
        insertPlayer.setString(2, normalized);
        insertPlayer.executeUpdate();

        selectPlayer.setString(1, normalized);
        try (ResultSet resultSet = selectPlayer.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Could not resolve player after insert: " + displayName);
            }
            long id = resultSet.getLong(1);
            playerCache.put(normalized, id);
            return id;
        }
    }

    /**
     * Creates the bounded player lookup cache.
     *
     * @return player cache
     */
    private Map<String, Long> createPlayerCache() {
        return new LinkedHashMap<>(PLAYER_CACHE_SIZE + 1, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            /**
             * Bounds the cache to avoid memory growth during very large imports.
             *
             * @param eldest eldest cache entry
             * @return true when the eldest entry should be evicted
             */
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > PLAYER_CACHE_SIZE;
            }
        };
    }

    /**
     * Appends a player-name filter to a dynamic search statement.
     *
     * @param sql SQL builder
     * @param parameters SQL parameters
     * @param column normalized player column
     * @param value requested player fragment
     */
    private void appendPlayerFilter(
            StringBuilder sql,
            List<Object> parameters,
            String column,
            String value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" LIKE ? ESCAPE '\\'");
        parameters.add(containsPattern(value));
    }

    /**
     * Creates a case-normalized LIKE pattern with escaped wildcard characters.
     *
     * @param value search text
     * @return LIKE pattern
     */
    private String containsPattern(String value) {
        String normalized = normalizePlayerName(value)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + normalized + "%";
    }

    /**
     * Normalizes a player name for indexed lookups.
     *
     * @param value source player name
     * @return normalized player name
     */
    private String normalizePlayerName(String value) {
        return value.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes one optional tag value.
     *
     * @param value source value
     * @return trimmed value or null
     */
    private String normalizeTag(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Normalizes a PGN result token.
     *
     * @param value source result
     * @return normalized result token
     */
    private String normalizeResult(String value) {
        if ("1-0".equals(value) || "0-1".equals(value) || "1/2-1/2".equals(value)) {
            return value;
        }
        return "*";
    }

    /**
     * Parses a nullable integer tag.
     *
     * @param value source value
     * @return parsed integer or null
     */
    private Integer parseInteger(String value) {
        if (value == null || value.isBlank() || "?".equals(value.trim())) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the four-digit year prefix from a PGN date.
     *
     * @param date PGN date
     * @return year or null
     */
    private Integer parseYear(String date) {
        if (date == null || date.length() < 4) {
            return null;
        }
        String year = date.substring(0, 4);
        return year.matches("\\d{4}") ? Integer.valueOf(year) : null;
    }

    /**
     * Binds a nullable long value.
     *
     * @param statement target statement
     * @param index parameter index
     * @param value value
     */
    private void bindNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    /**
     * Binds a nullable integer value.
     *
     * @param statement target statement
     * @param index parameter index
     * @param value value
     */
    private void bindNullableInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    /**
     * Binds a dynamic list of JDBC parameters.
     *
     * @param statement target statement
     * @param parameters parameters
     */
    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value instanceof Integer integer) {
                statement.setInt(index + 1, integer);
            } else if (value instanceof Long longValue) {
                statement.setLong(index + 1, longValue);
            } else {
                statement.setString(index + 1, String.valueOf(value));
            }
        }
    }

    /**
     * Reads a nullable integer column.
     *
     * @param resultSet result set
     * @param column column index
     * @return nullable integer
     */
    private Integer nullableInteger(ResultSet resultSet, int column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    /**
     * Writes one database metadata value if absent.
     *
     * @param connection database connection
     * @param key metadata key
     * @param value metadata value
     */
    private void writeInfoIfAbsent(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO database_info(key, value) VALUES (?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    /**
     * Reads a database metadata value.
     *
     * @param connection database connection
     * @param key metadata key
     * @param fallback fallback value
     * @return stored or fallback value
     */
    private String readInfo(Connection connection, String key, String fallback) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM database_info WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : fallback;
            }
        }
    }

    /**
     * Encodes PGN tags into a compact line-oriented safe representation.
     *
     * @param tags PGN tags
     * @return encoded tags
     */
    private String encodeTags(Map<String, String> tags) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        StringBuilder result = new StringBuilder();

        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(encoder.encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)))
                    .append(':')
                    .append(encoder.encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }

        return result.toString();
    }

    /**
     * Decodes stored PGN tags.
     *
     * @param encoded encoded tags
     * @return decoded tags
     */
    private Map<String, String> decodeTags(String encoded) {
        Map<String, String> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return result;
        }

        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String line : encoded.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = new String(
                    decoder.decode(line.substring(0, separator)),
                    StandardCharsets.UTF_8);
            String value = new String(
                    decoder.decode(line.substring(separator + 1)),
                    StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    /**
     * Prevalidated aggregate update generated from one imported move.
     *
     * @param hash position before the move
     * @param moveCode compact encoded move
     */
    private record PositionUpdate(PositionHash hash, int moveCode) {
    }
}
