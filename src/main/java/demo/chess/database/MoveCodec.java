package demo.chess.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compact 16-bit codec for UCI moves.
 */
public final class MoveCodec {

    private static final int FROM_MASK = 0x3f;
    private static final int TO_MASK = 0x3f;

    /**
     * Prevents instantiation.
     */
    private MoveCodec() {
    }

    /**
     * Encodes one UCI move into an unsigned 16-bit value stored in an int.
     *
     * @param uciMove UCI move
     * @return encoded move
     */
    public static int encode(String uciMove) {
        String move = normalizeMove(uciMove);
        int from = squareIndex(move.substring(0, 2));
        int to = squareIndex(move.substring(2, 4));
        int promotion = move.length() == 5 ? promotionCode(move.charAt(4)) : 0;
        return from | (to << 6) | (promotion << 12);
    }

    /**
     * Decodes one encoded move to UCI notation.
     *
     * @param code encoded move
     * @return UCI move
     */
    public static String decode(int code) {
        int from = code & FROM_MASK;
        int to = (code >> 6) & TO_MASK;
        int promotion = (code >> 12) & 0x7;

        StringBuilder result = new StringBuilder(5);
        result.append(squareName(from)).append(squareName(to));
        if (promotion != 0) {
            result.append(promotionChar(promotion));
        }
        return result.toString();
    }

    /**
     * Encodes a complete move list into a compact byte array.
     *
     * @param moves UCI moves
     * @return encoded move bytes
     */
    public static byte[] encodeMoves(List<String> moves) {
        if (moves == null || moves.isEmpty()) {
            return new byte[0];
        }

        byte[] result = new byte[moves.size() * 2];
        for (int index = 0; index < moves.size(); index++) {
            int code = encode(moves.get(index));
            result[index * 2] = (byte) (code & 0xff);
            result[index * 2 + 1] = (byte) ((code >>> 8) & 0xff);
        }
        return result;
    }

    /**
     * Decodes a compact move byte array.
     *
     * @param bytes encoded move bytes
     * @return UCI moves
     */
    public static List<String> decodeMoves(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        if ((bytes.length & 1) != 0) {
            throw new IllegalArgumentException("Encoded move data must contain an even number of bytes");
        }

        List<String> result = new ArrayList<>(bytes.length / 2);
        for (int index = 0; index < bytes.length; index += 2) {
            int code = Byte.toUnsignedInt(bytes[index])
                    | (Byte.toUnsignedInt(bytes[index + 1]) << 8);
            result.add(decode(code));
        }
        return result;
    }

    /**
     * Converts an algebraic square to a zero-based square index.
     *
     * @param square algebraic square
     * @return square index
     */
    static int squareIndex(String square) {
        if (square == null || !square.matches("[a-h][1-8]")) {
            throw new IllegalArgumentException("Invalid square: " + square);
        }
        int file = square.charAt(0) - 'a';
        int rank = square.charAt(1) - '1';
        return rank * 8 + file;
    }

    /**
     * Converts a square index to algebraic notation.
     *
     * @param index square index
     * @return algebraic square
     */
    static String squareName(int index) {
        if (index < 0 || index >= 64) {
            throw new IllegalArgumentException("Invalid square index: " + index);
        }
        char file = (char) ('a' + (index % 8));
        char rank = (char) ('1' + (index / 8));
        return new String(new char[] { file, rank });
    }

    /**
     * Normalizes and validates UCI notation.
     *
     * @param value source move
     * @return normalized move
     */
    private static String normalizeMove(String value) {
        if (value == null) {
            throw new IllegalArgumentException("UCI move must not be null");
        }
        String move = value.trim().toLowerCase(Locale.ROOT);
        if (!move.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            throw new IllegalArgumentException("Invalid UCI move: " + value);
        }
        return move;
    }

    /**
     * Returns a compact promotion code.
     *
     * @param promotion promotion piece
     * @return promotion code
     */
    private static int promotionCode(char promotion) {
        return switch (Character.toLowerCase(promotion)) {
            case 'q' -> 1;
            case 'r' -> 2;
            case 'b' -> 3;
            case 'n' -> 4;
            default -> throw new IllegalArgumentException("Invalid promotion piece: " + promotion);
        };
    }

    /**
     * Returns the promotion character for a compact code.
     *
     * @param code promotion code
     * @return promotion character
     */
    private static char promotionChar(int code) {
        return switch (code) {
            case 1 -> 'q';
            case 2 -> 'r';
            case 3 -> 'b';
            case 4 -> 'n';
            default -> throw new IllegalArgumentException("Invalid promotion code: " + code);
        };
    }
}
