package demo.chess.database;

import java.util.List;

/**
 * Search criteria for locally stored games.
 *
 * @param white white player name fragment
 * @param black black player name fragment
 * @param player first color-independent player name fragment
 * @param player2 second color-independent player name fragment
 * @param fromYear minimum year
 * @param toYear maximum year
 * @param result exact PGN result or null
 * @param minElo minimum rating required for both players
 * @param eco ECO code prefix or null
 * @param openingLines exact move-order prefixes; matches any one of them
 * @param limit maximum number of results
 */
public record GameSearch(
        String white,
        String black,
        String player,
        String player2,
        Integer fromYear,
        Integer toYear,
        String result,
        Integer minElo,
        String eco,
        List<OpeningLineSearch> openingLines,
        int limit) {

    /** Backward-compatible constructor without ECO or opening criteria. */
    public GameSearch(
            String white,
            String black,
            String player,
            String player2,
            Integer fromYear,
            Integer toYear,
            String result,
            Integer minElo,
            int limit) {
        this(
                white, black, player, player2,
                fromYear, toYear, result, minElo,
                null, List.of(), limit);
    }

    /** Backward-compatible constructor without a second color-independent player. */
    public GameSearch(
            String white,
            String black,
            String player,
            Integer fromYear,
            Integer toYear,
            String result,
            Integer minElo,
            int limit) {
        this(white, black, player, null, fromYear, toYear, result, minElo, limit);
    }

    /** Normalizes nullable strings, opening criteria and result limits. */
    public GameSearch {
        white = normalize(white);
        black = normalize(black);
        player = normalize(player);
        player2 = normalize(player2);
        result = normalize(result);
        eco = normalize(eco);
        if ("any".equalsIgnoreCase(result)) {
            result = null;
        }
        openingLines = openingLines == null ? List.of() : List.copyOf(openingLines);
        limit = Math.max(1, Math.min(limit <= 0 ? 200 : limit, 500));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
