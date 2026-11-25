package demo.chess.database;

import static demo.chess.database.SqliteDatabaseSupport.bindNullableInteger;
import static demo.chess.database.SqliteDatabaseSupport.nullableInteger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/** SQLite persistence boundary for immutable completed analysis runs. */
final class SqliteAnalysisRunStore {

    private final Path databasePath;

    SqliteAnalysisRunStore(Path databasePath) {
        this.databasePath = databasePath;
    }

    static void createSchema(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS analysis_run(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    game_key TEXT NOT NULL,
                    engine_name TEXT NOT NULL,
                    engine_profile_id TEXT NOT NULL,
                    requested_depth INTEGER NOT NULL,
                    move_time_seconds INTEGER NOT NULL,
                    multi_pv INTEGER NOT NULL,
                    options_snapshot TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    active INTEGER NOT NULL DEFAULT 0
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS analysis_position(
                    run_id INTEGER NOT NULL,
                    ply INTEGER NOT NULL,
                    evaluation REAL NOT NULL,
                    depth INTEGER NOT NULL,
                    mate_distance INTEGER,
                    PRIMARY KEY(run_id, ply),
                    FOREIGN KEY(run_id) REFERENCES analysis_run(id) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS analysis_line(
                    run_id INTEGER NOT NULL,
                    ply INTEGER NOT NULL,
                    rank INTEGER NOT NULL,
                    evaluation REAL NOT NULL,
                    depth INTEGER NOT NULL,
                    mate_distance INTEGER,
                    uci_moves TEXT NOT NULL,
                    PRIMARY KEY(run_id, ply, rank),
                    FOREIGN KEY(run_id, ply) REFERENCES analysis_position(run_id, ply) ON DELETE CASCADE
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS idx_analysis_run_game_key ON analysis_run(game_key, created_at DESC)");
    }

    long save(AnalysisRun run) throws SQLException {
        if (run == null) throw new IllegalArgumentException("run must not be null");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                deactivateRuns(connection, run.gameKey());
                long runId = insertRun(connection, run);
                insertPositions(connection, runId, run.positions());
                connection.commit();
                return runId;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    List<AnalysisRunSummary> find(String gameKey) throws SQLException {
        if (gameKey == null || gameKey.isBlank()) return List.of();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT r.id, r.engine_name, r.engine_profile_id, r.requested_depth,
                               r.move_time_seconds, r.multi_pv, COUNT(p.ply),
                               COALESCE(MAX(p.depth), 0), r.created_at, r.active
                        FROM analysis_run r
                        LEFT JOIN analysis_position p ON p.run_id = r.id
                        WHERE r.game_key = ?
                        GROUP BY r.id
                        ORDER BY r.created_at DESC, r.id DESC
                        """)) {
            statement.setString(1, gameKey);
            List<AnalysisRunSummary> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new AnalysisRunSummary(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getInt(5), rs.getInt(6),
                            rs.getInt(7), rs.getInt(8), rs.getString(9),
                            rs.getInt(10) != 0));
                }
            }
            return result;
        }
    }

    AnalysisRun get(long id) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT game_key, engine_name, engine_profile_id, requested_depth,
                               move_time_seconds, multi_pv, options_snapshot, created_at, active
                        FROM analysis_run WHERE id = ?
                        """)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Analysis run not found: " + id);
                return new AnalysisRun(
                        id, rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7),
                        rs.getString(8), rs.getInt(9) != 0,
                        readPositions(connection, id));
            }
        }
    }

    void activate(String gameKey, long runId) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                requireRun(connection, gameKey, runId);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE analysis_run SET active = CASE WHEN id = ? THEN 1 ELSE 0 END WHERE game_key = ?")) {
                    update.setLong(1, runId);
                    update.setString(2, gameKey);
                    update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private long insertRun(Connection connection, AnalysisRun run) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO analysis_run(
                    game_key, engine_name, engine_profile_id, requested_depth,
                    move_time_seconds, multi_pv, options_snapshot, active)
                VALUES(?, ?, ?, ?, ?, ?, ?, 1)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, run.gameKey());
            insert.setString(2, run.engineName());
            insert.setString(3, run.engineProfileId());
            insert.setInt(4, run.requestedDepth());
            insert.setInt(5, run.moveTimeSeconds());
            insert.setInt(6, run.multiPv());
            insert.setString(7, run.optionsSnapshot());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Could not create analysis run");
                return keys.getLong(1);
            }
        }
    }

    private void insertPositions(
            Connection connection,
            long runId,
            List<AnalysisPosition> positions) throws SQLException {
        try (PreparedStatement positionInsert = connection.prepareStatement(
                    "INSERT INTO analysis_position(run_id, ply, evaluation, depth, mate_distance) VALUES(?, ?, ?, ?, ?)");
                PreparedStatement lineInsert = connection.prepareStatement("""
                    INSERT INTO analysis_line(
                        run_id, ply, rank, evaluation, depth, mate_distance, uci_moves)
                    VALUES(?, ?, ?, ?, ?, ?, ?)
                    """)) {
            for (AnalysisPosition position : positions) {
                positionInsert.setLong(1, runId);
                positionInsert.setInt(2, position.ply());
                positionInsert.setDouble(3, position.evaluation());
                positionInsert.setInt(4, position.depth());
                bindNullableInteger(positionInsert, 5, position.mateDistance());
                positionInsert.addBatch();
                for (AnalysisLine line : position.lines()) {
                    lineInsert.setLong(1, runId);
                    lineInsert.setInt(2, position.ply());
                    lineInsert.setInt(3, line.rank());
                    lineInsert.setDouble(4, line.evaluation());
                    lineInsert.setInt(5, line.depth());
                    bindNullableInteger(lineInsert, 6, line.mateDistance());
                    lineInsert.setString(7, String.join(" ", line.uciMoves()));
                    lineInsert.addBatch();
                }
            }
            positionInsert.executeBatch();
            lineInsert.executeBatch();
        }
    }

    private List<AnalysisPosition> readPositions(Connection connection, long runId)
            throws SQLException {
        List<AnalysisPosition> result = new ArrayList<>();
        try (PreparedStatement positions = connection.prepareStatement(
                "SELECT ply, evaluation, depth, mate_distance FROM analysis_position WHERE run_id = ? ORDER BY ply")) {
            positions.setLong(1, runId);
            try (ResultSet rs = positions.executeQuery()) {
                while (rs.next()) {
                    int ply = rs.getInt(1);
                    result.add(new AnalysisPosition(
                            ply, rs.getDouble(2), rs.getInt(3), nullableInteger(rs, 4),
                            readLines(connection, runId, ply)));
                }
            }
        }
        return result;
    }

    private List<AnalysisLine> readLines(Connection connection, long runId, int ply)
            throws SQLException {
        List<AnalysisLine> result = new ArrayList<>();
        try (PreparedStatement lines = connection.prepareStatement("""
                SELECT rank, evaluation, depth, mate_distance, uci_moves
                FROM analysis_line WHERE run_id = ? AND ply = ? ORDER BY rank
                """)) {
            lines.setLong(1, runId);
            lines.setInt(2, ply);
            try (ResultSet rs = lines.executeQuery()) {
                while (rs.next()) {
                    String moves = rs.getString(5);
                    result.add(new AnalysisLine(
                            rs.getInt(1), rs.getDouble(2), rs.getInt(3), nullableInteger(rs, 4),
                            moves == null || moves.isBlank()
                                    ? List.of() : List.of(moves.trim().split("\\s+"))));
                }
            }
        }
        return result;
    }

    private void deactivateRuns(Connection connection, String gameKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE analysis_run SET active = 0 WHERE game_key = ?")) {
            statement.setString(1, gameKey);
            statement.executeUpdate();
        }
    }

    private void requireRun(Connection connection, String gameKey, long runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM analysis_run WHERE id = ? AND game_key = ?")) {
            statement.setLong(1, runId);
            statement.setString(2, gameKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Analysis run not found for game: " + runId);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return SqliteDatabaseSupport.openConnection(databasePath);
    }
}
