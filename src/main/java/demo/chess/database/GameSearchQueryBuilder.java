package demo.chess.database;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import demo.chess.definitions.ChessStartingPosition;
import demo.chess.definitions.engines.impl.NoMoveFoundException;

/** Builds the SQL and bind values for database game searches. */
final class GameSearchQueryBuilder {

    private GameSearchQueryBuilder() {
    }

    static Query build(GameSearch criteria) throws SQLException {
        StringBuilder sql = new StringBuilder(
                """
                SELECT g.id, g.game_date, COALESCE(w.name, '?'), COALESCE(b.name, '?'),
                       g.white_elo, g.black_elo, g.result, g.event, g.eco, g.ply_count
                FROM game g
                LEFT JOIN player w ON w.id = g.white_player_id
                LEFT JOIN player b ON b.id = g.black_player_id
                WHERE g.import_id IS NULL
                """);
        List<Object> parameters = new ArrayList<>();

        appendPlayerFilter(sql, parameters, "w.normalized_name", criteria.white());
        appendPlayerFilter(sql, parameters, "b.normalized_name", criteria.black());
        appendAnyColorPlayerFilter(sql, parameters, criteria.player());
        appendAnyColorPlayerFilter(sql, parameters, criteria.player2());

        if (criteria.fromYear() != null) {
            sql.append(" AND g.game_year >= ?");
            parameters.add(criteria.fromYear());
        }
        if (criteria.toYear() != null) {
            sql.append(" AND g.game_year <= ?");
            parameters.add(criteria.toYear());
        }
        if (criteria.result() != null) {
            sql.append(" AND g.result = ?");
            parameters.add(criteria.result());
        }
        if (criteria.minElo() != null) {
            sql.append(" AND g.white_elo >= ? AND g.black_elo >= ?");
            parameters.add(criteria.minElo());
            parameters.add(criteria.minElo());
        }
        if (criteria.eco() != null) {
            sql.append(" AND UPPER(g.eco) LIKE UPPER(?) ESCAPE '\\'");
            parameters.add(prefixPattern(criteria.eco()));
        }
        appendOpeningFilter(sql, parameters, criteria);

        sql.append(" ORDER BY g.game_year DESC, g.game_date DESC, g.id DESC LIMIT ?");
        parameters.add(criteria.limit());
        return new Query(sql.toString(), List.copyOf(parameters));
    }

    private static void appendOpeningFilter(
            StringBuilder sql,
            List<Object> parameters,
            GameSearch criteria) throws SQLException {
        if (criteria.openingMoves().isEmpty()) {
            return;
        }

        try {
            ChessStartingPosition startingPosition =
                    ChessStartingPosition.of(criteria.openingStartingPositionId());
            List<String> storageMoves = LegacyDatabaseMoveAdapter.toStorage(
                    startingPosition,
                    criteria.openingMoves());
            byte[] prefix = MoveCodec.encodeMoves(storageMoves);

            sql.append(
                    " AND g.starting_position_id = ?"
                            + " AND g.ply_count >= ?"
                            + " AND substr(g.moves, 1, ?) = ?");
            parameters.add(startingPosition.getId());
            parameters.add(storageMoves.size());
            parameters.add(prefix.length);
            parameters.add(prefix);
        } catch (NoMoveFoundException | IOException | IllegalArgumentException e) {
            throw new SQLException("Invalid opening search line", e);
        }
    }

    private static void appendAnyColorPlayerFilter(
            StringBuilder sql,
            List<Object> parameters,
            String value) {
        if (value == null) {
            return;
        }
        sql.append(
                " AND (w.normalized_name LIKE ? ESCAPE '\\'"
                        + " OR b.normalized_name LIKE ? ESCAPE '\\')");
        String pattern = containsPattern(value);
        parameters.add(pattern);
        parameters.add(pattern);
    }

    private static void appendPlayerFilter(
            StringBuilder sql,
            List<Object> parameters,
            String column,
            String value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" LIKE ? ESCAPE '\\'");
        parameters.add(containsPattern(value));
    }

    private static String containsPattern(String value) {
        return "%" + escapeLike(value.toLowerCase(java.util.Locale.ROOT)) + "%";
    }

    private static String prefixPattern(String value) {
        return escapeLike(value) + "%";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    record Query(String sql, List<Object> parameters) {
    }
}
