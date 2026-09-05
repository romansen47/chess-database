package demo.chess.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class MoveCodecTest {

    /**
     * Verifies that normal and promotion moves survive compact encoding.
     */
    @Test
    void roundTripsNormalAndPromotionMoves() {
        List<String> moves = List.of("e2e4", "e7e8q", "a2a1n", "h8h1");
        byte[] encoded = MoveCodec.encodeMoves(moves);

        assertEquals(moves.size() * 2, encoded.length);
        assertEquals(moves, MoveCodec.decodeMoves(encoded));
    }
}
