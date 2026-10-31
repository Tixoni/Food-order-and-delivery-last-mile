package ru.mirea.delivery.infra.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.config.AppConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Пул соединений с базой сервиса. Недоступная при запуске база не мешает
 * сервису стартовать: /ready покажет «не готов», запросы получат 500,
 * а после восстановления базы всё заработает само.
 */
public final class Database implements AutoCloseable {
    private final HikariDataSource dataSource;

    public Database(AppConfig.Database config) {
        HikariConfig hikari = new HikariConfig();
        // stringtype=unspecified: строковые параметры сервер приводит сам — к uuid, text, varchar.
        hikari.setJdbcUrl(config.jdbcUrl() + "&stringtype=unspecified");
        hikari.setUsername(config.user());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(10);
        hikari.setConnectionTimeout(5000);
        hikari.setInitializationFailTimeout(-1);
        hikari.setPoolName("delivery");
        dataSource = new HikariDataSource(hikari);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Проверка готовности (/ready): база отвечает на SELECT 1. */
    public Map<String, Object> readiness() {
        long started = System.nanoTime();
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SELECT 1");
            return Maps.of("status", "ok", "latencyMs", (System.nanoTime() - started) / 1_000_000);
        } catch (SQLException error) {
            return Maps.of("status", "fail", "latencyMs", (System.nanoTime() - started) / 1_000_000,
                    "error", error.getMessage());
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
