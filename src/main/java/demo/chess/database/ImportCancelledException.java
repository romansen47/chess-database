package demo.chess.database;

import java.io.IOException;

/**
 * Signals a user-requested cancellation of a running PGN database import.
 */
public class ImportCancelledException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message cancellation message
     */
    public ImportCancelledException(String message) {
        super(message);
    }
}
