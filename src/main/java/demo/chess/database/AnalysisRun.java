package demo.chess.database;

import java.util.List;

/** Immutable completed analysis run and the configuration that produced it. */
public record AnalysisRun(
        long id,
        String gameKey,
        String engineName,
        String engineProfileId,
        String profileName,
        int requestedDepth,
        int moveTimeSeconds,
        int multiPv,
        String optionsSnapshot,
        String createdAt,
        boolean active,
        List<AnalysisPosition> positions) {

    public AnalysisRun {
        if (gameKey == null || gameKey.isBlank()) {
            throw new IllegalArgumentException("gameKey must not be blank");
        }
        engineName = engineName == null ? "" : engineName;
        engineProfileId = engineProfileId == null ? "" : engineProfileId;
        profileName = profileName == null || profileName.isBlank()
                ? engineProfileId : profileName;
        optionsSnapshot = optionsSnapshot == null ? "" : optionsSnapshot;
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
