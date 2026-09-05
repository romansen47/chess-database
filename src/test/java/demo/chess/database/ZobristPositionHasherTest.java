package demo.chess.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ZobristPositionHasherTest {

    /**
     * Verifies that move-order transpositions produce the same position hash.
     */
    @Test
    void transpositionsProduceTheSameHash() {
        PositionHash first = ZobristPositionHasher.hashAfterMoves(
                List.of("g1f3", "g8f6", "g2g3", "g7g6"),
                4);
        PositionHash second = ZobristPositionHasher.hashAfterMoves(
                List.of("g2g3", "g7g6", "g1f3", "g8f6"),
                4);

        assertEquals(first, second);
    }

    /**
     * Verifies that castling rights remain part of the position identity.
     */
    @Test
    void castlingRightsChangeTheHashEvenWithEqualPiecePlacement() {
        PositionHash rookMoved = ZobristPositionHasher.hashAfterMoves(
                List.of("h2h3", "a7a6", "h1h2", "a6a5", "h2h1", "a5a4"),
                6);
        PositionHash rookUntouched = ZobristPositionHasher.hashAfterMoves(
                List.of("h2h3", "a7a6", "g1f3", "a6a5", "f3g1", "a5a4"),
                6);

        assertNotEquals(rookMoved, rookUntouched);
    }

    /**
     * Verifies deterministic results across independent cursors.
     */
    @Test
    void hashingIsDeterministic() {
        List<String> moves = List.of("e2e4", "c7c5", "g1f3", "d7d6");
        assertEquals(
                ZobristPositionHasher.hashAfterMoves(moves, moves.size()),
                ZobristPositionHasher.hashAfterMoves(moves, moves.size()));
    }
}
