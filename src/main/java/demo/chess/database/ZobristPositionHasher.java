package demo.chess.database;

import java.util.List;
import java.util.Locale;

import demo.chess.definitions.ChessStartingPosition;
import demo.chess.definitions.PieceType;

/**
 * Stable incremental 128-bit Zobrist hashing for classical chess and Chess960.
 *
 * <p>Classical position 518 deliberately keeps the historic feature layout, so
 * existing standard-chess position hashes remain byte-for-byte compatible.
 * Chess960 adds deterministic features for the Scharnagl start id and original
 * castling-rook files.</p>
 */
public final class ZobristPositionHasher {

    private static final long HIGH_SEED = 0x4f6c6f63616c4442L;
    private static final long LOW_SEED = 0x43686573735a6f62L;
    private static final long STEP = 0x9E3779B97F4A7C15L;

    private static final int PIECE_FEATURES = 12 * 64;
    private static final int SIDE_FEATURE = PIECE_FEATURES;
    private static final int CASTLING_FEATURE = SIDE_FEATURE + 1;
    private static final int EN_PASSANT_FEATURE = CASTLING_FEATURE + 4;

    // Extension features live strictly after the legacy feature range.
    private static final int CHESS960_CASTLING_FILE_FEATURE = EN_PASSANT_FEATURE + 64;
    private static final int CHESS960_START_FEATURE = CHESS960_CASTLING_FILE_FEATURE + 4 * 8;

    private ZobristPositionHasher() {
    }

    /** Creates a cursor at classical Scharnagl position 518. */
    public static Cursor newCursor() {
        return new Cursor(ChessStartingPosition.STANDARD);
    }

    /** Creates a cursor at one Scharnagl position. */
    public static Cursor newCursor(int startingPositionId) {
        return new Cursor(ChessStartingPosition.of(startingPositionId));
    }

    /** Creates a cursor at one Scharnagl position. */
    public static Cursor newCursor(ChessStartingPosition startingPosition) {
        return new Cursor(startingPosition == null
                ? ChessStartingPosition.STANDARD
                : startingPosition);
    }

    /** Computes a classical position hash after a move prefix. */
    public static PositionHash hashAfterMoves(List<String> moves, int ply) {
        return hashAfterMoves(ChessStartingPosition.STANDARD_ID, moves, ply);
    }

    /** Computes a position hash after a move prefix from one Chess960 start id. */
    public static PositionHash hashAfterMoves(
            int startingPositionId,
            List<String> moves,
            int ply) {
        Cursor cursor = newCursor(startingPositionId);
        int safePly = Math.max(0, Math.min(ply, moves == null ? 0 : moves.size()));
        for (int index = 0; index < safePly; index++) {
            cursor.apply(moves.get(index));
        }
        return cursor.hash();
    }

    /** Mutable incremental position cursor used during imports and queries. */
    public static final class Cursor {

        private final char[] board = new char[64];
        private final ChessStartingPosition startingPosition;

        private boolean whiteToMove = true;
        private Integer whiteKingSideRookSquare;
        private Integer whiteQueenSideRookSquare;
        private Integer blackKingSideRookSquare;
        private Integer blackQueenSideRookSquare;
        private int enPassantSquare = -1;

        private long high;
        private long low;

        private Cursor(ChessStartingPosition startingPosition) {
            this.startingPosition = startingPosition;
            initializeBoard();
            initializeCastlingRights();
            initializeHash();
        }

        public PositionHash hash() {
            return new PositionHash(high, low);
        }

        /**
         * Applies one UCI move. Chess960 castling follows the UCI convention
         * king-source -> original-rook-source.
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

            if (Character.toLowerCase(movingPiece) == 'k'
                    && isCastlingMove(from, to, movingPiece, capturedPiece)) {
                applyCastling(from, to, movingPiece);
                enPassantSquare = -1;
                toggleSideToMove();
                return;
            }

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

            toggleSideToMove();
        }

        private void initializeBoard() {
            PieceType[] backRank = startingPosition.getBackRank();
            for (int file = 0; file < 8; file++) {
                char whitePiece = pieceSymbol(backRank[file], true);
                board[file] = whitePiece;
                board[8 + file] = 'P';
                board[48 + file] = 'p';
                board[56 + file] = Character.toLowerCase(whitePiece);
            }
        }

        private void initializeCastlingRights() {
            int whiteRankOffset = 0;
            int blackRankOffset = 56;
            whiteKingSideRookSquare = whiteRankOffset + startingPosition.getKingSideRookFile() - 1;
            whiteQueenSideRookSquare = whiteRankOffset + startingPosition.getQueenSideRookFile() - 1;
            blackKingSideRookSquare = blackRankOffset + startingPosition.getKingSideRookFile() - 1;
            blackQueenSideRookSquare = blackRankOffset + startingPosition.getQueenSideRookFile() - 1;
        }

        private void initializeHash() {
            for (int square = 0; square < board.length; square++) {
                if (board[square] != 0) {
                    xorPiece(board[square], square);
                }
            }
            xorCastlingRight(0, whiteKingSideRookSquare);
            xorCastlingRight(1, whiteQueenSideRookSquare);
            xorCastlingRight(2, blackKingSideRookSquare);
            xorCastlingRight(3, blackQueenSideRookSquare);
            if (!startingPosition.isStandard()) {
                xorFeature(CHESS960_START_FEATURE + startingPosition.getId());
            }
        }

        private void removeEnPassantKey() {
            if (enPassantSquare >= 0) {
                xorFeature(EN_PASSANT_FEATURE + enPassantSquare);
            }
        }

        private boolean isCastlingMove(int from, int to, char king, char targetPiece) {
            if (startingPosition.isStandard()) {
                return Math.abs((from % 8) - (to % 8)) == 2;
            }
            if (Character.toLowerCase(targetPiece) != 'r'
                    || Character.isUpperCase(targetPiece) != Character.isUpperCase(king)) {
                return false;
            }
            Integer kingSideRook = Character.isUpperCase(king)
                    ? whiteKingSideRookSquare
                    : blackKingSideRookSquare;
            Integer queenSideRook = Character.isUpperCase(king)
                    ? whiteQueenSideRookSquare
                    : blackQueenSideRookSquare;
            return Integer.valueOf(to).equals(kingSideRook)
                    || Integer.valueOf(to).equals(queenSideRook);
        }

        private void applyCastling(int kingFrom, int encodedTarget, char king) {
            boolean white = Character.isUpperCase(king);
            Integer kingSideRook = white ? whiteKingSideRookSquare : blackKingSideRookSquare;
            Integer queenSideRook = white ? whiteQueenSideRookSquare : blackQueenSideRookSquare;

            boolean kingSide;
            int rookFrom;
            if (!startingPosition.isStandard()) {
                if (Integer.valueOf(encodedTarget).equals(kingSideRook)) {
                    kingSide = true;
                    rookFrom = encodedTarget;
                } else if (Integer.valueOf(encodedTarget).equals(queenSideRook)) {
                    kingSide = false;
                    rookFrom = encodedTarget;
                } else {
                    throw new IllegalArgumentException("Castling target is not an active rook square");
                }
            } else {
                kingSide = encodedTarget > kingFrom;
                Integer rookSquare = kingSide ? kingSideRook : queenSideRook;
                if (rookSquare == null) {
                    throw new IllegalArgumentException("Castling right is unavailable");
                }
                rookFrom = rookSquare;
            }

            char rook = board[rookFrom];
            if (Character.toLowerCase(rook) != 'r'
                    || Character.isUpperCase(rook) != white) {
                throw new IllegalArgumentException("Castling rook is missing");
            }

            int rankOffset = white ? 0 : 56;
            int kingTo = rankOffset + (kingSide ? 6 : 2); // g/c
            int rookTo = rankOffset + (kingSide ? 5 : 3); // f/d

            disableAllCastlingRights(white);

            xorPiece(king, kingFrom);
            board[kingFrom] = 0;
            xorPiece(rook, rookFrom);
            board[rookFrom] = 0;

            board[kingTo] = king;
            xorPiece(king, kingTo);
            board[rookTo] = rook;
            xorPiece(rook, rookTo);
        }

        private void updateCastlingRightsForMove(char movingPiece, int from) {
            switch (movingPiece) {
                case 'K' -> disableAllCastlingRights(true);
                case 'k' -> disableAllCastlingRights(false);
                case 'R' -> disableRookRight(true, from);
                case 'r' -> disableRookRight(false, from);
                default -> {
                    // No castling right changes.
                }
            }
        }

        private void updateCastlingRightsForCapture(char capturedPiece, int to) {
            if (capturedPiece == 'R') {
                disableRookRight(true, to);
            } else if (capturedPiece == 'r') {
                disableRookRight(false, to);
            }
        }

        private void disableAllCastlingRights(boolean white) {
            if (white) {
                if (whiteKingSideRookSquare != null) {
                    xorCastlingRight(0, whiteKingSideRookSquare);
                    whiteKingSideRookSquare = null;
                }
                if (whiteQueenSideRookSquare != null) {
                    xorCastlingRight(1, whiteQueenSideRookSquare);
                    whiteQueenSideRookSquare = null;
                }
            } else {
                if (blackKingSideRookSquare != null) {
                    xorCastlingRight(2, blackKingSideRookSquare);
                    blackKingSideRookSquare = null;
                }
                if (blackQueenSideRookSquare != null) {
                    xorCastlingRight(3, blackQueenSideRookSquare);
                    blackQueenSideRookSquare = null;
                }
            }
        }

        private void disableRookRight(boolean white, int square) {
            if (white) {
                if (Integer.valueOf(square).equals(whiteKingSideRookSquare)) {
                    xorCastlingRight(0, whiteKingSideRookSquare);
                    whiteKingSideRookSquare = null;
                }
                if (Integer.valueOf(square).equals(whiteQueenSideRookSquare)) {
                    xorCastlingRight(1, whiteQueenSideRookSquare);
                    whiteQueenSideRookSquare = null;
                }
            } else {
                if (Integer.valueOf(square).equals(blackKingSideRookSquare)) {
                    xorCastlingRight(2, blackKingSideRookSquare);
                    blackKingSideRookSquare = null;
                }
                if (Integer.valueOf(square).equals(blackQueenSideRookSquare)) {
                    xorCastlingRight(3, blackQueenSideRookSquare);
                    blackQueenSideRookSquare = null;
                }
            }
        }

        /**
         * The first four castling features are the legacy KQkq flags. Chess960
         * additionally includes the original rook file so rights are unambiguous.
         */
        private void xorCastlingRight(int rightIndex, Integer rookSquare) {
            if (rookSquare == null) {
                return;
            }
            xorFeature(CASTLING_FEATURE + rightIndex);
            if (!startingPosition.isStandard()) {
                int rookFile = rookSquare % 8;
                xorFeature(CHESS960_CASTLING_FILE_FEATURE + rightIndex * 8 + rookFile);
            }
        }

        private void toggleSideToMove() {
            whiteToMove = !whiteToMove;
            xorFeature(SIDE_FEATURE);
        }

        private void xorPiece(char piece, int square) {
            int feature = pieceIndex(piece) * 64 + square;
            xorFeature(feature);
        }

        private void xorFeature(int feature) {
            high ^= featureKey(HIGH_SEED, feature);
            low ^= featureKey(LOW_SEED, feature);
        }
    }

    private static char pieceSymbol(PieceType type, boolean white) {
        char value = switch (type) {
            case PAWN -> 'p';
            case KNIGHT -> 'n';
            case BISHOP -> 'b';
            case ROOK -> 'r';
            case QUEEN -> 'q';
            case KING -> 'k';
        };
        return white ? Character.toUpperCase(value) : value;
    }

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

    private static char promotedPiece(char promotion, boolean white) {
        char piece = switch (Character.toLowerCase(promotion)) {
            case 'q', 'r', 'b', 'n' -> Character.toLowerCase(promotion);
            default -> throw new IllegalArgumentException("Invalid promotion piece: " + promotion);
        };
        return white ? Character.toUpperCase(piece) : piece;
    }

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

    private static long featureKey(long seed, int feature) {
        return mix64(seed + STEP * (feature + 1L));
    }

    private static long mix64(long value) {
        long result = value;
        result = (result ^ (result >>> 30)) * 0xBF58476D1CE4E5B9L;
        result = (result ^ (result >>> 27)) * 0x94D049BB133111EBL;
        return result ^ (result >>> 31);
    }
}
