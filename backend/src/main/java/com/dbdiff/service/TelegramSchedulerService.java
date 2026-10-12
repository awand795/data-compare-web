package com.dbdiff.service;

import com.dbdiff.model.TelegramLog;
import com.dbdiff.model.TelegramSchedule;
import com.dbdiff.repository.TelegramScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class TelegramSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramSchedulerService.class);

    private final TaskScheduler taskScheduler;
    private final TelegramScheduleRepository scheduleRepository;
    private final TelegramSenderService senderService;

    // Track active scheduled futures per schedule ID
    private final Map<String, List<ScheduledFuture<?>>> scheduledTasks = new ConcurrentHashMap<>();

    @Autowired
    public TelegramSchedulerService(TaskScheduler taskScheduler,
                                    TelegramScheduleRepository scheduleRepository,
                                    TelegramSenderService senderService) {
        this.taskScheduler = taskScheduler;
        this.scheduleRepository = scheduleRepository;
        this.senderService = senderService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initSchedules() {
        try {
            List<TelegramSchedule> activeSchedules = scheduleRepository.findAllActive();
            logger.info("Initializing {} active Telegram schedules...", activeSchedules.size());
            for (TelegramSchedule s : activeSchedules) {
                registerSchedule(s);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize Telegram schedules: {}", e.getMessage(), e);
        }
    }

    public synchronized void registerSchedule(TelegramSchedule s) {
        cancelSchedule(s.getId());

        if (!s.isActive() || s.getCronExpression() == null || s.getCronExpression().trim().isEmpty()) {
            return;
        }

        // Support multiple cron expressions separated by comma or newline
        String[] cronParts = s.getCronExpression().split("[,\\n]+");
        List<ScheduledFuture<?>> futures = new ArrayList<>();

        for (String rawCron : cronParts) {
            String cron = rawCron.trim();
            if (cron.isEmpty()) continue;

            try {
                CronTrigger trigger = new CronTrigger(cron);
                ScheduledFuture<?> future = taskScheduler.schedule(() -> executeScheduledTask(s.getId()), trigger);
                futures.add(future);
                logger.info("Registered Telegram schedule '{}' with cron '{}'", s.getName(), cron);
            } catch (Exception e) {
                logger.warn("Invalid cron expression '{}' for Telegram schedule '{}': {}", cron, s.getName(), e.getMessage());
            }
        }

        if (!futures.isEmpty()) {
            scheduledTasks.put(s.getId(), futures);
        }
    }

    public synchronized void cancelSchedule(String scheduleId) {
        List<ScheduledFuture<?>> futures = scheduledTasks.remove(scheduleId);
        if (futures != null) {
            for (ScheduledFuture<?> f : futures) {
                try {
                    f.cancel(false);
                } catch (Exception ignored) {}
            }
        }
    }

    public void reloadSchedule(String scheduleId) {
        TelegramSchedule s = scheduleRepository.findById(scheduleId);
        if (s != null) {
            registerSchedule(s);
        } else {
            cancelSchedule(scheduleId);
        }
    }

    public TelegramLog executeScheduledTask(String scheduleId) {
        TelegramSchedule s = scheduleRepository.findById(scheduleId);
        if (s == null) {
            logger.warn("Telegram schedule not found during execution: {}", scheduleId);
            return null;
        }

        logger.info("Executing Telegram schedule: '{}' [{}]", s.getName(), s.getId());
        LocalDateTime now = LocalDateTime.now();

        try {
            TelegramLog log = senderService.executeAndSend(
                    s.getId(),
                    s.getName(),
                    s.getBotId(),
                    s.getConnectionId(),
                    s.getSqlQuery(),
                    s.getTemplateMessage(),
                    s.getRecipientMode(),
                    s.getMasterRecipientIds(),
                    s.getPhoneColumnName(),
                    s.getMaxRows(),
                    "SCHEDULER"
            );

            scheduleRepository.updateLastRun(s.getId(), now, log.getStatus(), log.getDetails());
            return log;
        } catch (Exception e) {
            logger.error("Error executing Telegram schedule '{}': {}", s.getName(), e.getMessage(), e);
            scheduleRepository.updateLastRun(s.getId(), now, "FAILED", e.getMessage());
            return null;
        }
    }
}
