package demo.chess.database;

import java.util.List;

/**
 * Aggregated database information for a position.
 *
 * @param hash position hash
 * @param moves available continuations ordered by frequency
 */
public record PositionStatistics(PositionHash hash, List<PositionMoveStatistics> moves) {

    /**
     * Creates an immutable defensive copy of move statistics.
     */
    public PositionStatistics {
        moves = List.copyOf(moves);
    }
}
