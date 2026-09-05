package demo.chess.database;

/**
 * Lightweight representation used for game search results.
 *
 * @param id database identifier
 * @param date PGN date
 * @param white white player
 * @param black black player
 * @param whiteElo white rating
 * @param blackElo black rating
 * @param result PGN result
 * @param event event name
 * @param eco ECO code
 * @param plyCount number of half-moves
 */
public record GameSummary(
        long id,
        String date,
        String white,
        String black,
        Integer whiteElo,
        Integer blackElo,
        String result,
        String event,
        String eco,
        int plyCount) {
}
