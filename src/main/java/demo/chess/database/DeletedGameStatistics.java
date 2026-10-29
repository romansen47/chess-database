package demo.chess.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import demo.chess.definitions.ChessStartingPosition;

/** Removes one game's contribution from the materialized position totals. */
final class DeletedGameStatistics {

    private DeletedGameStatistics() { }

    static void remove(
            Connection connection,
            ChessStartingPosition startingPosition,
            List<String> moves,
            String result) throws SQLException {
        int whiteWins = "1-0".equals(result) ? 1 : 0;
        int draws = "1/2-1/2".equals(result) ? 1 : 0;
        int blackWins = "0-1".equals(result) ? 1 : 0;
        ZobristPositionHasher.Cursor cursor =
                ZobristPositionHasher.newCursor(startingPosition);

        try (PreparedStatement update = connection.prepareStatement(
                    """
                    UPDATE position_move
                    SET games = games - 1,
                        white_wins = white_wins - ?,
                        draws = draws - ?,
                        black_wins = black_wins - ?
                    WHERE hash_hi = ? AND hash_lo = ? AND move_code = ?
                      AND games >= 1
                      AND white_wins >= ? AND draws >= ? AND black_wins >= ?
                    """);
                PreparedStatement deleteEmpty = connection.prepareStatement(
                    """
                    DELETE FROM position_move
                    WHERE games = 0 AND white_wins = 0 AND draws = 0 AND black_wins = 0
                    """)) {
            for (String move : moves) {
                PositionHash hash = cursor.hash();
                update.setInt(1, whiteWins);
                update.setInt(2, draws);
                update.setInt(3, blackWins);
                update.setLong(4, hash.high());
                update.setLong(5, hash.low());
                update.setInt(6, MoveCodec.encode(move));
                update.setInt(7, whiteWins);
                update.setInt(8, draws);
                update.setInt(9, blackWins);
                if (update.executeUpdate() != 1) {
                    throw new SQLException(
                            "Missing or inconsistent position aggregate while deleting game.");
                }
                cursor.apply(move);
            }
            deleteEmpty.executeUpdate();
        }
    }
}
