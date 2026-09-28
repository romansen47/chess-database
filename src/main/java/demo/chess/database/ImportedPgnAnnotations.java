package demo.chess.database;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import demo.chess.notation.PgnAnnotationMerger;

/** Import-local annotations; only publication may change an existing game's PGN. */
final class ImportedPgnAnnotations implements AutoCloseable {
    private static final Pattern ANNOTATION_MARKER = Pattern.compile("[\\{;(\\$]|[a-h1-8][!?]");

    static final String SAVE_PGN = """
            INSERT INTO game_annotation(game_id, annotated_pgn, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(game_id) DO UPDATE SET
                annotated_pgn = excluded.annotated_pgn, updated_at = CURRENT_TIMESTAMP
            """;

    private final PreparedStatement find;
    private final PreparedStatement stage;
    private final PgnAnnotationMerger merger = new PgnAnnotationMerger();

    ImportedPgnAnnotations(Connection connection) throws SQLException {
        find = connection.prepareStatement("SELECT annotated_pgn FROM game_annotation_stage WHERE import_id = ? AND game_id = ?");
        stage = connection.prepareStatement("""
                INSERT INTO game_annotation_stage(import_id, game_id, annotated_pgn) VALUES (?, ?, ?)
                ON CONFLICT(import_id, game_id) DO UPDATE SET annotated_pgn = excluded.annotated_pgn
                """);
    }

    static void createSchema(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS game_annotation_stage(
                    import_id TEXT NOT NULL,
                    game_id INTEGER NOT NULL,
                    annotated_pgn TEXT NOT NULL,
                    PRIMARY KEY(import_id, game_id),
                    FOREIGN KEY(game_id) REFERENCES game(id) ON DELETE CASCADE
                )
                """);
    }

    void stage(String importId, long gameId, String pgn) throws SQLException, IOException {
        // This cheap check also allows false positives in headers: preservation is preferable
        // to another full SAN replay for every game in a large, unannotated library.
        if (!ANNOTATION_MARKER.matcher(pgn).find()) return;
        find.setString(1, importId);
        find.setLong(2, gameId);
        String previous = null;
        try (ResultSet rs = find.executeQuery()) {
            if (rs.next()) previous = rs.getString(1);
        }
        String enriched = merger.fillMissing(previous, pgn);
        if (enriched.equals(previous)) return;
        stage.setString(1, importId);
        stage.setLong(2, gameId);
        stage.setString(3, enriched);
        stage.executeUpdate();
    }

    static void publish(Connection connection, String importId, BooleanSupplier cancelled)
            throws SQLException, IOException {
        PgnAnnotationMerger merger = new PgnAnnotationMerger();
        try (PreparedStatement select = connection.prepareStatement("""
                    SELECT s.game_id, s.annotated_pgn, a.annotated_pgn
                    FROM game_annotation_stage s
                    LEFT JOIN game_annotation a ON a.game_id = s.game_id
                    WHERE s.import_id = ?
                    """);
                PreparedStatement save = connection.prepareStatement(SAVE_PGN)) {
            select.setString(1, importId);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new ImportCancelledException("Chess database import cancelled.");
                    }
                    String existing = rs.getString(3);
                    String merged = merger.fillMissing(existing, rs.getString(2));
                    if (!merged.equals(existing)) {
                        save.setLong(1, rs.getLong(1));
                        save.setString(2, merged);
                        save.executeUpdate();
                    }
                }
            }
        }
        cleanup(connection, importId);
    }

    static void cleanup(Connection connection, String importId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM game_annotation_stage WHERE import_id = ?")) {
            delete.setString(1, importId);
            delete.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        try { find.close(); } finally { stage.close(); }
    }
}
