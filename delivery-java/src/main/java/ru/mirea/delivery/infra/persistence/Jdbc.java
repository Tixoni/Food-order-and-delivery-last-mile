package ru.mirea.delivery.infra.persistence;

import ru.mirea.delivery.domain.GeoPoint;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Тонкая обёртка над JDBC: подстановка параметров, чтение строк, перевод
 * {@link SQLException} в непроверяемое исключение. Запросы сервиса написаны
 * на SQL — ORM здесь не нужен, а PostGIS-функции в нём неудобны.
 */
public final class Jdbc {
    /** Нарушение уникальности в PostgreSQL. */
    public static final String UNIQUE_VIOLATION = "23505";

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    /** Ошибка базы без обязательной обработки — сценарий её не исправит. */
    public static final class DataAccessException extends RuntimeException {
        private final String sqlState;

        DataAccessException(SQLException cause) {
            super(cause.getMessage(), cause);
            this.sqlState = cause.getSQLState();
        }

        public String sqlState() {
            return sqlState;
        }
    }

    private Jdbc() {
    }

    public static <T> List<T> query(Connection conn, String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(mapper.map(rows));
                }
                return result;
            }
        } catch (SQLException error) {
            throw new DataAccessException(error);
        }
    }

    public static <T> T queryOne(Connection conn, String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(conn, sql, mapper, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(Connection conn, String sql, Object... params) {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException error) {
            throw new DataAccessException(error);
        }
    }

    public static Array array(Connection conn, String type, Object[] values) {
        try {
            return conn.createArrayOf(type, values);
        } catch (SQLException error) {
            throw new DataAccessException(error);
        }
    }

    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            int index = i + 1;
            if (value == null) {
                statement.setNull(index, Types.OTHER);
            } else if (value instanceof Instant moment) {
                statement.setObject(index, OffsetDateTime.ofInstant(moment, ZoneOffset.UTC));
            } else if (value instanceof Array array) {
                statement.setArray(index, array);
            } else {
                statement.setObject(index, value);
            }
        }
    }

    public static String text(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);
        return value == null ? null : value.toString();
    }

    public static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static GeoPoint point(ResultSet row, String latitude, String longitude) throws SQLException {
        Object lat = row.getObject(latitude);
        if (lat == null) {
            return null;
        }
        return new GeoPoint(((Number) lat).doubleValue(), ((Number) row.getObject(longitude)).doubleValue());
    }
}
