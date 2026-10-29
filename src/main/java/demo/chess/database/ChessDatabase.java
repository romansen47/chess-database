package demo.chess.database;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import demo.chess.definitions.ChessStartingPosition;
import demo.chess.definitions.engines.impl.NoMoveFoundException;

/** Public API of the local chess database. */
public interface ChessDatabase {

    /**
     * Returns the current database status and storage metadata.
     *
     * @return database status
     * @throws SQLException when the database cannot be queried
     * @throws IOException when the database file metadata cannot be read
     */
    ChessDatabaseStatus getStatus() throws SQLException, IOException;

    /**
     * Imports a PGN stream without progress reporting or cancellation.
     *
     * @param inputStream PGN source
     * @return import statistics
     * @throws SQLException when database persistence fails
     * @throws IOException when the PGN stream cannot be read
     */
    default ImportResult importPgn(InputStream inputStream) throws SQLException, IOException {
        return importPgn(
                UUID.randomUUID().toString(),
                inputStream,
                -1L,
                progress -> {
                },
                () -> false);
    }

    /**
     * Imports a PGN stream with progress reporting and cooperative cancellation.
     *
     * @param importId unique id used for staging and cleanup
     * @param inputStream PGN source
     * @param totalBytes total source size, or a negative value when unknown
     * @param progressConsumer progress callback
     * @param cancellationRequested cooperative cancellation signal
     * @return import statistics
     * @throws SQLException when database persistence fails
     * @throws IOException when the PGN stream cannot be read
     */
    ImportResult importPgn(
            String importId,
            InputStream inputStream,
            long totalBytes,
            Consumer<ImportProgress> progressConsumer,
            BooleanSupplier cancellationRequested) throws SQLException, IOException;

    /**
     * Searches stored games.
     *
     * @param search search criteria
     * @return matching game summaries
     * @throws SQLException when the query fails
     */
    List<GameSummary> findGames(GameSearch search) throws SQLException;

    /**
     * Loads one stored game.
     *
     * @param id database game id
     * @return stored game
     * @throws SQLException when the game cannot be read
     */
    StoredGame getGame(long id) throws SQLException;

    /**
     * Deletes one stored game and removes its contribution from all position
     * statistics.
     *
     * @param id database game id
     * @throws SQLException when the game or its statistics cannot be updated
     */
    void deleteGame(long id) throws SQLException;

    /**
     * Reconstructs one stored game as PGN.
     *
     * @param id database game id
     * @return PGN document
     * @throws SQLException when the game cannot be read
     * @throws IOException when replay serialization fails
     * @throws NoMoveFoundException when a stored move cannot be replayed
     */
    String getGameAsPgn(long id) throws SQLException, IOException, NoMoveFoundException;

    /**
     * Finds the database id of a game represented by PGN.
     *
     * @param pgn PGN document
     * @return matching database id, or a negative value when absent
     * @throws SQLException when the lookup fails
     * @throws IOException when PGN parsing fails
     * @throws NoMoveFoundException when the PGN move sequence is invalid
     */
    long findGameId(String pgn) throws SQLException, IOException, NoMoveFoundException;

    /**
     * Replaces the stored annotated PGN representation for a game.
     *
     * @param id database game id
     * @param pgn annotated PGN document
     * @throws SQLException when persistence fails
     */
    void saveAnnotatedPgn(long id, String pgn) throws SQLException;

    /**
     * Performs a backward-compatible classical position lookup.
     *
     * @param uciMoves move history
     * @param ply number of half-moves to apply
     * @return position statistics
     * @throws SQLException when the lookup fails
     */
    default PositionStatistics findPosition(List<String> uciMoves, int ply) throws SQLException {
        return findPosition(ChessStartingPosition.STANDARD_ID, uciMoves, ply);
    }

    /**
     * Performs a Chess960-aware opening/position lookup.
     *
     * @param startingPositionId Scharnagl starting-position identifier
     * @param uciMoves move history
     * @param ply number of half-moves to apply
     * @return position statistics
     * @throws SQLException when the lookup fails
     */
    PositionStatistics findPosition(int startingPositionId, List<String> uciMoves, int ply)
            throws SQLException;
}
