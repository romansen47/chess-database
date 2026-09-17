package demo.chess.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Chess960DatabaseTest {

    @TempDir
    Path tempDirectory;

    @Test
    void importsStoresAndIndexesChess960ByStartingPosition() throws Exception {
        String pgn = """
                [Event "Chess960 Test"]
                [Site "?"]
                [Date "2026.09.17"]
                [Round "1"]
                [White "White"]
                [Black "Black"]
                [Result "*"]
                [Variant "Chess960"]
                [SetUp "1"]
                [FEN "bbqnnrkr/pppppppp/8/8/8/8/PPPPPPPP/BBQNNRKR w HFhf - 0 1"]

                1. a4 a5 *
                """;

        SqliteChessDatabase database = new SqliteChessDatabase(tempDirectory.resolve("chess960.db"));
        ImportResult result = database.importPgn(
                new ByteArrayInputStream(pgn.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, result.importedGames());
        long id = database.findGameId(pgn);
        StoredGame stored = database.getGame(id);
        assertEquals(0, stored.startingPositionId());
        assertEquals(List.of("a2a4", "a7a5"), stored.uciMoves());

        PositionStatistics chess960Initial = database.findPosition(0, List.of(), 0);
        assertEquals(1, chess960Initial.moves().size());
        assertEquals("a2a4", chess960Initial.moves().get(0).move());
        assertTrue(database.findPosition(518, List.of(), 0).moves().isEmpty());

        String reconstructed = database.getGameAsPgn(id);
        assertTrue(reconstructed.contains("[Variant \"Chess960\"]"));
        assertTrue(reconstructed.contains("[SetUp \"1\"]"));
        assertTrue(reconstructed.contains("[FEN \"bbqnnrkr/pppppppp/8/8/8/8/PPPPPPPP/BBQNNRKR w HFhf - 0 1\"]"));
    }
}
