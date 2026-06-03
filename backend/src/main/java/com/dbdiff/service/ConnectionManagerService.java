package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConnectionManagerService {
    
    // Simple cache to avoid recreating pools for the same connection
    private final Map<String, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    public DataSource getDataSource(ConnectionDetails details) {
        String cacheKey = details.getJdbcUrl() + "|" + details.getUsername();
        return dataSourceCache.computeIfAbsent(cacheKey, key -> createDataSource(details));
    }

    private DataSource createDataSource(ConnectionDetails details) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(details.getJdbcUrl());
        config.setUsername(details.getUsername());
        config.setPassword(details.getPassword());
        
        if (details.getSchema() != null && !details.getSchema().trim().isEmpty()) {
            config.setSchema(details.getSchema());
        }
        
        // Auto-detect driver, set explicitly for reliability
        switch (details.getType().toLowerCase()) {
            case "postgresql":
                config.setDriverClassName("org.postgresql.Driver");
                // PostgreSQL performance tuning
                config.addDataSourceProperty("prepareThreshold", "3");
                config.addDataSourceProperty("preparedStatementCacheQueries", "256");
                config.addDataSourceProperty("preparedStatementCacheSizeMiB", "5");
                config.addDataSourceProperty("defaultRowFetchSize", "1000");
                config.addDataSourceProperty("reWriteBatchedInserts", "true");
                break;
            case "mysql":
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                config.addDataSourceProperty("useServerPrepStmts", "true");
                config.addDataSourceProperty("defaultFetchSize", "1000");
                break;
            case "mariadb":
                config.setDriverClassName("org.mariadb.jdbc.Driver");
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("defaultFetchSize", "1000");
                break;
            case "sqlserver":
                config.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                break;
        }

        // Connection pool tuning for fast execution
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000); // 10s connect timeout
        config.setIdleTimeout(300000);      // 5min idle timeout
        config.setMaxLifetime(600000);      // 10min max lifetime
        config.setLeakDetectionThreshold(30000); // 30s leak detection
        config.setAutoCommit(true);
        
        return new HikariDataSource(config);
    }
    
    public boolean testConnection(ConnectionDetails details) {
        try {
            DataSource ds = getDataSource(details);
            try (java.sql.Connection conn = ds.getConnection()) {
                return conn.isValid(5);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
