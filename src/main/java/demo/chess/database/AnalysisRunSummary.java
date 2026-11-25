package demo.chess.database;

/** Lightweight metadata used to select completed analyses of one game. */
public record AnalysisRunSummary(
        long id,
        String engineName,
        String engineProfileId,
        String profileName,
        int requestedDepth,
        int moveTimeSeconds,
        int multiPv,
        int positionCount,
        int maximumDepth,
        String createdAt,
        boolean active) {
}
