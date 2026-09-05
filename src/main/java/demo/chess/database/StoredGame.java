package demo.chess.database;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete game payload stored by the local database.
 *
 * @param id database identifier
 * @param tags PGN tags
 * @param uciMoves game moves in UCI notation
 */
public record StoredGame(long id, Map<String, String> tags, List<String> uciMoves) {

    /**
     * Creates an immutable defensive copy of stored game data.
     */
    public StoredGame {
        tags = Map.copyOf(new LinkedHashMap<>(tags));
        uciMoves = List.copyOf(uciMoves);
    }
}
