package demo.chess.database;

/**
 * Summarizes a completed PGN import.
 *
 * @param importedGames successfully imported games
 * @param skippedGames skipped invalid or unsupported games
 * @param totalPlies number of imported half-moves
 * @param elapsedMillis elapsed import time in milliseconds
 */
public record ImportResult(
        long importedGames,
        long skippedGames,
        long totalPlies,
        long elapsedMillis) {
}
