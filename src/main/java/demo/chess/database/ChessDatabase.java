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

    ChessDatabaseStatus getStatus() throws SQLException, IOException;

    default ImportResult importPgn(InputStream inputStream) throws SQLException, IOException {
        return importPgn(
                UUID.randomUUID().toString(),
                inputStream,
                -1L,
                progress -> {
                },
                () -> false);
    }

    ImportResult importPgn(
            String importId,
            InputStream inputStream,
            long totalBytes,
            Consumer<ImportProgress> progressConsumer,
            BooleanSupplier cancellationRequested) throws SQLException, IOException;

    List<GameSummary> findGames(GameSearch search) throws SQLException;

    StoredGame getGame(long id) throws SQLException;

    String getGameAsPgn(long id) throws SQLException, IOException, NoMoveFoundException;

    long findGameId(String pgn) throws SQLException, IOException, NoMoveFoundException;

    void saveAnnotatedPgn(long id, String pgn) throws SQLException;

    /** Backward-compatible classical lookup. */
    default PositionStatistics findPosition(List<String> uciMoves, int ply) throws SQLException {
        return findPosition(ChessStartingPosition.STANDARD_ID, uciMoves, ply);
    }

    /** Chess960-aware opening/position lookup. */
    PositionStatistics findPosition(int startingPositionId, List<String> uciMoves, int ply)
            throws SQLException;
}
