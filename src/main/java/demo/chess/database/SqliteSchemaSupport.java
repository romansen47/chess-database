package demo.chess.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Small, reusable SQLite schema-migration helpers. */
final class SqliteSchemaSupport {

    private SqliteSchemaSupport() {
    }

    static void ensureColumn(
            Connection connection,
            String table,
            String column,
            String definition) throws SQLException {
        boolean found = false;
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }
}
