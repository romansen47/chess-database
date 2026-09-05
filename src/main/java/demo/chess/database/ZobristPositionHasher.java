package demo.chess.database;

import java.util.List;
import java.util.Locale;

/**
 * Stable incremental 128-bit Zobrist hashing for standard chess positions.
 *
 * <p>The hash contains piece placement, side to move, castling rights and the
 * en-passant target square. Half-move and full-move counters are deliberately
 * excluded.</p>
 */
public final class ZobristPositionHasher {

    private static final long HIGH_SEED = 0x4f6c6f63616c4442L;
    private static final long LOW_SEED = 0x43686573735a6f62L;
    private static final long STEP = 0x9E3779B97F4A7C15L;

    private static final int PIECE_FEATURES = 12 * 64;
    private static final int SIDE_FEATURE = PIECE_FEATURES;
    private static final int CASTLING_FEATURE = SIDE_FEATURE + 1;
    private static final int EN_PASSANT_FEATURE = CASTLING_FEATURE + 4;

    /**
     * Prevents instantiation.
     */
    private ZobristPositionHasher() {
    }

    /**
     * Creates a cursor at the standard initial position.
     *
     * @return new position cursor
     */
    public static Cursor newCursor() {
        return new Cursor();
    }

    /**
     * Computes the hash after applying a prefix of a UCI move list.
     *
     * @param moves UCI moves
     * @param ply number of moves to apply
     * @return resulting position hash
     */
    public static PositionHash hashAfterMoves(List<String> moves, int ply) {
        Cursor cursor = newCursor();
        int safePly = Math.max(0, Math.min(ply, moves == null ? 0 : moves.size()));
        for (int index = 0; index < safePly; index++) {
            cursor.apply(moves.get(index));
        }
        return cursor.hash();
    }

    /**
     * Mutable incremental position cursor used during imports and queries.
     */
    public static final class Cursor {

        private final char[] board = new char[64];

        private boolean whiteToMove = true;
        private boolean whiteKingSide = true;
        private boolean whiteQueenSide = true;
        private boolean blackKingSide = true;
        private boolean blackQueenSide = true;
        private int enPassantSquare = -1;

        private long high;
        private long low;

        /**
         * Creates a cursor for the standard initial position.
         */
        private Cursor() {
            initializeBoard();
            initializeHash();
        }

        /**
         * Returns the current 128-bit position hash.
         *
         * @return position hash
         */
        public PositionHash hash() {
            return new PositionHash(high, low);
        }

        /**
         * Applies one legal standard-chess UCI move and updates the hash incrementally.
         *
         * @param rawMove UCI move
         */
        public void apply(String rawMove) {
            String move = normalizeMove(rawMove);
            int from = MoveCodec.squareIndex(move.substring(0, 2));
            int to = MoveCodec.squareIndex(move.substring(2, 4));
            char movingPiece = board[from];

            if (movingPiece == 0) {
                throw new IllegalArgumentException("No piece on source square for move " + rawMove);
            }
            if (Character.isUpperCase(movingPiece) != whiteToMove) {
                throw new IllegalArgumentException("Move color does not match side to move: " + rawMove);
            }

            char capturedPiece = board[to];
            removeEnPassantKey();

            updateCastlingRightsForMove(movingPiece, from);
            updateCastlingRightsForCapture(capturedPiece, to);

            xorPiece(movingPiece, from);
            board[from] = 0;

            boolean pawnMove = Character.toLowerCase(movingPiece) == 'p';
            boolean enPassantCapture = pawnMove
                    && capturedPiece == 0
                    && (from % 8) != (to % 8)
                    && to == enPassantSquare;

            if (enPassantCapture) {
                int capturedSquare = whiteToMove ? to - 8 : to + 8;
                char enPassantPawn = board[capturedSquare];
                if (Character.toLowerCase(enPassantPawn) != 'p') {
                    throw new IllegalArgumentException("Invalid en-passant move: " + rawMove);
                }
                xorPiece(enPassantPawn, capturedSquare);
                board[capturedSquare] = 0;
            } else if (capturedPiece != 0) {
                xorPiece(capturedPiece, to);
            }

            if (Character.toLowerCase(movingPiece) == 'k'
                    && Math.abs((from % 8) - (to % 8)) == 2) {
                moveCastlingRook(from, to);
            }

            char placedPiece = movingPiece;
            if (move.length() == 5) {
                placedPiece = promotedPiece(move.charAt(4), Character.isUpperCase(movingPiece));
            }

            board[to] = placedPiece;
            xorPiece(placedPiece, to);

            if (pawnMove && Math.abs(to - from) == 16) {
                enPassantSquare = (from + to) / 2;
                xorFeature(EN_PASSANT_FEATURE + enPassantSquare);
            } else {
                enPassantSquare = -1;
            }

            whiteToMove = !whiteToMove;
            xorFeature(SIDE_FEATURE);
        }

        /**
         * Initializes the standard piece placement.
         */
        private void initializeBoard() {
            String whiteBackRank = "RNBQKBNR";
            String blackBackRank = "rnbqkbnr";
            for (int file = 0; file < 8; file++) {
                board[file] = whiteBackRank.charAt(file);
                board[8 + file] = 'P';
                board[48 + file] = 'p';
                board[56 + file] = blackBackRank.charAt(file);
            }
        }

        /**
         * Computes the initial hash once from the initial position.
         */
        private void initializeHash() {
            for (int square = 0; square < board.length; square++) {
                if (board[square] != 0) {
                    xorPiece(board[square], square);
                }
            }
            xorFeature(CASTLING_FEATURE);
            xorFeature(CASTLING_FEATURE + 1);
            xorFeature(CASTLING_FEATURE + 2);
            xorFeature(CASTLING_FEATURE + 3);
        }

        /**
         * Removes the current en-passant component from the hash.
         */
        private void removeEnPassantKey() {
            if (enPassantSquare >= 0) {
                xorFeature(EN_PASSANT_FEATURE + enPassantSquare);
            }
        }

        /**
         * Moves the rook belonging to a castling king move.
         *
         * @param kingFrom king source square
         * @param kingTo king target square
         */
        private void moveCastlingRook(int kingFrom, int kingTo) {
            int rookFrom;
            int rookTo;
            if (kingTo > kingFrom) {
                rookFrom = kingFrom + 3;
                rookTo = kingFrom + 1;
            } else {
                rookFrom = kingFrom - 4;
                rookTo = kingFrom - 1;
            }

            char rook = board[rookFrom];
            if (Character.toLowerCase(rook) != 'r') {
                throw new IllegalArgumentException("Castling rook is missing");
            }

            xorPiece(rook, rookFrom);
            board[rookFrom] = 0;
            board[rookTo] = rook;
            xorPiece(rook, rookTo);
        }

        /**
         * Removes castling rights caused by the moving piece.
         *
         * @param movingPiece moving piece
         * @param from source square
         */
        private void updateCastlingRightsForMove(char movingPiece, int from) {
            switch (movingPiece) {
                case 'K' -> {
                    disableWhiteKingSide();
                    disableWhiteQueenSide();
                }
                case 'k' -> {
                    disableBlackKingSide();
                    disableBlackQueenSide();
                }
                case 'R' -> {
                    if (from == 0) {
                        disableWhiteQueenSide();
                    } else if (from == 7) {
                        disableWhiteKingSide();
                    }
                }
                case 'r' -> {
                    if (from == 56) {
                        disableBlackQueenSide();
                    } else if (from == 63) {
                        disableBlackKingSide();
                    }
                }
                default -> {
                    // No castling right changes.
                }
            }
        }

        /**
         * Removes castling rights caused by capturing a rook on its initial square.
         *
         * @param capturedPiece captured piece
         * @param to capture square
         */
        private void updateCastlingRightsForCapture(char capturedPiece, int to) {
            if (capturedPiece == 'R') {
                if (to == 0) {
                    disableWhiteQueenSide();
                } else if (to == 7) {
                    disableWhiteKingSide();
                }
            } else if (capturedPiece == 'r') {
                if (to == 56) {
                    disableBlackQueenSide();
                } else if (to == 63) {
                    disableBlackKingSide();
                }
            }
        }

        /**
         * Disables White's king-side castling right.
         */
        private void disableWhiteKingSide() {
            if (whiteKingSide) {
                whiteKingSide = false;
                xorFeature(CASTLING_FEATURE);
            }
        }

        /**
         * Disables White's queen-side castling right.
         */
        private void disableWhiteQueenSide() {
            if (whiteQueenSide) {
                whiteQueenSide = false;
                xorFeature(CASTLING_FEATURE + 1);
            }
        }

        /**
         * Disables Black's king-side castling right.
         */
        private void disableBlackKingSide() {
            if (blackKingSide) {
                blackKingSide = false;
                xorFeature(CASTLING_FEATURE + 2);
            }
        }

        /**
         * Disables Black's queen-side castling right.
         */
        private void disableBlackQueenSide() {
            if (blackQueenSide) {
                blackQueenSide = false;
                xorFeature(CASTLING_FEATURE + 3);
            }
        }

        /**
         * Applies a piece-square key to both 64-bit halves.
         *
         * @param piece piece symbol
         * @param square square index
         */
        private void xorPiece(char piece, int square) {
            int feature = pieceIndex(piece) * 64 + square;
            xorFeature(feature);
        }

        /**
         * Applies one stable feature key to both hash halves.
         *
         * @param feature feature index
         */
        private void xorFeature(int feature) {
            high ^= featureKey(HIGH_SEED, feature);
            low ^= featureKey(LOW_SEED, feature);
        }
    }

    /**
     * Returns the piece index used by the Zobrist table.
     *
     * @param piece piece symbol
     * @return piece index
     */
    private static int pieceIndex(char piece) {
        return switch (piece) {
            case 'P' -> 0;
            case 'N' -> 1;
            case 'B' -> 2;
            case 'R' -> 3;
            case 'Q' -> 4;
            case 'K' -> 5;
            case 'p' -> 6;
            case 'n' -> 7;
            case 'b' -> 8;
            case 'r' -> 9;
            case 'q' -> 10;
            case 'k' -> 11;
            default -> throw new IllegalArgumentException("Unsupported piece: " + piece);
        };
    }

    /**
     * Returns a promoted piece symbol.
     *
     * @param promotion promotion character
     * @param white whether the moving pawn is white
     * @return promoted piece symbol
     */
    private static char promotedPiece(char promotion, boolean white) {
        char piece = switch (Character.toLowerCase(promotion)) {
            case 'q', 'r', 'b', 'n' -> Character.toLowerCase(promotion);
            default -> throw new IllegalArgumentException("Invalid promotion piece: " + promotion);
        };
        return white ? Character.toUpperCase(piece) : piece;
    }

    /**
     * Validates and normalizes a UCI move.
     *
     * @param value source move
     * @return normalized UCI move
     */
    private static String normalizeMove(String value) {
        if (value == null) {
            throw new IllegalArgumentException("UCI move must not be null");
        }
        String result = value.trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            throw new IllegalArgumentException("Invalid UCI move: " + value);
        }
        return result;
    }

    /**
     * Returns one deterministic 64-bit key for a feature.
     *
     * @param seed fixed hash-half seed
     * @param feature feature index
     * @return deterministic key
     */
    private static long featureKey(long seed, int feature) {
        return mix64(seed + STEP * (feature + 1L));
    }

    /**
     * SplitMix64 finalizer used to derive stable pseudo-random constants.
     *
     * @param value input value
     * @return mixed value
     */
    private static long mix64(long value) {
        long result = value;
        result = (result ^ (result >>> 30)) * 0xBF58476D1CE4E5B9L;
        result = (result ^ (result >>> 27)) * 0x94D049BB133111EBL;
        return result ^ (result >>> 31);
    }
}
