package demo.chess.database;

/**
 * Search criteria for locally stored games.
 *
 * @param white white player name fragment
 * @param black black player name fragment
 * @param player either player name fragment
 * @param fromYear minimum year
 * @param toYear maximum year
 * @param result exact PGN result or null
 * @param minElo minimum rating required for both players
 * @param limit maximum number of results
 */
public record GameSearch(
        String white,
        String black,
        String player,
        Integer fromYear,
        Integer toYear,
        String result,
        Integer minElo,
        int limit) {

    /**
     * Normalizes nullable strings and result limits.
     */
    public GameSearch {
        white = normalize(white);
        black = normalize(black);
        player = normalize(player);
        result = normalize(result);
        if ("any".equalsIgnoreCase(result)) {
            result = null;
        }
        limit = Math.max(1, Math.min(limit <= 0 ? 200 : limit, 500));
    }

    /**
     * Normalizes a text search value.
     *
     * @param value source value
     * @return trimmed value or null
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
