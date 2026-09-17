package demo.chess.database;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import demo.chess.definitions.ChessStartingPosition;

/** Complete game payload stored by the local database. */
public record StoredGame(
        long id,
        Map<String, String> tags,
        List<String> uciMoves,
        int startingPositionId) {

    public StoredGame(long id, Map<String, String> tags, List<String> uciMoves) {
        this(id, tags, uciMoves, ChessStartingPosition.STANDARD_ID);
    }

    public StoredGame {
        tags = Map.copyOf(new LinkedHashMap<>(tags));
        uciMoves = List.copyOf(uciMoves);
        ChessStartingPosition.of(startingPositionId);
    }
}
