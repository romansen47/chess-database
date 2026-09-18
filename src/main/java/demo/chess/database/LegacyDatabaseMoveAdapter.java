package demo.chess.database;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import demo.chess.definitions.ChessStartingPosition;
import demo.chess.definitions.engines.impl.NoMoveFoundException;
import demo.chess.definitions.moves.Move;
import demo.chess.game.LegalMoveResolver;
import demo.chess.game.impl.Simulation;
import demo.chess.notation.UciMoveCodec;

/**
 * Compatibility boundary for move bytes written by database move codec v1.
 *
 * <p>Before the application adopted Chess960 UCI semantics for all 960
 * positions, position 518 castling was stored as king-to-final-square
 * ({@code e1g1}/{@code e1c1}). Existing databases and position hashes must
 * remain readable. The rest of the application now uses king-to-rook-source
 * UCI for position 518 as well, so translation happens only at this storage
 * boundary.</p>
 */
final class LegacyDatabaseMoveAdapter {

    private LegacyDatabaseMoveAdapter() {
    }

    static List<String> toStorage(
            ChessStartingPosition startingPosition,
            List<String> protocolMoves)
            throws NoMoveFoundException, IOException {
        if (!isLegacyPosition518(startingPosition) || protocolMoves == null || protocolMoves.isEmpty()) {
            return protocolMoves == null ? List.of() : List.copyOf(protocolMoves);
        }

        Simulation simulation = Simulation.createSimulation(startingPosition);
        List<String> result = new ArrayList<>(protocolMoves.size());
        for (String protocolMove : protocolMoves) {
            Move move = LegalMoveResolver.resolveUci(simulation, protocolMove);
            result.add(move.toString());
            simulation.apply(move);
        }
        return List.copyOf(result);
    }

    static List<String> fromStorage(
            ChessStartingPosition startingPosition,
            List<String> storageMoves)
            throws NoMoveFoundException, IOException {
        if (!isLegacyPosition518(startingPosition) || storageMoves == null || storageMoves.isEmpty()) {
            return storageMoves == null ? List.of() : List.copyOf(storageMoves);
        }

        Simulation simulation = Simulation.createSimulation(startingPosition);
        List<String> result = new ArrayList<>(storageMoves.size());
        for (String storageMove : storageMoves) {
            Move move = resolveStorageMove(simulation, storageMove);
            result.add(UciMoveCodec.encode(simulation, move));
            simulation.apply(move);
        }
        return List.copyOf(result);
    }

    static String nextMoveFromStorage(
            ChessStartingPosition startingPosition,
            List<String> protocolPrefix,
            String storageMove)
            throws NoMoveFoundException, IOException {
        if (!isLegacyPosition518(startingPosition)) {
            return storageMove;
        }

        Simulation simulation = Simulation.createSimulation(startingPosition);
        if (protocolPrefix != null) {
            for (String protocolMove : protocolPrefix) {
                Move move = LegalMoveResolver.resolveUci(simulation, protocolMove);
                simulation.apply(move);
            }
        }

        Move move = resolveStorageMove(simulation, storageMove);
        return UciMoveCodec.encode(simulation, move);
    }

    private static Move resolveStorageMove(
            Simulation simulation,
            String storageMove)
            throws NoMoveFoundException, IOException {
        if (storageMove == null || storageMove.isBlank()) {
            throw new NoMoveFoundException("Stored move must not be blank");
        }

        try {
            return LegalMoveResolver.resolveUci(simulation, storageMove);
        } catch (NoMoveFoundException ignored) {
            for (Move candidate : simulation.getPlayer().getValidMoves(simulation)) {
                if (storageMove.equalsIgnoreCase(candidate.toString())) {
                    return candidate;
                }
            }
            throw new NoMoveFoundException(
                    "Could not resolve legacy stored move: " + storageMove);
        }
    }

    private static boolean isLegacyPosition518(ChessStartingPosition startingPosition) {
        return startingPosition != null
                && startingPosition.getId() == ChessStartingPosition.STANDARD_ID;
    }
}
