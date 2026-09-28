package demo.chess.database;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Reads one PGN game at a time without loading the complete source into memory.
 */
final class PgnStreamReader implements AutoCloseable {

    private final BufferedReader reader;
    private String pendingLine;
    private boolean inBraceComment;
    private int variationDepth;

    /**
     * Creates a streaming PGN reader.
     *
     * @param reader source reader
     */
    PgnStreamReader(Reader reader) {
        this.reader = reader instanceof BufferedReader bufferedReader
                ? bufferedReader
                : new BufferedReader(reader);
    }

    /**
     * Reads the next PGN game.
     *
     * @return one PGN document or null at end of stream
     */
    String nextGame() throws IOException {
        StringBuilder game = new StringBuilder();
        boolean seenMoveText = false;

        String line = takeLine();
        while (line != null) {
            String trimmed = line.trim();
            boolean tagLine = !inBraceComment && variationDepth == 0
                    && trimmed.matches("\\[[A-Za-z0-9_]+\\s+\".*\"\\]");

            if (game.length() == 0 && trimmed.isEmpty()) {
                line = reader.readLine();
                continue;
            }

            if (tagLine && seenMoveText) {
                pendingLine = line;
                break;
            }

            game.append(line).append('\n');
            if (!trimmed.isEmpty() && !tagLine) {
                seenMoveText = true;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (inBraceComment) {
                        if (c == '}') inBraceComment = false;
                    } else if (c == ';') {
                        break;
                    } else if (c == '{') {
                        inBraceComment = true;
                    } else if (c == '(') {
                        variationDepth++;
                    } else if (c == ')' && variationDepth > 0) {
                        variationDepth--;
                    }
                }
            }

            line = reader.readLine();
        }

        return game.toString().isBlank() ? null : game.toString();
    }

    /**
     * Returns the pending line from the next game or reads a new line.
     *
     * @return next source line
     */
    private String takeLine() throws IOException {
        if (pendingLine != null) {
            String result = pendingLine;
            pendingLine = null;
            return result;
        }
        return reader.readLine();
    }

    /**
     * Closes the underlying reader.
     */
    @Override
    public void close() throws IOException {
        reader.close();
    }
}
