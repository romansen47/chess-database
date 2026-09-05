package demo.chess.database;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;

import demo.chess.definitions.engines.impl.NoMoveFoundException;

/**
 * Public API of the local chess database.
 */
public interface ChessDatabase {

    /**
     * Returns database status information.
     *
     * @return current database status
     */
    ChessDatabaseStatus getStatus() throws SQLException, IOException;

    /**
     * Imports one or more PGN games from the supplied stream.
     *
     * @param inputStream PGN stream
     * @return completed import summary
     */
    ImportResult importPgn(InputStream inputStream) throws SQLException, IOException;

    /**
     * Searches stored games.
     *
     * @param search search criteria
     * @return matching games
     */
    List<GameSummary> findGames(GameSearch search) throws SQLException;

    /**
     * Loads one complete stored game.
     *
     * @param id database game identifier
     * @return stored game
     */
    StoredGame getGame(long id) throws SQLException;

    /**
     * Recreates a PGN document for a stored game.
     *
     * @param id database game identifier
     * @return PGN text
     */
    String getGameAsPgn(long id) throws SQLException, IOException, NoMoveFoundException;

    /**
     * Returns move statistics for the position reached after the requested ply.
     *
     * @param uciMoves game moves from the initial position
     * @param ply number of moves to apply before querying
     * @return aggregated position statistics
     */
    PositionStatistics findPosition(List<String> uciMoves, int ply) throws SQLException;
}
