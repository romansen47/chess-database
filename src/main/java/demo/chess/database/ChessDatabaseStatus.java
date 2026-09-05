package demo.chess.database;

/**
 * Describes the currently configured local chess database.
 *
 * @param path database file path
 * @param name display name
 * @param schemaVersion schema version
 * @param gameCount number of stored games
 * @param sizeBytes current file size
 */
public record ChessDatabaseStatus(
        String path,
        String name,
        int schemaVersion,
        long gameCount,
        long sizeBytes) {
}
