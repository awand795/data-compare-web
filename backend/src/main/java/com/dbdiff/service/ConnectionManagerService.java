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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ConnectionManagerService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManagerService.class);

    private final int MAX_POOL_CACHE = 3;
    private final ReentrantLock cacheLock = new ReentrantLock();
    private final java.util.concurrent.ConcurrentLinkedQueue<Map.Entry<String, Object>> evictedEntries =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final Map<String, Object> dataSourceCache = new LinkedHashMap<String, Object>(MAX_POOL_CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            if (size() > MAX_POOL_CACHE) {
                evictedEntries.add(eldest);
                return true;
            }
            return false;
        }
    };
    private final Map<String, java.util.concurrent.Semaphore> poolSemaphores = new java.util.concurrent.ConcurrentHashMap<>();

    public java.util.concurrent.Semaphore getSemaphoreForPool(String connId) {
        return poolSemaphores.computeIfAbsent(connId, k -> new java.util.concurrent.Semaphore(1));
    }

    private void drainEvictedEntries() {
        Map.Entry<String, Object> entry;
        while ((entry = evictedEntries.poll()) != null) {
            final String key = entry.getKey();
            final Object dsObj = entry.getValue();
            try {
                if (dsObj instanceof HikariDataSource hds) {
                    hds.close();
                    logger.info("Closed evicted HikariCP pool: {}", hds.getPoolName());
                }
            } catch (Exception e) {
                logger.warn("Failed to close evicted HikariCP pool: {}", e.getMessage());
            }
            if (key != null && key.contains("|")) {
                String connId = key.substring(0, key.indexOf('|'));
                boolean stillInUse;
                cacheLock.lock();
                try {
                    stillInUse = dataSourceCache.keySet().stream()
                        .anyMatch(k -> k.startsWith(connId + "|"));
                } finally {
                    cacheLock.unlock();
                }
                if (!stillInUse) {
                    sshTunnelService.closeTunnel(connId);
                    logger.info("Closed evicted SSH tunnel for: {}", connId);
                }
            }
        }
    }

    public void evictConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) return;
        List<Object> toClose = new java.util.ArrayList<>();
        cacheLock.lock();
        try {
            java.util.List<String> keysToRemove = new java.util.ArrayList<>();
            for (String key : dataSourceCache.keySet()) {
                if (key.startsWith(connectionId + "|")) {
                    keysToRemove.add(key);
                }
            }
            for (String key : keysToRemove) {
                Object ds = dataSourceCache.remove(key);
                if (ds != null) toClose.add(ds);
            }
        } finally {
            cacheLock.unlock();
        }
        // Tutup DataSource DI LUAR lock untuk menghindari deadlock
        for (Object ds : toClose) {
            try {
                if (ds instanceof HikariDataSource hds) {
                    hds.close();
                    logger.info("Closed evicted HikariCP pool from evictConnection: {}", hds.getPoolName());
                }
            } catch (Exception e) {
                logger.warn("Failed to close DataSource during eviction: {}", e.getMessage());
            }
        }
        // Do not remove poolSemaphores here to avoid race conditions with running queries
        sshTunnelService.closeTunnel(connectionId);
    }

    @Autowired
    private SshTunnelService sshTunnelService;

    @Autowired
    private org.springframework.core.task.TaskExecutor taskExecutor;

    public DataSource getDataSource(ConnectionDetails details) {
        String safeUsername = details.getUsername() != null ? details.getUsername() : "";
        String connId = details.getStableIdentifier();
        String cacheKey = connId + "|" + safeUsername;

        // SSH health-check SEBELUM masuk cacheLock untuk hindari deadlock
        // (cacheLock → sshLock bisa deadlock jika ada thread lain pegang sshLock → cacheLock)
        boolean sshTunnelDead = false;
        if (details.isUseSsh()) {
            // Cek apakah ada cache entry dulu (tanpa lock, hanya peek)
            Object peek;
            cacheLock.lock();
            try { peek = dataSourceCache.get(cacheKey); } finally { cacheLock.unlock(); }
            if (peek instanceof DataSource) {
                sshTunnelDead = !sshTunnelService.isTunnelHealthy(connId);
                if (sshTunnelDead) {
                    logger.warn("SSH tunnel mati untuk {}. Akan evict pool lama dan buat ulang.", connId);
                }
            }
        }

        java.util.concurrent.CompletableFuture<DataSource> future = null;
        boolean isCreator = false;
        
        cacheLock.lock();
        try {
            Object existing = dataSourceCache.get(cacheKey);
            if (existing instanceof DataSource existingDs) {
                if (sshTunnelDead) {
                    // Evict pool lama; buat ulang di bawah
                    dataSourceCache.remove(cacheKey);
                    evictedEntries.add(Map.entry(cacheKey, existingDs));
                    future = new java.util.concurrent.CompletableFuture<>();
                    dataSourceCache.put(cacheKey, future);
                    isCreator = true;
                } else {
                    return existingDs;
                }
            } else if (existing instanceof java.util.concurrent.CompletableFuture) {
                future = (java.util.concurrent.CompletableFuture<DataSource>) existing;
            } else {
                future = new java.util.concurrent.CompletableFuture<>();
                dataSourceCache.put(cacheKey, future);
                isCreator = true;
            }
        } finally {
            cacheLock.unlock();
            drainEvictedEntries();
        }

        if (!isCreator) {
            try {
                return future.get();
            } catch (Exception e) {
                throw new RuntimeException("Failed to await data source creation: " + e.getMessage(), e);
            }
        }

        DataSource ds;
        try {
            ds = createDataSource(details, connId);
            future.complete(ds);
            
            cacheLock.lock();
            try {
                dataSourceCache.put(cacheKey, ds);
            } finally {
                cacheLock.unlock();
                drainEvictedEntries();
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
            cacheLock.lock();
            try {
                dataSourceCache.remove(cacheKey);
            } finally {
                cacheLock.unlock();
            }
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String msg = cause.getMessage() != null ? cause.getMessage() : e.getMessage();
            throw new RuntimeException("Failed to create data source: " + msg, e);
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

    private DataSource createDataSource(ConnectionDetails details, String connId) throws Exception {
        HikariConfig config = new HikariConfig();
        
        String effectiveHost = details.getHost();
        int effectivePort = details.getPort();
        
        if (details.isUseSsh()) {
            effectivePort = sshTunnelService.getOrOpenTunnel(details, connId);
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

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        // Frontend sends timeout in seconds, HikariCP expects milliseconds
        int timeoutMs = details.getConnectionTimeout() != null ? details.getConnectionTimeout() * 1000 : 60000;
        if (timeoutMs < 250) timeoutMs = 250;
        config.setConnectionTimeout(timeoutMs);
        config.setIdleTimeout(120000);    // 2 menit — kasih waktu lebih sebelum evict
        config.setMaxLifetime(300000);    // 5 menit
        config.setKeepaliveTime(50000);   // harus < idleTimeout=120000
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
