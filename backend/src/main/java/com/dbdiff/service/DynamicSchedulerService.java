package com.dbdiff.service;

import com.dbdiff.model.*;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.repository.NotificationChannelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;

@Service
public class DynamicSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(DynamicSchedulerService.class);

    private final TaskScheduler taskScheduler;
    private final ScheduleManagerService scheduleManagerService;
    private final DataComparisonService dataComparisonService;
    private final NotificationService notificationService;
    private final ConnectionRepository connectionRepository;
    private final NotificationChannelRepository notificationChannelRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    
    // Limit concurrent scheduled compare jobs to prevent OOM
    // Only 1 job runs at a time; others queue up
    private final Semaphore jobSemaphore = new Semaphore(1);

    @Autowired
    private org.springframework.core.task.TaskExecutor taskExecutor;

    @Autowired
    public DynamicSchedulerService(TaskScheduler taskScheduler,
                                   ScheduleManagerService scheduleManagerService,
                                   DataComparisonService dataComparisonService,
                                   NotificationService notificationService,
                                   ConnectionRepository connectionRepository,
                                   NotificationChannelRepository notificationChannelRepository) {
        this.taskScheduler = taskScheduler;
        this.scheduleManagerService = scheduleManagerService;
        this.dataComparisonService = dataComparisonService;
        this.notificationService = notificationService;
        this.connectionRepository = connectionRepository;
        this.notificationChannelRepository = notificationChannelRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            logger.info("Initializing Dynamic Scheduler...");
            List<ScheduleConfig> schedules = scheduleManagerService.getAllSchedules();
            for (ScheduleConfig schedule : schedules) {
                if (schedule.isActive()) {
                    scheduleTask(schedule);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to initialize scheduler (DB might be unavailable): {}", e.getMessage());
        }
    }

    public void refreshSchedule(String scheduleId) {
        // Cek apakah sebelumnya sudah aktif (ada di scheduledTasks) agar kita tahu ini toggle ON atau update biasa
        boolean wasScheduled = scheduledTasks.containsKey(scheduleId);
        cancelSchedule(scheduleId);
        ScheduleConfig schedule = scheduleManagerService.getSchedule(scheduleId);
        if (schedule != null && schedule.isActive()) {
            scheduleTask(schedule);
            // Hanya trigger immediate execution jika transisi dari inactive → active
            // (toggle ON), bukan saat update schedule yang sudah aktif
            if (!wasScheduled) {
                logger.info("Triggering immediate execution for schedule: {} ({})", schedule.getName(), scheduleId);
                taskExecutor.execute(() -> executeCompareJob(scheduleId));
            }
        }
    }

    public void cancelSchedule(String scheduleId) {
        ScheduledFuture<?> future = scheduledTasks.remove(scheduleId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void scheduleTask(ScheduleConfig schedule) {
        Runnable task = () -> executeCompareJob(schedule.getId());
        try {
            CronTrigger cronTrigger = new CronTrigger(schedule.getCronExpression());
            ScheduledFuture<?> future = taskScheduler.schedule(task, cronTrigger);
            scheduledTasks.put(schedule.getId(), future);
            logger.info("Scheduled job: {} with cron: {}", schedule.getName(), schedule.getCronExpression());
        } catch (Exception e) {
            logger.error("Failed to schedule job {}: {}", schedule.getName(), e.getMessage());
        }
    }

    public void executeCompareJob(String scheduleId) {
        // Acquire semaphore to limit concurrent jobs — prevents OOM from multiple large comparisons
        boolean acquired = false;
        try {
            acquired = jobSemaphore.tryAcquire(5, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Job {} interrupted while waiting for semaphore", scheduleId);
            return;
        }
        if (!acquired) {
            logger.warn("Job {} skipped — another job is still running after 5min wait", scheduleId);
            return;
        }
        
        try {
            executeCompareJobInternal(scheduleId);
        } finally {
            jobSemaphore.release();
        }
    }

    private void executeCompareJobInternal(String scheduleId) {
        logger.info("Executing scheduled compare job for schedule: {}", scheduleId);
        ScheduleConfig schedule = scheduleManagerService.getSchedule(scheduleId);
        if (schedule == null) return;

        ScheduleResult result = new ScheduleResult();
        result.setId(UUID.randomUUID().toString());
        result.setScheduleId(scheduleId);
        result.setRunTime(LocalDateTime.now());
        
        // Save initial result first to satisfy Foreign Key constraints for detail rows
        scheduleManagerService.saveResult(result);

        try {
            ConnectionDetails srcConn = connectionRepository.findById(schedule.getSourceConnectionId());
            ConnectionDetails tgtConn = connectionRepository.findById(schedule.getTargetConnectionId());

            if (srcConn == null || tgtConn == null) {
                throw new Exception("Source or Target connection not found");
            }

            List<Map<String, Object>> mappings = new ArrayList<>();
            if (schedule.getMappings() != null && !schedule.getMappings().isEmpty()) {
                mappings = objectMapper.readValue(schedule.getMappings(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>(){});
            } else {
                // Fallback for legacy single-mapping jobs
                Map<String, Object> legacyMapping = new HashMap<>();
                legacyMapping.put("sourceTable", schedule.getSourceTable());
                legacyMapping.put("targetTable", schedule.getTargetTable());
                legacyMapping.put("customQuerySource", schedule.getCustomQuerySource());
                legacyMapping.put("customQueryTarget", schedule.getCustomQueryTarget());
                legacyMapping.put("primaryKeys", schedule.getPrimaryKeys());
                legacyMapping.put("excludeColumns", schedule.getExcludeColumns());
                legacyMapping.put("sortColumns", schedule.getSortColumns());
                mappings.add(legacyMapping);
            }

            int totalMatch = 0, totalDifferent = 0, totalSrcOnly = 0, totalTgtOnly = 0;
            List<Map<String, Object>> executionDetails = new ArrayList<>();

            for (Map<String, Object> mapping : mappings) {
                try {
                    DiffRequest request = new DiffRequest();
                    request.setSourceConnection(srcConn);
                    request.setTargetConnection(tgtConn);

                    String sourceTable = (String) mapping.get("sourceTable");
                    String targetTable = (String) mapping.get("targetTable");
                    String cqSource = (String) mapping.get("customQuerySource");
                    String cqTarget = (String) mapping.get("customQueryTarget");

                    if ((cqSource != null && !cqSource.isEmpty()) || (cqTarget != null && !cqTarget.isEmpty())) {
                        request.setCustomQuerySource(cqSource);
                        request.setCustomQueryTarget(cqTarget);
                        request.setTableName(null);
                    } else {
                        if (sourceTable != null && sourceTable.equals(targetTable)) {
                            request.setTableName(sourceTable);
                        } else {
                            request.setCustomQuerySource("SELECT * FROM " + sourceTable);
                            request.setCustomQueryTarget("SELECT * FROM " + targetTable);
                        }
                    }

                    // Primary Keys
                    Object pks = mapping.get("primaryKeys");
                    if (pks instanceof List) {
                        request.setPrimaryKeys((List<String>) pks);
                    } else if (pks instanceof String && !((String) pks).isEmpty() && !pks.equals("[]")) {
                        request.setPrimaryKeys(objectMapper.readValue((String) pks, new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
                    }

                    // Exclude Columns
                    Object excludes = mapping.get("excludeColumns");
                    if (excludes instanceof List) {
                        request.setExcludeColumns((List<String>) excludes);
                    } else if (excludes instanceof String && !((String) excludes).isEmpty() && !excludes.equals("[]")) {
                        request.setExcludeColumns(objectMapper.readValue((String) excludes, new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
                    }

                    // Sort Columns
                    Object sorts = mapping.get("sortColumns");
                    if (sorts instanceof List) {
                        request.setSortColumns((List<String>) sorts);
                    } else if (sorts instanceof String && !((String) sorts).isEmpty() && !sorts.equals("[]")) {
                        request.setSortColumns(objectMapper.readValue((String) sorts, new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
                    }

                    request.setReturnMatchedRows(false);
                    int[] mArr = {0}, dArr = {0}, sArr = {0}, tArr = {0};
                    int[] totalsArr = {0}; // totalSource from onTotals
                    // FIX: Stream rows directly to DB instead of collecting in memory
                    String displayTableName = sourceTable != null ? sourceTable : "Custom Query";
                    final String resultId = result.getId();

                    dataComparisonService.processStream(request, new DiffRowConsumer() {
                        private static final int BATCH_FLUSH_SIZE = 100;
                        private final List<ScheduleResultRow> rowBuffer = new ArrayList<>(BATCH_FLUSH_SIZE);

                        @Override
                        public void onColumns(List<String> columns) throws Exception {}

                        @Override
                        public void onRow(DiffRow row) throws Exception {
                            // NOTE: MATCH rows are NOT sent when returnMatchedRows=false.
                            // Only DIFFERENT, SOURCE_ONLY, TARGET_ONLY arrive here.
                            switch (row.getStatus()) {
                                case DIFFERENT: dArr[0]++; break;
                                case SOURCE_ONLY: sArr[0]++; break;
                                case TARGET_ONLY: tArr[0]++; break;
                                default: break;
                            }
                            // Buffer rows and flush in batches to reduce DB round-trips
                            if (schedule.isSaveFullData()) {
                                ScheduleResultRow rr = new ScheduleResultRow();
                                rr.setResultId(resultId);
                                rr.setRowKey(row.getRowKey());
                                rr.setStatus(row.getStatus().name());
                                rr.setDataJson(objectMapper.writeValueAsString(row.getCells()));
                                rr.setTableName(displayTableName);
                                rowBuffer.add(rr);
                                if (rowBuffer.size() >= BATCH_FLUSH_SIZE) {
                                    flushBuffer();
                                }
                            }
                        }

                        private void flushBuffer() {
                            if (!rowBuffer.isEmpty()) {
                                scheduleManagerService.saveResultRowsBatch(rowBuffer);
                                rowBuffer.clear();
                            }
                        }

                        @Override
                        public void onTotals(int totalSource, int totalTarget, int totalDiffs) throws Exception {
                            // Flush remaining buffered rows before finishing
                            flushBuffer();
                            // Calculate match count from totals:
                            // totalSource = M + D + S  →  M = totalSource - D - S
                            totalsArr[0] = totalSource;
                            mArr[0] = totalSource - dArr[0] - sArr[0];
                            if (mArr[0] < 0) mArr[0] = 0; // safety guard
                        }
                    });

                    totalMatch += mArr[0];
                    totalDifferent += dArr[0];
                    totalSrcOnly += sArr[0];
                    totalTgtOnly += tArr[0];

                    Map<String, Object> tableResult = new HashMap<>();
                    tableResult.put("tableName", displayTableName);
                    tableResult.put("match", mArr[0]);
                    tableResult.put("different", dArr[0]);
                    tableResult.put("sourceOnly", sArr[0]);
                    tableResult.put("targetOnly", tArr[0]);
                    tableResult.put("totalSourceRows", totalsArr[0]);
                    executionDetails.add(tableResult);
                } catch (Exception e) {
                    logger.error("Error comparing mapping: {}", e.getMessage(), e);
                    Map<String, Object> errorTable = new HashMap<>();
                    errorTable.put("tableName", mapping.get("sourceTable"));
                    errorTable.put("error", e.getMessage());
                    executionDetails.add(errorTable);
                }
            }

            result.setMatchCount(totalMatch);
            result.setDifferentCount(totalDifferent);
            result.setSourceOnlyCount(totalSrcOnly);
            result.setTargetOnlyCount(totalTgtOnly);
            result.setDetails(objectMapper.writeValueAsString(executionDetails));

            scheduleManagerService.updateResult(result);
            scheduleManagerService.updateLastRun(scheduleId, LocalDateTime.now());

            int diffs = result.getDifferentCount() + result.getSourceOnlyCount() + result.getTargetOnlyCount();
            logger.info("Job {} finished. Total diffs: {}", schedule.getName(), diffs);
            
            boolean hasDiffs = diffs > 0;
            logger.info("Checking notification channels for job {}...", schedule.getName());
            logger.debug("Telegram Channel ID: {}", schedule.getTelegramChannelId());
            logger.debug("Discord Channel ID: {}", schedule.getDiscordChannelId());

            // Build per-table breakdown
            StringBuilder tableDetailsHtml = new StringBuilder();
            StringBuilder tableDetailsDiscord = new StringBuilder();
            tableDetailsHtml.append("\n<b>📋 Per-Table Breakdown:</b>\n");
            tableDetailsDiscord.append("\n**📋 Per-Table Breakdown:**\n");
            int shownTables = 0;
            int maxTablesToShow = 20;
            for (Map<String, Object> td : executionDetails) {
                if (shownTables >= maxTablesToShow) {
                    int remaining = executionDetails.size() - maxTablesToShow;
                    tableDetailsHtml.append(String.format("  ... and %d more tables (see dashboard)\n", remaining));
                    tableDetailsDiscord.append(String.format("  ... and %d more tables (see dashboard)\n", remaining));
                    break;
                }
                shownTables++;
                if (td.containsKey("error")) {                        String errTable = (String) td.getOrDefault("tableName", "unknown");
                    tableDetailsHtml.append(String.format(
                            "  ❌ <i>%s</i>: ERROR - %s\n", errTable, td.get("error")));
                    tableDetailsDiscord.append(String.format(
                            "  ❌ *%s*: ERROR - %s\n", errTable, td.get("error")));
                    continue;
                }
                String tn = (String) td.getOrDefault("tableName", "unknown");
                int match = ((Number) td.getOrDefault("match", 0)).intValue();
                int diff = ((Number) td.getOrDefault("different", 0)).intValue();
                int srcOnly = ((Number) td.getOrDefault("sourceOnly", 0)).intValue();
                int tgtOnly = ((Number) td.getOrDefault("targetOnly", 0)).intValue();
                int perTableDiff = diff + srcOnly + tgtOnly;

                if (perTableDiff > 0) {
                    tableDetailsHtml.append(String.format(
                            "  🔴 <i>%s</i> — %d different, %d source-only, %d target-only (match: %d)\n",
                            tn, diff, srcOnly, tgtOnly, match));
                    tableDetailsDiscord.append(String.format(
                            "  🔴 *%s* — %d different, %d source-only, %d target-only (match: %d)\n",
                            tn, diff, srcOnly, tgtOnly, match));
                } else {
                    tableDetailsHtml.append(String.format("  ✅ <i>%s</i> — %d rows match, no differences\n", tn, match));
                    tableDetailsDiscord.append(String.format("  ✅ *%s* — %d rows match, no differences\n", tn, match));
                }
            }

            String titleHtml = hasDiffs ? "<b>🚨 Data Mismatch Detected!</b>" : "<b>✅ All Data Match!</b>";
            String titleDiscord = hasDiffs ? "🚨 **Data Mismatch Detected!**" : "✅ **All Data Match!**";
            String statusHtml = hasDiffs ? String.format("⚠️ %d differences found", diffs) : "✅ No differences found";
            String statusDiscord = hasDiffs ? String.format("⚠️ %d differences found", diffs) : "✅ No differences found";

            String message = String.format(
                    "%s\n\n" +
                    "<b>Job:</b> <i>%s</i>\n" +
                    "<b>Run Time:</b> %s\n" +
                    "<b>Status:</b> %s\n\n" +
                    "<b>📊 Total Summary:</b>\n" +
                    "  ✅ Match: %d\n" +
                    "  🔴 Different: %d\n" +
                    "  🔵 Source Only: %d\n" +
                    "  🟡 Target Only: %d\n" +
                    "%s\n" +
                    "━━━━━━━━━━━━━━━━━━━\n" +
                    "<i>Check the Dashboard for full details.</i>",
                    titleHtml,
                    schedule.getName(),
                    result.getRunTime().toString().replace("T", " "),
                    statusHtml,
                    result.getMatchCount(), result.getDifferentCount(), result.getSourceOnlyCount(), result.getTargetOnlyCount(),
                    tableDetailsHtml.toString());

            String discordMessage = String.format(
                    "%s\n\n" +
                    "**Job:** *%s*\n" +
                    "**Run Time:** %s\n" +
                    "**Status:** %s\n\n" +
                    "**📊 Total Summary:**\n" +
                    "  ✅ Match: %d\n" +
                    "  🔴 Different: %d\n" +
                    "  🔵 Source Only: %d\n" +
                    "  🟡 Target Only: %d\n" +
                    "%s\n" +
                    "━━━━━━━━━━━━━━━━━━━\n" +
                    "*Check the Dashboard for full details.*",
                    titleDiscord,
                    schedule.getName(),
                    result.getRunTime().toString().replace("T", " "),
                    statusDiscord,
                    result.getMatchCount(), result.getDifferentCount(), result.getSourceOnlyCount(), result.getTargetOnlyCount(),
                    tableDetailsDiscord.toString());

            if (schedule.getTelegramChannelId() != null && !schedule.getTelegramChannelId().isEmpty()) {
                logger.info("Sending to Telegram channel: {}", schedule.getTelegramChannelId());
                notificationService.sendToChannel(schedule.getTelegramChannelId(), message);
            }
            if (schedule.getDiscordChannelId() != null && !schedule.getDiscordChannelId().isEmpty()) {
                logger.info("Sending to Discord channel: {}", schedule.getDiscordChannelId());
                notificationService.sendToChannel(schedule.getDiscordChannelId(), discordMessage);
            }
        } catch (Exception e) {
            logger.error("Error executing job: {}", e.getMessage(), e);
            result.setErrorMessage(e.getMessage());
            try {
                scheduleManagerService.updateResult(result);
            } catch (Exception saveErr) {
                logger.error("Failed to save error result: {}", saveErr.getMessage());
            }
        }
    }
}
