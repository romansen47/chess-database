package demo.chess.database;

import java.util.List;

/** One exact opening move-order prefix used by database game search. */
public record OpeningLineSearch(
        int startingPositionId,
        List<String> moves) {

    public OpeningLineSearch {
        moves = moves == null ? List.of() : List.copyOf(moves);
        if (moves.isEmpty()) {
            throw new IllegalArgumentException("Opening line search requires at least one move");
        }
    }
}
