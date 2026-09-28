package demo.chess.database;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import demo.chess.notation.PgnAnnotationParser;

class ImportedPgnAnnotationsTest {
    @TempDir Path directory;
    private static final String GAME = "[White \"Alice\"]\n[Black \"Bob\"]\n[Result \"*\"]\n\n1. e4 e5 *\n";

    private ByteArrayInputStream input(String pgn) {
        return new ByteArrayInputStream(pgn.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void preservesLibraryAnnotationsAndEnrichesDuplicatesWithoutChangingPositions() throws Exception {
        var db = new SqliteChessDatabase(directory.resolve("library.db"));
        db.importPgn(input(GAME));
        long id = db.findGameId(GAME);
        var before = db.findPosition(List.of(), 0);
        String annotated = GAME.replace("1. e4", "{Introduction} 1. e4 {My note. [%clk 0:05:00]}");
        String supplement = GAME.replace("1. e4 e5", "1. e4 {Other note. [%clk 0:04:00] [%emt 0:00:02]} e5 {Reply}");
        db.importPgn(input(annotated + "\n" + supplement));
        var annotations = new PgnAnnotationParser().parse(db.getGameAsPgn(id));
        assertEquals("Introduction", annotations.get(0).comment());
        assertEquals("My note.", annotations.get(1).comment());
        assertEquals(300000L, annotations.get(1).clockMillis());
        assertEquals(2000L, annotations.get(1).elapsedMoveMillis());
        assertEquals("Reply", annotations.get(2).comment());
        assertEquals(before, db.findPosition(List.of(), 0));
        assertEquals(1, db.getStatus().gameCount());
        String saved = db.getGameAsPgn(id);
        db.importPgn(input(supplement));
        assertEquals(saved, db.getGameAsPgn(id));
    }

    @Test
    void newLibraryRetainsMultilineAndSemicolonComments() throws Exception {
        var db = new SqliteChessDatabase(directory.resolve("multiline.db"));
        String annotated = GAME.replace("1. e4 e5", "1. e4 {Long note\n[%clk 0:01:00]\n[Event \"not a new game\"]\n} e5 ;Black's note\n");
        var result = db.importPgn(input(annotated));
        assertEquals(1, result.importedGames());
        assertEquals(annotated, db.getGameAsPgn(db.findGameId(GAME)));
    }

    @Test
    void cancellationAfterCommittedBatchDoesNotAlterExistingGame() throws Exception {
        Path path = directory.resolve("cancel.db");
        var db = new SqliteChessDatabase(path);
        db.importPgn(input(GAME));
        long id = db.findGameId(GAME);
        String before = db.getGameAsPgn(id);
        String enriched = GAME.replace("e4", "e4 {[%clk 0:05:00]}");
        AtomicBoolean cancel = new AtomicBoolean();
        assertThrows(ImportCancelledException.class, () -> db.importPgn("cancel", input(enriched.repeat(10001)), -1,
                progress -> { if (progress.processedGames() >= 10000) cancel.set(true); }, cancel::get));
        assertEquals(before, db.getGameAsPgn(id));
        assertEquals(1, db.findPosition(List.of(), 0).moves().getFirst().games());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM game_annotation_stage")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void publicationRespectsEditsMadeWhileImportWasReading() throws Exception {
        var db = new SqliteChessDatabase(directory.resolve("edits.db"));
        db.importPgn(input(GAME));
        long id = db.findGameId(GAME);
        // Exercise the same staged publication used after each imported library.
        Path path = directory.resolve("edits.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var staged = new ImportedPgnAnnotations(connection)) {
            staged.stage("test", id, GAME.replace("e4", "e4 {Imported [%clk 0:05:00]}"));
            db.saveAnnotatedPgn(id, GAME.replace("e4", "e4 {My edit}"));
            connection.setAutoCommit(false);
            ImportedPgnAnnotations.publish(connection, "test", () -> false);
            connection.commit();
        }
        var annotation = new PgnAnnotationParser().parse(db.getGameAsPgn(id)).get(1);
        assertEquals("My edit", annotation.comment());
        assertEquals(300000L, annotation.clockMillis());
    }
}
