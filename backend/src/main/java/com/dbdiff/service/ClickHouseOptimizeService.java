package com.dbdiff.service;

import com.dbdiff.model.ClickHouseOptimizeSchedule;
import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ClickHouseOptimizeScheduleRepository;
import com.dbdiff.repository.ConnectionRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class ClickHouseOptimizeService {

    private static final Logger logger = LoggerFactory.getLogger(ClickHouseOptimizeService.class);
    private static final ZoneId JAKARTA_ZONE = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter NEXT_RUN_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'WIB'");

    @Value("${app.scheduling.enabled:true}")
    private boolean schedulingEnabled;

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private ClickHouseOptimizeScheduleRepository scheduleRepo;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionManagerService connectionManagerService;

    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> runningLocks = new ConcurrentHashMap<>();
    private final ExecutorService manualExecutor = Executors.newSingleThreadExecutor();

    @EventListener(ApplicationReadyEvent.class)
    public void initSchedules() {
        if (!schedulingEnabled) {
            logger.info("[SCHEDULING DISABLED] ClickHouseOptimizeService is disabled.");
            return;
        }
        logger.info("Initializing ClickHouse Optimize Schedules...");
        refreshAllSchedules();
    }

    @PreDestroy
    public void shutdown() {
        manualExecutor.shutdown();
        scheduledTasks.values().forEach(f -> {
            if (f != null) f.cancel(false);
        });
    }

    public synchronized void refreshAllSchedules() {
        scheduledTasks.values().forEach(f -> {
            if (f != null) f.cancel(false);
        });
        scheduledTasks.clear();

        for (ClickHouseOptimizeSchedule s : scheduleRepo.findAll()) {
            if (s.isActive()) {
                scheduleTask(s);
            }
        }
    }

    public synchronized void scheduleTask(ClickHouseOptimizeSchedule s) {
        if (s == null || s.getId() == null) return;
        cancelTask(s.getId());
        if (!s.isActive()) return;

        String cron = normalizeCron(s.getCronExpression());
        try {
            CronExpression.parse(cron);
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executeOptimize(s.getId()),
                    new CronTrigger(cron, JAKARTA_ZONE)
            );
            scheduledTasks.put(s.getId(), future);
            logger.info("Scheduled ClickHouse Optimize [{}] with cron '{}' (Asia/Jakarta)", s.getName(), cron);
        } catch (Exception e) {
            logger.error("Failed to schedule ClickHouse Optimize [{}] with cron '{}': {}", s.getName(), cron, e.getMessage());
        }
    }

    public synchronized void cancelTask(String id) {
        ScheduledFuture<?> existing = scheduledTasks.remove(id);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    public String normalizeCron(String cron) {
        if (cron == null || cron.trim().isEmpty()) {
            return "0 0 3 1 */5 *";
        }
        String trimmed = cron.trim();
        if (trimmed.split("\\s+").length == 5) {
            return "0 " + trimmed;
        }
        return trimmed;
    }

    public void validateCron(String cron) {
        String normalized = normalizeCron(cron);
        CronExpression.parse(normalized);
    }

    public String computeNextRun(String cronExpression, boolean active) {
        if (!active) return "Paused";
        try {
            CronExpression expr = CronExpression.parse(normalizeCron(cronExpression));
            ZonedDateTime next = expr.next(ZonedDateTime.now(JAKARTA_ZONE));
            return next != null ? next.format(NEXT_RUN_FMT) : "-";
        } catch (Exception e) {
            return "Invalid cron";
        }
    }

    public boolean triggerManualRun(String scheduleId) {
        ClickHouseOptimizeSchedule s = scheduleRepo.findById(scheduleId);
        if (s == null) {
            throw new IllegalArgumentException("Schedule not found: " + scheduleId);
        }
        if (Boolean.TRUE.equals(runningLocks.get(scheduleId))) {
            return false;
        }
        manualExecutor.submit(() -> executeOptimize(scheduleId));
        return true;
    }

    public void executeOptimize(String scheduleId) {
        if (Boolean.TRUE.equals(runningLocks.putIfAbsent(scheduleId, true))) {
            logger.warn("ClickHouse Optimize [{}] is already running, skipping duplicate trigger.", scheduleId);
            return;
        }

        long startMs = System.currentTimeMillis();
        try {
            ClickHouseOptimizeSchedule s = scheduleRepo.findById(scheduleId);
            if (s == null) return;

            scheduleRepo.updateExecutionResult(scheduleId, "RUNNING", "Scanning ReplacingMergeTree tables...");

            ConnectionDetails connDetails = resolveClickHouseConnection(s.getConnectionId());
            if (connDetails == null) {
                scheduleRepo.updateExecutionResult(scheduleId, "FAILED", "No ClickHouse connection found.");
                return;
            }

            String dbName = (s.getDatabaseName() != null && !s.getDatabaseName().trim().isEmpty())
                    ? s.getDatabaseName().trim() : "dw_erp";

            List<String> targetTables = new ArrayList<>();
            long bytesBefore = 0L;

            try (Connection conn = connectionManagerService.getDataSource(connDetails).getConnection()) {
                String listSql = "SELECT name, total_bytes FROM system.tables " +
                        "WHERE database = ? AND engine LIKE '%ReplacingMergeTree%' " +
                        "ORDER BY total_bytes DESC";

                try (PreparedStatement ps = conn.prepareStatement(listSql)) {
                    ps.setString(1, dbName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String tableName = rs.getString("name");
                            long bytes = rs.getLong("total_bytes");
                            if (!s.isIncludeCdcTables() && tableName.startsWith("cdc_")) {
                                continue;
                            }
                            if (matchesPattern(tableName, s.getTablePattern())) {
                                targetTables.add(tableName);
                                bytesBefore += bytes;
                            }
                        }
                    }
                }

                int successCount = 0;
                int failCount = 0;
                String lastErr = null;

                for (String table : targetTables) {
                    String safeDb = dbName.replace("`", "");
                    String safeTable = table.replace("`", "");
                    // OPTIMIZE ... FINAL deduplicates older ReplacingMergeTree versions while preserving is_deleted = 1 rows
                    String optSql = "OPTIMIZE TABLE `" + safeDb + "`.`" + safeTable + "` FINAL";
                    try (Statement st = conn.createStatement()) {
                        st.setQueryTimeout(600);
                        st.execute(optSql);
                        successCount++;
                    } catch (Exception ex) {
                        failCount++;
                        lastErr = ex.getMessage();
                        logger.warn("OPTIMIZE TABLE {}.{} FINAL failed: {}", safeDb, safeTable, ex.getMessage());
                    }

                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                long bytesAfter = 0L;
                try (PreparedStatement ps = conn.prepareStatement(listSql)) {
                    ps.setString(1, dbName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String tableName = rs.getString("name");
                            long bytes = rs.getLong("total_bytes");
                            if (!s.isIncludeCdcTables() && tableName.startsWith("cdc_")) {
                                continue;
                            }
                            if (matchesPattern(tableName, s.getTablePattern())) {
                                bytesAfter += bytes;
                            }
                        }
                    }
                }

                long durationSec = Math.max(1L, (System.currentTimeMillis() - startMs) / 1000L);
                long freedBytes = Math.max(0L, bytesBefore - bytesAfter);
                String status = (failCount == 0) ? "SUCCESS" : (successCount > 0 ? "PARTIAL_ERROR" : "FAILED");
                String summary = String.format(
                        "Optimized %d/%d tables in %s (%ds). Size: %s -> %s (Freed %s)%s",
                        successCount, targetTables.size(), dbName, durationSec,
                        formatBytes(bytesBefore), formatBytes(bytesAfter), formatBytes(freedBytes),
                        (lastErr != null ? " | Last error: " + lastErr : "")
                );

                scheduleRepo.updateExecutionResult(scheduleId, status, summary);
                logger.info("ClickHouse Optimize [{}] finished: {}", s.getName(), summary);
            }
        } catch (Exception e) {
            logger.error("ClickHouse Optimize [{}] failed: {}", scheduleId, e.getMessage(), e);
            scheduleRepo.updateExecutionResult(scheduleId, "FAILED", "Error: " + e.getMessage());
        } finally {
            runningLocks.remove(scheduleId);
        }
    }

    private ConnectionDetails resolveClickHouseConnection(String connectionId) {
        if (connectionId != null && !connectionId.trim().isEmpty()) {
            ConnectionDetails c = connectionRepository.findById(connectionId.trim());
            if (c != null) return c;
        }
        for (ConnectionDetails c : connectionRepository.findAll()) {
            if (c.getType() != null && "clickhouse".equalsIgnoreCase(c.getType().trim())) {
                return c;
            }
        }
        return null;
    }

    private boolean matchesPattern(String tableName, String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) return true;
        String p = pattern.trim().toLowerCase();
        String t = tableName.toLowerCase();
        if (p.contains("%")) {
            String regex = "^" + p.replace(".", "\\.").replace("%", ".*") + "$";
            return t.matches(regex);
        }
        return t.contains(p);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KiB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2f MiB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GiB", gb);
    }
}
