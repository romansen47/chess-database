package demo.chess.database;

import java.util.List;

/** Immutable result of one position inside a completed game-analysis run. */
public record AnalysisPosition(
        int ply,
        double evaluation,
        int depth,
        Integer mateDistance,
        List<AnalysisLine> lines) {

    public AnalysisPosition {
        ply = Math.max(0, ply);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
