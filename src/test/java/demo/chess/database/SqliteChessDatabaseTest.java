package demo.chess.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

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
        String pgn = """
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
}
