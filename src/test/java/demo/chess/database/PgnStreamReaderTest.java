package demo.chess.database;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

class PgnStreamReaderTest {

    /**
     * Verifies that a tag-looking line inside a brace comment is not mistaken
     * for the beginning of the next PGN game.
     *
     * @throws Exception when the in-memory reader cannot be consumed
     */
    @Test
    void keepsCommentContentInsideTheCurrentGame() throws Exception {
        String source = """
                [Event "First"]
                [Result "1-0"]

                1. e4 {comment starts
                [Event "not a boundary"]
                comment ends} e5 1-0

                [Event "Second"]
                [Result "0-1"]

                1. d4 d5 0-1
                """;

        try (PgnStreamReader reader = new PgnStreamReader(new StringReader(source))) {
            String first = reader.nextGame();
            String second = reader.nextGame();

            assertTrue(first.contains("[Event \"First\"]"));
            assertTrue(first.contains("[Event \"not a boundary\"]"));
            assertTrue(second.contains("[Event \"Second\"]"));
            assertNull(reader.nextGame());
        }
    }
}
