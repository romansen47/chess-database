package demo.chess.database;

/**
 * Aggregated statistics for one move from a position.
 *
 * @param move UCI move
 * @param games number of occurrences
 * @param whiteWins white wins
 * @param draws draws
 * @param blackWins black wins
 */
public record PositionMoveStatistics(
        String move,
        long games,
        long whiteWins,
        long draws,
        long blackWins) {
}
