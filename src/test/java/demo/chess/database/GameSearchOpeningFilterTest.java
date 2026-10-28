package demo.chess.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration coverage for ECO and opening-line game search filters. */
class GameSearchOpeningFilterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void filtersByEcoPrefixAndOpeningMovePrefix() throws Exception {
        SqliteChessDatabase database =
                new SqliteChessDatabase(tempDirectory.resolve("opening-search.db"));
        database.importPgn(new ByteArrayInputStream(fixture().getBytes(StandardCharsets.UTF_8)));

        GameSearch commonKingPawn = search(
                null,
                518,
                List.of("e2e4", "e7e5", "g1f3", "b8c6"));
        assertEquals(2, database.findGames(commonKingPawn).size());

        GameSearch italian = search(
                null,
                518,
                List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5"));
        assertEquals(1, database.findGames(italian).size());
        assertEquals("C50", database.findGames(italian).get(0).eco());

        assertEquals(2, database.findGames(search("C", null, List.of())).size());
        assertEquals(1, database.findGames(search("B2", null, List.of())).size());
    }

    @Test
    void variationSearchMatchesAnyExplicitMoveOrderButNotUnlistedTranspositions() throws Exception {
        String pgn = """
                [Event "Move order A"]
                [White "A"]
                [Black "B"]
                [Result "*"]

                1. Nf3 Nf6 2. c4 g6 3. Nc3 Bg7 4. d4 O-O *

                [Event "Move order B"]
                [White "C"]
                [Black "D"]
                [Result "*"]

                1. d4 Nf6 2. c4 g6 3. Nc3 Bg7 4. Nf3 O-O *
                """;
        SqliteChessDatabase database =
                new SqliteChessDatabase(tempDirectory.resolve("move-orders.db"));
        database.importPgn(new ByteArrayInputStream(pgn.getBytes(StandardCharsets.UTF_8)));

        OpeningLineSearch first = new OpeningLineSearch(
                518, List.of("g1f3", "g8f6", "c2c4", "g7g6"));
        OpeningLineSearch second = new OpeningLineSearch(
                518, List.of("d2d4", "g8f6", "c2c4", "g7g6"));

        GameSearch both = new GameSearch(
                null, null, null, null,
                null, null, null, null,
                null, List.of(first, second), 50);
        assertEquals(2, database.findGames(both).size());

        GameSearch firstOnly = new GameSearch(
                null, null, null, null,
                null, null, null, null,
                null, List.of(first), 50);
        assertEquals(1, database.findGames(firstOnly).size());
    }

    @Test
    void openingPrefixUsesUnifiedUciAcrossLegacyPosition518Storage() throws Exception {
        SqliteChessDatabase database =
                new SqliteChessDatabase(tempDirectory.resolve("castling-search.db"));
        database.importPgn(new ByteArrayInputStream(fixture().getBytes(StandardCharsets.UTF_8)));

        List<String> ruyWithCastle = List.of(
                "e2e4", "e7e5",
                "g1f3", "b8c6",
                "f1b5", "a7a6",
                "b5a4", "g8f6",
                "e1h1");

        List<GameSummary> matches = database.findGames(search(null, 518, ruyWithCastle));

        assertEquals(1, matches.size());
        assertEquals("C60", matches.get(0).eco());
    }

    private GameSearch search(String eco, Integer startingPositionId, List<String> moves) {
        List<OpeningLineSearch> openingLines = startingPositionId == null || moves.isEmpty()
                ? List.of()
                : List.of(new OpeningLineSearch(startingPositionId, moves));
        return new GameSearch(
                null, null, null, null,
                null, null, null, null,
                eco, openingLines, 50);
    }

    private String fixture() {
        return """
                [Event "Italian"]
                [White "Alpha"]
                [Black "Beta"]
                [Result "*"]
                [ECO "C50"]

                1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 *

                [Event "Ruy"]
                [White "Gamma"]
                [Black "Delta"]
                [Result "*"]
                [ECO "C60"]

                1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O *

                [Event "Sicilian"]
                [White "Epsilon"]
                [Black "Zeta"]
                [Result "*"]
                [ECO "B20"]

                1. e4 c5 2. Nf3 Nc6 *
                """;
    }
}
