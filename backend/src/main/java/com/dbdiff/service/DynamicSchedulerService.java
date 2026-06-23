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
    
    // Limit concurrent scheduled compare jobs per-schedule
    private final Map<String, Semaphore> scheduleSemaphores = new ConcurrentHashMap<>();
    private final Map<String, Boolean> lastRunErrorState = new ConcurrentHashMap<>();

    private Semaphore getScheduleSemaphore(String scheduleId) {
        return scheduleSemaphores.computeIfAbsent(scheduleId, k -> new Semaphore(1));
    }

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
        // Do not remove from scheduleSemaphores to prevent race conditions
        // if a job is currently executing and releasing the semaphore.
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
        Semaphore sem = getScheduleSemaphore(scheduleId);
        boolean acquired = false;
        try {
            acquired = sem.tryAcquire(1, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Job {} interrupted while waiting for semaphore", scheduleId);
            return;
        }
        if (!acquired) {
            logger.warn("Job {} skipped — previous run still in progress", scheduleId);
            return;
        }
        
        try {
            executeCompareJobInternal(scheduleId);
        } finally {
            sem.release();
        }
    }

    @SuppressWarnings("unchecked")
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
                String label = (String) mapping.get("label");
                String rawSourceTable = (String) mapping.get("sourceTable");
                String displayTableName = label != null && !label.trim().isEmpty() ? label.trim() : rawSourceTable;
                if (displayTableName == null || displayTableName.equalsIgnoreCase("query") || displayTableName.trim().isEmpty()) {
                    displayTableName = schedule.getName() != null && !schedule.getName().trim().isEmpty() ? schedule.getName() : "Custom Query";
                }

                try {
                    DiffRequest request = new DiffRequest();
                    request.setSourceConnection(srcConn);
                    request.setTargetConnection(tgtConn);

                    String sourceTable = rawSourceTable;
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

                    request.setReturnMatchedRows(true);
                    int[] mArr = {0}, dArr = {0}, sArr = {0}, tArr = {0};
                    int[] totalsArr = {0}; // totalSource from onTotals
                    final String finalDisplayTableName = displayTableName;
                    final String resultId = result.getId();

                    dataComparisonService.processStream(request, new DiffRowConsumer() {
                        private static final int BATCH_FLUSH_SIZE = 100;
                        private final List<ScheduleResultRow> rowBuffer = new ArrayList<>(BATCH_FLUSH_SIZE);

                        @Override
                        public void onColumns(List<String> columns) throws Exception {}

                        @Override
                        public void onMatchRow(String key, Object[] values, List<String> columns) throws Exception {
                            mArr[0]++;
                        }

                        @Override
                        public void onRow(DiffRow row) throws Exception {
                            switch (row.getStatus()) {
                                case DIFFERENT: dArr[0]++; break;
                                case SOURCE_ONLY: sArr[0]++; break;
                                case TARGET_ONLY: tArr[0]++; break;
                                default: break;
                            }
                            if (schedule.isSaveFullData()) {
                                ScheduleResultRow rr = new ScheduleResultRow();
                                rr.setResultId(resultId);
                                rr.setRowKey(row.getRowKey());
                                rr.setStatus(row.getStatus().name());
                                rr.setDataJson(objectMapper.writeValueAsString(row.getCells()));
                                rr.setTableName(finalDisplayTableName);
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
                            flushBuffer();
                            totalsArr[0] = totalSource;
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
                    errorTable.put("tableName", displayTableName);
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
            
            boolean hasErrors = executionDetails.stream().anyMatch(td -> td.containsKey("error"));
            boolean hasDiffs = diffs > 0;
            logger.info("Checking notification channels for job {}...", schedule.getName());
            logger.debug("Telegram Channel ID: {}", schedule.getTelegramChannelId());
            logger.debug("Discord Channel ID: {}", schedule.getDiscordChannelId());
            
            // Build per-table breakdown
            StringBuilder tableDetailsHtml = new StringBuilder();
            StringBuilder tableDetailsDiscord = new StringBuilder();
            
            String titleHtml;
            String titleDiscord;
            String statusHtml;
            String statusDiscord;

            if (hasErrors) {
                titleHtml = "<b>❌ Job Finished with Errors!</b>";
                titleDiscord = "❌ **Job Finished with Errors!**";
                statusHtml = hasDiffs ? String.format("⚠️ %d differences found, and some tables failed", diffs) : "❌ Some tables failed to compare";
                statusDiscord = hasDiffs ? String.format("⚠️ %d differences found, and some tables failed", diffs) : "❌ Some tables failed to compare";
            } else if (hasDiffs) {
                titleHtml = "<b>🚨 Data Mismatch Detected!</b>";
                titleDiscord = "🚨 **Data Mismatch Detected!**";
                statusHtml = String.format("⚠️ %d differences found", diffs);
                statusDiscord = String.format("⚠️ %d differences found", diffs);
            } else {
                titleHtml = "<b>✅ All Data Match!</b>";
                titleDiscord = "✅ **All Data Match!**";
                statusHtml = "✅ No differences found";
                statusDiscord = "✅ No differences found";
            }

            String telegramTemplate = String.format(
                    "%s\n\n" +
                    "<b>Job:</b> <i>%s</i>\n" +
                    "<b>Run Time:</b> %s\n" +
                    "<b>Status:</b> %s\n\n" +
                    "<b>📊 Total Summary:</b>\n" +
                    "  ✅ Match: %d\n" +
                    "  🔴 Different: %d\n" +
                    "  🔵 Source Only: %d\n" +
                    "  🟡 Target Only: %d\n\n" +
                    "<b>📋 Per-Table Breakdown:</b>\n" +
                    "%%s" +
                    "━━━━━━━━━━━━━━━━━━━\n" +
                    "<i>Check the Dashboard for full details.</i>",
                    titleHtml,
                    schedule.getName(),
                    result.getRunTime().toString().replace("T", " "),
                    statusHtml,
                    result.getMatchCount(), result.getDifferentCount(), result.getSourceOnlyCount(), result.getTargetOnlyCount()
            );

            String discordTemplate = String.format(
                    "%s\n\n" +
                    "**Job:** *%s*\n" +
                    "**Run Time:** %s\n" +
                    "**Status:** %s\n\n" +
                    "**📊 Total Summary:**\n" +
                    "  ✅ Match: %d\n" +
                    "  🔴 Different: %d\n" +
                    "  🔵 Source Only: %d\n" +
                    "  🟡 Target Only: %d\n\n" +
                    "**📋 Per-Table Breakdown:**\n" +
                    "%%s" +
                    "━━━━━━━━━━━━━━━━━━━\n" +
                    "*Check the Dashboard for full details.*",
                    titleDiscord,
                    schedule.getName(),
                    result.getRunTime().toString().replace("T", " "),
                    statusDiscord,
                    result.getMatchCount(), result.getDifferentCount(), result.getSourceOnlyCount(), result.getTargetOnlyCount()
            );

            int shownTables = 0;
            int maxTablesToShow = 20;
            boolean telegramTruncated = false;
            boolean discordTruncated = false;

            for (Map<String, Object> td : executionDetails) {
                if (shownTables >= maxTablesToShow) {
                    int remaining = executionDetails.size() - maxTablesToShow;
                    if (!telegramTruncated) {
                        tableDetailsHtml.append(String.format("  ... and %d more tables (see dashboard)\n", remaining));
                        telegramTruncated = true;
                    }
                    if (!discordTruncated) {
                        tableDetailsDiscord.append(String.format("  ... and %d more tables (see dashboard)\n", remaining));
                        discordTruncated = true;
                    }
                    break;
                }
                shownTables++;

                String nextHtmlLine = "";
                String nextDiscordLine = "";

                if (td.containsKey("error")) {
                    String errTable = (String) td.getOrDefault("tableName", "unknown");
                    nextHtmlLine = String.format("  ❌ <i>%s</i>: ERROR - %s\n", errTable, td.get("error"));
                    nextDiscordLine = String.format("  ❌ *%s*: ERROR - %s\n", errTable, td.get("error"));
                } else {
                    String tn = (String) td.getOrDefault("tableName", "unknown");
                    int match = ((Number) td.getOrDefault("match", 0)).intValue();
                    int diff = ((Number) td.getOrDefault("different", 0)).intValue();
                    int srcOnly = ((Number) td.getOrDefault("sourceOnly", 0)).intValue();
                    int tgtOnly = ((Number) td.getOrDefault("targetOnly", 0)).intValue();
                    int perTableDiff = diff + srcOnly + tgtOnly;

                    if (perTableDiff > 0) {
                        nextHtmlLine = String.format("  🔴 <i>%s</i> — %d different, %d source-only, %d target-only (match: %d)\n",
                                tn, diff, srcOnly, tgtOnly, match);
                        nextDiscordLine = String.format("  🔴 *%s* — %d different, %d source-only, %d target-only (match: %d)\n",
                                tn, diff, srcOnly, tgtOnly, match);
                    } else {
                        nextHtmlLine = String.format("  ✅ <i>%s</i> — %d rows match, no differences\n", tn, match);
                        nextDiscordLine = String.format("  ✅ *%s* — %d rows match, no differences\n", tn, match);
                    }
                }

                // Check length limits before appending
                if (!telegramTruncated) {
                    if (String.format(telegramTemplate, tableDetailsHtml.toString() + nextHtmlLine).length() > 3900) {
                        tableDetailsHtml.append("  ... (breakdown truncated, view dashboard for full details)\n");
                        telegramTruncated = true;
                    } else {
                        tableDetailsHtml.append(nextHtmlLine);
                    }
                }

                if (!discordTruncated) {
                    if (String.format(discordTemplate, tableDetailsDiscord.toString() + nextDiscordLine).length() > 1850) {
                        tableDetailsDiscord.append("  ... (breakdown truncated, view dashboard for full details)\n");
                        discordTruncated = true;
                    } else {
                        tableDetailsDiscord.append(nextDiscordLine);
                    }
                }
            }

            String message = String.format(telegramTemplate, tableDetailsHtml.toString());
            String discordMessage = String.format(discordTemplate, tableDetailsDiscord.toString());

            boolean shouldNotify = false;
            
            // Only send if there are diffs
            if (hasDiffs) {
                shouldNotify = true;
            }
            
            // Or if there is an error, but ONLY if the last run didn't have an error (throttle error spam)
            if (hasErrors) {
                Boolean wasError = lastRunErrorState.getOrDefault(scheduleId, false);
                if (!wasError) {
                    shouldNotify = true;
                }
            }
            
            lastRunErrorState.put(scheduleId, hasErrors);

            if (!shouldNotify) {
                logger.info("Skipping notification for schedule {} (no diffs, or error already reported).", schedule.getName());
            } else {
                if (schedule.getTelegramChannelId() != null && !schedule.getTelegramChannelId().isEmpty()) {
                    logger.info("Sending to Telegram channel: {}", schedule.getTelegramChannelId());
                    notificationService.sendToChannel(schedule.getTelegramChannelId(), message);
                }
                if (schedule.getDiscordChannelId() != null && !schedule.getDiscordChannelId().isEmpty()) {
                    logger.info("Sending to Discord channel: {}", schedule.getDiscordChannelId());
                    notificationService.sendToChannel(schedule.getDiscordChannelId(), discordMessage);
                }
            }
        } catch (Exception e) {
            logger.error("Error executing job: {}", e.getMessage(), e);
            result.setErrorMessage(e.getMessage());
            try {
                scheduleManagerService.updateResult(result);
                scheduleManagerService.deleteResultRows(result.getId()); // Clean up orphan result rows on failure
            } catch (Exception saveErr) {
                logger.error("Failed to save error result or cleanup result rows: {}", saveErr.getMessage());
            }
        }
    }
}
