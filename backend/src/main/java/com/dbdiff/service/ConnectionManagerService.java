package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConnectionManagerService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManagerService.class);

    private final Map<String, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    @Autowired
    private SshTunnelService sshTunnelService;

    public DataSource getDataSource(ConnectionDetails details) {
        return getDataSource(details, details.getDatabase());
    }

    public DataSource getDataSource(ConnectionDetails details, String databaseName) {
        String effectiveDb = databaseName != null && !databaseName.isBlank() ? databaseName : details.getDatabase();
        String cacheKey = (details.getId() != null && !details.getId().isBlank()
            ? details.getId() + "|" + effectiveDb + "|" + details.getUsername()
            : (details.getJdbcUrl().toLowerCase().trim() + "|" + effectiveDb + "|" + details.getUsername().toLowerCase().trim()));
        final String db = effectiveDb;
        return dataSourceCache.computeIfAbsent(cacheKey, key -> {
            try {
                return createDataSource(details, db);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create data source", e);
            }
        });
    }

    private String decodePassword(String encoded) {
        if (encoded == null) return null;
        // Frontend may base64-encode passwords before saving
        try {
            return new String(java.util.Base64.getDecoder().decode(encoded));
        } catch (Exception e) {
            return encoded; // not base64, use as-is
        }
    }

    private DataSource createDataSource(ConnectionDetails details) throws Exception {
        return createDataSource(details, details.getDatabase());
    }

    private DataSource createDataSource(ConnectionDetails details, String databaseName) throws Exception {
        HikariConfig config = new HikariConfig();
        
        String effectiveHost = details.getHost();
        int effectivePort = details.getPort();
        
        if (details.isUseSsh()) {
            effectivePort = sshTunnelService.getOrOpenTunnel(details);
            effectiveHost = "localhost";
        }
        
        config.setJdbcUrl(details.getJdbcUrl(effectiveHost, effectivePort, databaseName));
        config.setUsername(details.getUsername());
        config.setPassword(decodePassword(details.getPassword()));

        if (details.getSchema() != null && !details.getSchema().trim().isEmpty()) {
            config.setSchema(details.getSchema());
        }
        
        if (details.isReadOnly()) {
            config.setReadOnly(true);
        }

        switch (details.getType().toLowerCase()) {
            case "postgresql":
                config.setDriverClassName("org.postgresql.Driver");
                config.setAutoCommit(true);
                config.addDataSourceProperty("prepareThreshold", "3");
                config.addDataSourceProperty("preparedStatementCacheQueries", "256");
                config.addDataSourceProperty("preparedStatementCacheSizeMiB", "5");
                config.addDataSourceProperty("defaultRowFetchSize", details.getFetchSize() != null ? String.valueOf(details.getFetchSize()) : "5000");
                config.addDataSourceProperty("reWriteBatchedInserts", "true");
                
                if (details.getSslMode() != null && !details.getSslMode().isEmpty()) {
                    config.addDataSourceProperty("ssl", "true");
                    config.addDataSourceProperty("sslmode", details.getSslMode());
                    if (details.getSslCaFile() != null) config.addDataSourceProperty("sslrootcert", details.getSslCaFile());
                    if (details.getSslCertFile() != null) config.addDataSourceProperty("sslcert", details.getSslCertFile());
                    if (details.getSslKeyFile() != null) config.addDataSourceProperty("sslkey", details.getSslKeyFile());
                }
                
                if (details.getSocketTimeout() != null) {
                    config.addDataSourceProperty("socketTimeout", String.valueOf(details.getSocketTimeout() / 1000)); // PG uses seconds
                }
                break;

            case "mysql":
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setAutoCommit(true);
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                config.addDataSourceProperty("useServerPrepStmts", "true");
                if (details.getFetchSize() != null) {
                    config.addDataSourceProperty("defaultFetchSize", String.valueOf(details.getFetchSize()));
                } else {
                    config.addDataSourceProperty("defaultFetchSize", "1000");
                }
                
                if (details.getSslMode() != null && !details.getSslMode().isEmpty()) {
                    config.addDataSourceProperty("useSSL", "true");
                    if ("require".equalsIgnoreCase(details.getSslMode())) {
                        config.addDataSourceProperty("requireSSL", "true");
                    }
                    if (details.getSslCaFile() != null) config.addDataSourceProperty("trustCertificateKeyStoreUrl", "file:" + details.getSslCaFile());
                }
                
                if (details.getSocketTimeout() != null) {
                    config.addDataSourceProperty("socketTimeout", String.valueOf(details.getSocketTimeout())); // MySQL uses ms
                }
                break;

            case "mariadb":
                config.setDriverClassName("org.mariadb.jdbc.Driver");
                config.setAutoCommit(true);
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                if (details.getFetchSize() != null) {
                    config.addDataSourceProperty("defaultFetchSize", String.valueOf(details.getFetchSize()));
                } else {
                    config.addDataSourceProperty("defaultFetchSize", "1000");
                }
                break;

            case "sqlserver":
                config.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                config.setAutoCommit(true);
                if (details.getSslMode() != null && !details.getSslMode().isEmpty()) {
                    config.addDataSourceProperty("encrypt", "true");
                    config.addDataSourceProperty("trustServerCertificate", "require".equalsIgnoreCase(details.getSslMode()) ? "false" : "true");
                }
                break;

            default:
                config.setAutoCommit(true);
                break;
        }

        if (details.getExtraProps() != null && !details.getExtraProps().trim().isEmpty()) {
            String[] props = details.getExtraProps().split(",");
            for (String prop : props) {
                String[] kv = prop.split("=");
                if (kv.length == 2) {
                    config.addDataSourceProperty(kv[0].trim(), kv[1].trim());
                }
            }
        }

        config.setMaximumPoolSize(20);
        config.setMinimumIdle(4);
        // Frontend sends timeout in seconds, HikariCP expects milliseconds
        int timeoutMs = details.getConnectionTimeout() != null ? details.getConnectionTimeout() * 1000 : 30000;
        if (timeoutMs < 250) timeoutMs = 250;
        config.setConnectionTimeout(timeoutMs);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(600000);

        HikariDataSource ds = new HikariDataSource(config);
        
        // Warm up: buka koneksi awal ke remote DB secara asinkron di background
        new Thread(() -> {
            try (java.sql.Connection conn = ds.getConnection()) {
                // just to warm up the connection
            } catch (Exception e) {
                logger.error("Failed to warm up connection pool: {}", e.getMessage());
            }
        }).start();
        
        return ds;
    }

    public Map<String, Object> testConnection(ConnectionDetails details) {
        try {
            DataSource ds = getDataSource(details);
            try (java.sql.Connection conn = ds.getConnection()) {
                boolean valid = conn.isValid(5);
                if (valid) {
                    return Map.of("success", true, "message", "Connection successful");
                } else {
                    return Map.of("success", false, "message", "Connection timeout — database did not respond within 5 seconds");
                }
            }
        } catch (Exception e) {
            logger.error("Connection test failed: {}", e.getMessage(), e);
            // Unwrap to get the real root cause message
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String msg = cause.getMessage() != null ? cause.getMessage() : e.getMessage();
            if (msg == null) msg = "Unknown connection error";
            // Clean up common verbose messages
            if (msg.contains("\n")) msg = msg.substring(0, msg.indexOf('\n'));
            return Map.of("success", false, "message", msg);
        }
    }
}
