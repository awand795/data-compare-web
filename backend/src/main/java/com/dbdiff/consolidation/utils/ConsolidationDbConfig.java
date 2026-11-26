package com.dbdiff.consolidation.utils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.json.JSONObject;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class ConsolidationDbConfig {

    private static volatile DataSource dataSource;
    private static final Object LOCK = new Object();

    private static DataSource getOrCreateDataSource() {
        if (dataSource == null) {
            synchronized (LOCK) {
                if (dataSource == null) {
                    dataSource = buildDataSource();
                }
            }
        }
        return dataSource;
    }

    private static DataSource buildDataSource() {
        String url = System.getenv("CONSOLIDATION_DB_URL");
        String user = System.getenv("CONSOLIDATION_DB_USER");
        String password = System.getenv("CONSOLIDATION_DB_PASSWORD");

        if (url == null || url.isBlank()) {
            String propSpringBoot = System.getenv("PROPERTY_SPRING_BOOT");
            if (propSpringBoot != null && !propSpringBoot.isBlank()) {
                try {
                    JSONObject envJson = new JSONObject(propSpringBoot);
                    if (envJson.has("CONFIGURE_DB_CONSOLIDATION")) {
                        JSONObject cfg = envJson.getJSONObject("CONFIGURE_DB_CONSOLIDATION");
                        url = cfg.optString("URL", url);
                        user = cfg.optString("USER", user);
                        password = cfg.optString("PASSWORD", password);
                    }
                } catch (Exception e) {
                    Logger.getLogger(ConsolidationDbConfig.class.getName())
                            .log(Level.WARNING, "Failed to parse PROPERTY_SPRING_BOOT: " + e.getMessage());
                }
            }
        }

        if (url == null || url.isBlank()) {
            url = "jdbc:postgresql://127.0.0.1:8832/data_konsol";
        }
        if (user == null || user.isBlank()) {
            user = "postgres";
        }
        if (password == null || password.isBlank()) {
            password = "postgre!PowerData@202608";
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setInitializationFailTimeout(-1);
        config.setPoolName("DarkoConsolidationPool");
        return new HikariDataSource(config);
    }

    public static Connection getConnection() throws SQLException {
        return getOrCreateDataSource().getConnection();
    }
}
