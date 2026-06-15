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

@Service
public class ConnectionManagerService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManagerService.class);

    private final int MAX_POOL_CACHE = 3;
    private final Map<String, DataSource> dataSourceCache = Collections.synchronizedMap(
      new LinkedHashMap<String, DataSource>(MAX_POOL_CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DataSource> eldest) {
          if (size() > MAX_POOL_CACHE) {
            closeQuietly(eldest.getValue());
            return true;
          }
          return false;
        }
      }
    );

    private void closeQuietly(DataSource ds) {
      try {
        if (ds instanceof com.zaxxer.hikari.HikariDataSource hds) {
          hds.close();
          logger.info("Closed evicted HikariCP pool: {}", hds.getPoolName());
        }
      } catch (Exception e) {
        logger.warn("Failed to close evicted DataSource: {}", e.getMessage());
      }
    }

    @Autowired
    private SshTunnelService sshTunnelService;

    @Autowired
    private org.springframework.core.task.TaskExecutor taskExecutor;

    public DataSource getDataSource(ConnectionDetails details) {
        String cacheKey = details.getId() != null && !details.getId().isBlank()
            ? details.getId() + "|" + details.getUsername()
            : (details.getJdbcUrl().toLowerCase().trim() + "|" + details.getUsername().toLowerCase().trim());
        synchronized (dataSourceCache) {
            DataSource existing = dataSourceCache.get(cacheKey);
            if (existing != null) return existing;
            try {
                DataSource ds = createDataSource(details);
                dataSourceCache.put(cacheKey, ds);
                return ds;
            } catch (Exception e) {
                throw new RuntimeException("Failed to create data source", e);
            }
        }
    }

    private DataSource createDataSource(ConnectionDetails details) throws Exception {
        HikariConfig config = new HikariConfig();
        
        String effectiveHost = details.getHost();
        int effectivePort = details.getPort();
        
        if (details.isUseSsh()) {
            effectivePort = sshTunnelService.getOrOpenTunnel(details);
            effectiveHost = "localhost";
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
                
                // OPTIMIZATION: Boost work_mem to 256MB and enable 4 parallel workers 
                // for ultra-fast in-memory sorting of millions of rows.
                config.setConnectionInitSql("SET work_mem = '256MB'; SET max_parallel_workers_per_gather = 4; SET parallel_setup_cost = 0; SET parallel_tuple_cost = 0;");
                
                if (details.getSslMode() != null && !details.getSslMode().isEmpty() && !"disable".equalsIgnoreCase(details.getSslMode())) {
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

        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        // Frontend sends timeout in seconds, HikariCP expects milliseconds
        int timeoutMs = details.getConnectionTimeout() != null ? details.getConnectionTimeout() * 1000 : 30000;
        if (timeoutMs < 250) timeoutMs = 250;
        config.setConnectionTimeout(timeoutMs);
        config.setIdleTimeout(60000);    // 1 menit — lepas koneksi idle lebih cepat
        config.setMaxLifetime(180000);   // 3 menit
        config.setKeepaliveTime(90000);  // 1.5 menit — harus > idleTimeout
        config.setLeakDetectionThreshold(60000);

        HikariDataSource ds = new HikariDataSource(config);
        
        // Warm up: buka koneksi awal ke remote DB secara asinkron di background
        taskExecutor.execute(() -> {
            try (java.sql.Connection conn = ds.getConnection()) {
                logger.info("Warmup connection successful for pool");
            } catch (Exception e) {
                logger.warn("Warmup connection failed: {}", e.getMessage());
            }
        });
        
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
