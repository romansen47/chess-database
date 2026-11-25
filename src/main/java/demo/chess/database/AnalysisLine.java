package demo.chess.database;

import java.util.List;

/** One engine-ranked principal variation stored for an analyzed position. */
public record AnalysisLine(
        int rank,
        double evaluation,
        int depth,
        Integer mateDistance,
        List<String> uciMoves) {

    public AnalysisLine {
        rank = Math.max(1, rank);
        uciMoves = uciMoves == null ? List.of() : List.copyOf(uciMoves);
    }
}
