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

    private final int MAX_POOL_CACHE = 100;
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
        int initialSize = evictedEntries.size();
        int attempts = 0;
        while ((entry = evictedEntries.poll()) != null && attempts < initialSize) {
            attempts++;
            final String key = entry.getKey();
            final Object dsObj = entry.getValue();
            
            String connId = null;
            if (key != null && key.contains("|")) {
                connId = key.substring(0, key.indexOf('|'));
                java.util.concurrent.Semaphore sem = poolSemaphores.get(connId);
                // Cek apakah pool masih digunakan oleh job lain (Semaphore dikunci/antri)
                if (sem != null && (sem.availablePermits() == 0 || sem.hasQueuedThreads())) {
                    logger.debug("Deferred closing of in-use connection pool: {}", connId);
                    evictedEntries.add(entry);
                    continue;
                }
            }

            try {
                if (dsObj instanceof HikariDataSource hds) {
                    hds.close();
                    logger.info("Closed evicted HikariCP pool: {}", hds.getPoolName());
                }
            } catch (Exception e) {
                logger.warn("Failed to close evicted HikariCP pool: {}", e.getMessage());
            }
            if (connId != null) {
                boolean stillInUse;
                cacheLock.lock();
                try {
                    String finalConnId = connId; // for lambda compatibility
                    stillInUse = dataSourceCache.keySet().stream()
                        .anyMatch(k -> k.startsWith(finalConnId + "|"));
                } finally {
                    cacheLock.unlock();
                }
                if (!stillInUse) {
                    sshTunnelService.closeTunnel(connId);
                    logger.info("Closed evicted SSH tunnel for: {}", connId);
                }
                poolSemaphores.remove(connId);
            }
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
                Object ds = dataSourceCache.remove(key);
                if (ds != null) {
                    evictedEntries.add(Map.entry(key, ds));
                }
            }
        } finally {
            cacheLock.unlock();
        }
        drainEvictedEntries();
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

    /** True jika string tidak null dan tidak kosong (setelah trim). */
    private static boolean isSet(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String resolveSslFile(String content, String prefix, String suffix) {
        if (!isSet(content)) return content;
        if (content.contains("BEGIN CERTIFICATE") || content.contains("BEGIN PRIVATE KEY") || content.contains("BEGIN RSA PRIVATE KEY")) {
            try {
                java.io.File tempFile = java.io.File.createTempFile(prefix, suffix);
                tempFile.deleteOnExit();
                java.nio.file.Files.writeString(tempFile.toPath(), content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                return tempFile.getAbsolutePath();
            } catch (Exception e) {
                logger.warn("Failed to write SSL file to temp", e);
                return content;
            }
        }
        return content;
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

        // Jangan lempar exception langsung saat inisialisasi pool kalau database sedang mati/tidur (misal: serverless DB wake up).
        // Biarkan getConnection() nanti yang memblokir (loading) sambil mencoba reconnect.
        config.setInitializationFailTimeout(-1L);

        // Frontend sends timeout in seconds, HikariCP expects milliseconds.
        // Dihitung di sini agar bisa dipakai juga sebagai driver-level connect/login timeout per tipe DB.
        int timeoutMs = details.getConnectionTimeout() != null ? details.getConnectionTimeout() * 1000 : 60000;
        if (timeoutMs < 250) timeoutMs = 250;

        switch (details.getType().toLowerCase()) {
            case "postgresql":
                config.setDriverClassName("org.postgresql.Driver");
                config.setAutoCommit(true);
                config.setConnectionInitSql("SELECT 1"); // Validasi koneksi baru sebelum dipakai
                config.addDataSourceProperty("prepareThreshold", "3");
                config.addDataSourceProperty("preparedStatementCacheQueries", "256");
                config.addDataSourceProperty("preparedStatementCacheSizeMiB", "5");
                config.addDataSourceProperty("defaultRowFetchSize", details.getFetchSize() != null ? String.valueOf(details.getFetchSize()) : "5000");
                config.addDataSourceProperty("reWriteBatchedInserts", "true");
                config.addDataSourceProperty("socketReceiveBufferSize", "1048576"); // 1MB buffer
                config.addDataSourceProperty("tcpKeepAlive", "true"); // Detect broken connections faster

                // Removed forced sslmode=disable for SSH tunnels because AWS RDS might enforce SSL.
                // We rely on the user's sslMode setting or default (prefer).
                if (isSet(details.getSslMode()) && !"disable".equalsIgnoreCase(details.getSslMode())) {
                    config.addDataSourceProperty("ssl", "true");
                    config.addDataSourceProperty("sslmode", details.getSslMode());
                    // Hanya set file cert kalau benar-benar diisi. Path kosong ("") membuat
                    // driver mencoba membaca file dari path kosong sehingga handshake menggantung.
                    String resolvedCa = resolveSslFile(details.getSslCaFile(), "pg_ca_", ".crt");
                    String resolvedCert = resolveSslFile(details.getSslCertFile(), "pg_cert_", ".crt");
                    String resolvedKey = resolveSslFile(details.getSslKeyFile(), "pg_key_", ".key");

                    if (isSet(resolvedCa)) config.addDataSourceProperty("sslrootcert", resolvedCa);
                    if (isSet(resolvedCert)) config.addDataSourceProperty("sslcert", resolvedCert);
                    if (isSet(resolvedKey)) config.addDataSourceProperty("sslkey", resolvedKey);
                } else {
                    config.addDataSourceProperty("sslmode", "disable");
                }

                // Batasi fase connect + TLS handshake di level driver (detik) agar host SSL yang
                // tidak responsif gagal cepat dengan pesan jelas, bukan menggantung sampai HikariCP timeout.
                config.addDataSourceProperty("loginTimeout", String.valueOf(Math.max(1, timeoutMs / 1000)));
                config.addDataSourceProperty("connectTimeout", String.valueOf(Math.max(1, timeoutMs / 1000)));

                if (details.getSocketTimeout() != null && details.getSocketTimeout() > 0) {
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
                
                if (isSet(details.getSslMode()) && !"disable".equalsIgnoreCase(details.getSslMode())) {
                    config.addDataSourceProperty("useSSL", "true");
                    if ("require".equalsIgnoreCase(details.getSslMode())) {
                        config.addDataSourceProperty("requireSSL", "true");
                    }
                    String resolvedCa = resolveSslFile(details.getSslCaFile(), "mysql_ca_", ".crt");
                    if (isSet(resolvedCa)) config.addDataSourceProperty("trustCertificateKeyStoreUrl", "file:" + resolvedCa);
                } else {
                    config.addDataSourceProperty("useSSL", "false");
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
                if (isSet(details.getSslMode()) && !"disable".equalsIgnoreCase(details.getSslMode())) {
                    config.addDataSourceProperty("encrypt", "true");
                    config.addDataSourceProperty("trustServerCertificate", "require".equalsIgnoreCase(details.getSslMode()) ? "false" : "true");
                }
                break;

            case "clickhouse":
                config.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
                config.setAutoCommit(true);
                if (isSet(details.getSslMode()) && !"disable".equalsIgnoreCase(details.getSslMode())) {
                    config.addDataSourceProperty("ssl", "true");
                    // require -> strict (SSL enabled, minimal cert check)
                    // verify-ca, verify-full -> strict + SSL cert verification
                    // prefer/allow -> none (SSL enabled, no cert verification)
                    String mode = details.getSslMode().toLowerCase();
                    if ("verify-ca".equals(mode) || "verify-full".equals(mode) || "require".equals(mode)) {
                        config.addDataSourceProperty("sslmode", "strict");
                    } else {
                        config.addDataSourceProperty("sslmode", "none");
                    }
                    String resolvedCa = resolveSslFile(details.getSslCaFile(), "ch_ca_", ".crt");
                    String resolvedCert = resolveSslFile(details.getSslCertFile(), "ch_cert_", ".crt");
                    String resolvedKey = resolveSslFile(details.getSslKeyFile(), "ch_key_", ".key");

                    if (isSet(resolvedCa)) config.addDataSourceProperty("sslrootcert", resolvedCa);
                    if (isSet(resolvedCert)) config.addDataSourceProperty("sslcert", resolvedCert);
                    if (isSet(resolvedKey)) config.addDataSourceProperty("sslkey", resolvedKey);
                }
                // Timeout dalam milliseconds sesuai format ClickHouse JDBC driver
                config.addDataSourceProperty("connect_timeout", String.valueOf(timeoutMs));
                config.addDataSourceProperty("socket_timeout", String.valueOf(timeoutMs * 2)); // 2x untuk query timeout

                // Fix: Docker overlay network MTU (1350) dapat menyebabkan TLS record fragmentation
                // yang membuat Java HttpClient hang saat SSL handshake dengan ClickHouse Cloud.
                // curl dari host berhasil karena pakai host network stack (MTU 1400).
                // Solusi: set socket buffer lebih kecil agar TLS records tidak di-fragment,
                // dan disable HTTP compression yang memperburuk ukuran paket.
                config.addDataSourceProperty("compress", "0");             // Disable LZ4 compression
                config.addDataSourceProperty("http_connection_provider", "apache"); // Pakai Apache HttpClient
                config.addDataSourceProperty("custom_http_params",
                    "socket_rcvbuf=65536,socket_sndbuf=65536");            // Limit socket buffer = less fragmentation
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

        config.setMaximumPoolSize(20);    // Diperbesar agar aman untuk sync banyak tabel paralel
        config.setMinimumIdle(2);
        config.setConnectionTimeout(timeoutMs);
        config.setIdleTimeout(300000);    // 5 menit (RDS idle timeout biasanya 10 menit)
        config.setMaxLifetime(540000);    // 9 menit — lebih pendek dari RDS idle timeout utk cegah EOF
        config.setKeepaliveTime(300000);  // 5 menit
        config.setLeakDetectionThreshold(0); // Matikan (0) karena proses stream sync memang memakan waktu lama, agar log tidak penuh false alarm
        config.setConnectionTestQuery("SELECT 1");

        HikariDataSource ds = new HikariDataSource(config);
        return ds;
    }

    public Map<String, Object> testConnection(ConnectionDetails details) {
        try {
            DataSource ds = getDataSource(details);
            try (java.sql.Connection conn = ds.getConnection()) {
                boolean valid;
                String errorMsg = null;
                try {
                    valid = conn.isValid(15);
                } catch (Exception e) {
                    valid = false;
                    errorMsg = e.getMessage();
                }
                
                if (!valid) {
                    try (java.sql.Statement stmt = conn.createStatement()) {
                        stmt.setQueryTimeout(15);
                        stmt.execute("SELECT 1");
                        valid = true;
                        errorMsg = null;
                    } catch (Exception e) {
                        valid = false;
                        errorMsg = e.getMessage();
                    }
                }

                if (valid) {
                    return Map.of("success", true, "message", "Connection successful");
                } else {
                    return Map.of("success", false, "message", "Connection validation failed: " + (errorMsg != null ? errorMsg : "Timeout or false response"));
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
