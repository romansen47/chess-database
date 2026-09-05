package demo.chess.database;

/**
 * Progress snapshot emitted while a PGN database import is running.
 *
 * @param bytesRead bytes consumed from the PGN stream
 * @param totalBytes total source size, or a negative value when unknown
 * @param processedGames number of PGN games processed so far
 * @param importedGames successfully staged games
 * @param skippedGames skipped or unsupported games
 * @param totalPlies staged half-moves
 * @param elapsedMillis elapsed import time
 */
public record ImportProgress(
        long bytesRead,
        long totalBytes,
        long processedGames,
        long importedGames,
        long skippedGames,
        long totalPlies,
        long elapsedMillis) {
}
