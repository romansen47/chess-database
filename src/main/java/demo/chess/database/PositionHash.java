package demo.chess.database;

/**
 * Stable 128-bit key for a chess position.
 *
 * @param high high 64 bits
 * @param low low 64 bits
 */
public record PositionHash(long high, long low) {
}
