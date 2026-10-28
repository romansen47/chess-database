package demo.chess.database;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared SQLite binding and persisted-value codecs. */
final class SqliteDatabaseSupport {
    private static final int PLAYER_CACHE_SIZE = 10_000;

    private SqliteDatabaseSupport() { }

    static Connection openConnection(Path databasePath) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    static String normalizePlayerName(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String normalizeLookupPlayer(String value) {
        String normalized = normalizeTag(value);
        return normalized == null || "?".equals(normalized) ? "" : normalizePlayerName(normalized);
    }

    static Long findOrCreatePlayer(
            PreparedStatement insertPlayer,
            PreparedStatement selectPlayer,
            Map<String, Long> playerCache,
            String rawName) throws SQLException {
        String displayName = normalizeTag(rawName);
        if (displayName == null || "?".equals(displayName)) return null;
        String normalized = normalizePlayerName(displayName);
        Long cached = playerCache.get(normalized);
        if (cached != null) return cached;
        insertPlayer.setString(1, displayName);
        insertPlayer.setString(2, normalized);
        insertPlayer.executeUpdate();
        selectPlayer.setString(1, normalized);
        try (ResultSet resultSet = selectPlayer.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Could not resolve player after insert: " + displayName);
            }
            long id = resultSet.getLong(1);
            playerCache.put(normalized, id);
            return id;
        }
    }

    static Map<String, Long> createPlayerCache() {
        return new LinkedHashMap<>(PLAYER_CACHE_SIZE + 1, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > PLAYER_CACHE_SIZE;
            }
        };
    }

    static String normalizeTag(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String normalizeResult(String value) {
        return "1-0".equals(value) || "0-1".equals(value) || "1/2-1/2".equals(value)
                ? value
                : "*";
    }

    static Integer parseInteger(String value) {
        if (value == null || value.isBlank() || "?".equals(value.trim())) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Integer parseYear(String date) {
        if (date == null || date.length() < 4) return null;
        String year = date.substring(0, 4);
        return year.matches("\\d{4}") ? Integer.valueOf(year) : null;
    }

    static void bindNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    static void bindNullableInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER);
        else statement.setInt(index, value);
    }

    static void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value instanceof Integer integer) statement.setInt(index + 1, integer);
            else if (value instanceof Long longValue) statement.setLong(index + 1, longValue);
            else if (value instanceof byte[] bytes) statement.setBytes(index + 1, bytes);
            else statement.setString(index + 1, String.valueOf(value));
        }
    }

    static Integer nullableInteger(ResultSet resultSet, int column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    static String encodeTags(Map<String, String> tags) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (result.length() > 0) result.append('\n');
            result.append(encoder.encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)))
                    .append(':')
                    .append(encoder.encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        return result.toString();
    }

    static Map<String, String> decodeTags(String encoded) {
        Map<String, String> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return result;
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String line : encoded.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            result.put(
                    new String(decoder.decode(line.substring(0, separator)), StandardCharsets.UTF_8),
                    new String(decoder.decode(line.substring(separator + 1)), StandardCharsets.UTF_8));
        }
        return result;
    }
}
