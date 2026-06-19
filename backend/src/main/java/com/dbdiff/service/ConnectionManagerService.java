package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ConnectionManagerService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManagerService.class);

    private final int MAX_POOL_CACHE = 3;
    private final ReentrantLock cacheLock = new ReentrantLock();
    private final Map<String, DataSource> dataSourceCache = new LinkedHashMap<String, DataSource>(MAX_POOL_CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DataSource> eldest) {
          if (size() > MAX_POOL_CACHE) {
            closeQuietly(eldest.getKey(), eldest.getValue());
            return true;
          }
          return false;
        }
    };

    private void closeQuietly(String key, DataSource ds) {
      try {
        if (ds instanceof com.zaxxer.hikari.HikariDataSource hds) {
          hds.close();
          logger.info("Closed evicted HikariCP pool: {}", hds.getPoolName());
        }
        if (key != null && key.contains("|")) {
          String connId = key.substring(0, key.indexOf('|'));
          if (!connId.startsWith("jdbc:")) {
            sshTunnelService.closeTunnel(connId);
            logger.info("Closed evicted SSH tunnel for connection: {}", connId);
          }
        }
      } catch (Exception e) {
        logger.warn("Failed to close evicted DataSource: {}", e.getMessage());
      }
    }

    public void evictConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) return;
        cacheLock.lock();
        try {
            java.util.List<String> keysToRemove = new java.util.ArrayList<>();
            for (String key : dataSourceCache.keySet()) {
                if (key.startsWith(connectionId + "|")) {
                    keysToRemove.add(key);
                }
            }
            for (String key : keysToRemove) {
                DataSource ds = dataSourceCache.remove(key);
                closeQuietly(key, ds);
            }
        } finally {
            cacheLock.unlock();
        }
        sshTunnelService.closeTunnel(connectionId);
    }

    @Autowired
    private SshTunnelService sshTunnelService;

    @Autowired
    private org.springframework.core.task.TaskExecutor taskExecutor;

    public DataSource getDataSource(ConnectionDetails details) {
        String safeUsername = details.getUsername() != null ? details.getUsername() : "";
        String cacheKey = details.getId() != null && !details.getId().isBlank()
            ? details.getId() + "|" + safeUsername
            : (details.getJdbcUrl() != null ? details.getJdbcUrl().toLowerCase().trim() : "") + "|" + safeUsername.toLowerCase().trim();
        DataSource ds;
        cacheLock.lock();
        try {
            DataSource existing = dataSourceCache.get(cacheKey);
            if (existing != null) return existing;
            try {
                ds = createDataSource(details);
                dataSourceCache.put(cacheKey, ds);
            } catch (Exception e) {
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                String msg = cause.getMessage() != null ? cause.getMessage() : e.getMessage();
                throw new RuntimeException("Failed to create data source: " + msg, e);
            }
        } finally {
            cacheLock.unlock();
        }

        // Warm up: buka koneksi awal — synchronous untuk SSH agar pool langsung siap pakai
        // Dilakukan DI LUAR lock agar tidak memblokir thread lain
        if (details.isUseSsh() && ds instanceof HikariDataSource hds) {
            try (java.sql.Connection conn = hds.getConnection()) {
                logger.info("Warmup connection successful for SSH pool: {}", hds.getPoolName());
            } catch (Exception e) {
                logger.warn("Warmup connection failed for SSH pool: {}", e.getMessage());
            }
        } else {
            taskExecutor.execute(() -> {
                try (java.sql.Connection conn = ds.getConnection()) {
                    logger.info("Warmup connection successful for pool");
                } catch (Exception e) {
                    logger.warn("Warmup connection failed: {}", e.getMessage());
                }
            });
        }

        return ds;
    }

    private DataSource createDataSource(ConnectionDetails details) throws Exception {
        HikariConfig config = new HikariConfig();
        
        String effectiveHost = details.getHost();
        int effectivePort = details.getPort();
        
        if (details.isUseSsh()) {
            effectivePort = sshTunnelService.getOrOpenTunnel(details);
            effectiveHost = "127.0.0.1";
        }
        
        config.setJdbcUrl(details.getJdbcUrl(effectiveHost, effectivePort));
        config.setUsername(details.getUsername());
        config.setPassword(details.getPassword());

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
                config.addDataSourceProperty("socketReceiveBufferSize", "1048576"); // 1MB buffer
                
                if (details.getSslMode() != null && !details.getSslMode().isEmpty() && !"disable".equalsIgnoreCase(details.getSslMode())) {
                    config.addDataSourceProperty("ssl", "true");
                    config.addDataSourceProperty("sslmode", details.getSslMode());
                    if (details.getSslCaFile() != null) config.addDataSourceProperty("sslrootcert", details.getSslCaFile());
                    if (details.getSslCertFile() != null) config.addDataSourceProperty("sslcert", details.getSslCertFile());
                    if (details.getSslKeyFile() != null) config.addDataSourceProperty("sslkey", details.getSslKeyFile());
                } else {
                    config.addDataSourceProperty("sslmode", "disable");
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
                config.addDataSourceProperty("useCursorFetch", "true");
                if (details.getFetchSize() != null) {
                    config.addDataSourceProperty("defaultFetchSize", String.valueOf(details.getFetchSize()));
                } else {
                    config.addDataSourceProperty("defaultFetchSize", "1000");
                }
                config.addDataSourceProperty("socketRcvBufSize", "1048576"); // 1MB buffer
                
                // OPTIMIZATION: Boost MySQL sort and read buffers to keep 1M+ rows in memory
                config.setConnectionInitSql("SET SESSION sort_buffer_size = 268435456; SET SESSION read_rnd_buffer_size = 268435456; SET SESSION join_buffer_size = 67108864; SET SESSION max_sort_length = 1024;");
                
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
                config.addDataSourceProperty("useServerPrepStmts", "true");
                config.addDataSourceProperty("useCursorFetch", "true");
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

        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        // Frontend sends timeout in seconds, HikariCP expects milliseconds
        int timeoutMs = details.getConnectionTimeout() != null ? details.getConnectionTimeout() * 1000 : 60000;
        if (timeoutMs < 250) timeoutMs = 250;
        config.setConnectionTimeout(timeoutMs);
        config.setIdleTimeout(120000);    // 2 menit — kasih waktu lebih sebelum evict
        config.setMaxLifetime(300000);    // 5 menit
        config.setKeepaliveTime(60000);   // 1 menit — HARUS < idleTimeout agar efektif
        config.setLeakDetectionThreshold(120000);

        HikariDataSource ds = new HikariDataSource(config);
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
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String msg = cause.getMessage() != null ? cause.getMessage() : e.getMessage();
            if (msg == null) msg = "Unknown connection error";
            if (msg.contains("\n")) msg = msg.substring(0, msg.indexOf('\n'));
            return Map.of("success", false, "message", msg);
        }
    }
}
