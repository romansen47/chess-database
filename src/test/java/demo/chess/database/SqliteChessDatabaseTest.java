package demo.chess.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteChessDatabaseTest {

    @TempDir
    Path tempDirectory;

    /**
     * Verifies import, metadata search, position statistics and PGN reconstruction.
     */
    @Test
    void importsSearchesAndReconstructsGames() throws Exception {
        String pgn = twoGamePgn();

        SqliteChessDatabase database = new SqliteChessDatabase(tempDirectory.resolve("test.db"));
        ImportResult importResult = database.importPgn(
                new ByteArrayInputStream(pgn.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, importResult.importedGames());
        assertEquals(0, importResult.skippedGames());
        assertEquals(2, database.getStatus().gameCount());

        List<GameSummary> alphaGames = database.findGames(
                new GameSearch(null, null, "alpha", null, null, null, null, 50));
        assertEquals(2, alphaGames.size());

        PositionStatistics initialPosition = database.findPosition(List.of(), 0);
        assertEquals(2, initialPosition.moves().size());
        assertEquals("d2d4", initialPosition.moves().get(0).move());
        assertEquals("e2e4", initialPosition.moves().get(1).move());
        assertEquals(1, initialPosition.moves().get(0).games());
        assertEquals(1, initialPosition.moves().get(1).games());

        String reconstructedPgn = database.getGameAsPgn(alphaGames.get(0).id());
        assertTrue(reconstructedPgn.contains("[White "));
        assertTrue(reconstructedPgn.contains("[Black "));
        assertTrue(reconstructedPgn.contains("1."));
    }

    /**
     * Verifies cancellation removes the entire staged import and emits useful progress.
     */
    @Test
    void cancelledImportDoesNotPublishPartialGamesOrPositions() throws Exception {
        String pgn = twoGamePgn();
        byte[] bytes = pgn.getBytes(StandardCharsets.UTF_8);
        SqliteChessDatabase database = new SqliteChessDatabase(tempDirectory.resolve("cancel.db"));
        AtomicBoolean cancel = new AtomicBoolean(false);
        List<ImportProgress> progressSnapshots = new ArrayList<>();

        assertThrows(
                ImportCancelledException.class,
                () -> database.importPgn(
                        "cancel-test",
                        new ByteArrayInputStream(bytes),
                        bytes.length,
                        progress -> {
                            progressSnapshots.add(progress);
                            if (progress.processedGames() >= 1) {
                                cancel.set(true);
                            }
                        },
                        cancel::get));

        assertFalse(progressSnapshots.isEmpty());
        assertTrue(progressSnapshots.stream().anyMatch(progress -> progress.processedGames() >= 1));
        assertEquals(0, database.getStatus().gameCount());
        assertTrue(database.findGames(
                new GameSearch(null, null, null, null, null, null, null, 50)).isEmpty());
        assertTrue(database.findPosition(List.of(), 0).moves().isEmpty());
    }

    /**
     * Returns a compact two-game PGN fixture.
     */
    private String twoGamePgn() {
        return """
                [Event "Test One"]
                [Site "?"]
                [Date "2024.01.02"]
                [Round "1"]
                [White "Alpha, Alice"]
                [Black "Beta, Bob"]
                [WhiteElo "2500"]
                [BlackElo "2450"]
                [Result "1-0"]
                [ECO "C20"]

                1. e4 e5 2. Nf3 Nc6 1-0

                [Event "Test Two"]
                [Site "?"]
                [Date "2023.05.06"]
                [Round "2"]
                [White "Gamma, Gina"]
                [Black "Alpha, Alice"]
                [WhiteElo "2400"]
                [BlackElo "2510"]
                [Result "1/2-1/2"]
                [ECO "D00"]

                1. d4 d5 2. Nf3 Nf6 1/2-1/2
                """;
    }
}
