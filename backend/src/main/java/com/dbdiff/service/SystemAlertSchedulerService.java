package com.dbdiff.service;

import com.dbdiff.model.SystemAlertSchedule;
import com.dbdiff.model.WalAlertSchedule;
import com.dbdiff.repository.SystemAlertScheduleRepository;
import com.dbdiff.repository.WalAlertScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class SystemAlertSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(SystemAlertSchedulerService.class);

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private SystemAlertScheduleRepository systemScheduleRepo;

    @Autowired
    private WalAlertScheduleRepository walScheduleRepo;

    @Autowired
    private SystemMonitorService systemMonitorService;

    @Autowired
    private WalAlertService walAlertService;

    private final Map<String, ScheduledFuture<?>> systemScheduledTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> walScheduledTasks = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void initSchedulers() {
        logger.info("Initializing System & WAL Alert Schedulers...");
        refreshAllSystemSchedules();
        refreshAllWalSchedules();
    }

    public synchronized void refreshAllSystemSchedules() {
        // Cancel existing
        systemScheduledTasks.forEach((id, future) -> {
            if (future != null) future.cancel(false);
        });
        systemScheduledTasks.clear();

        for (SystemAlertSchedule s : systemScheduleRepo.findAll()) {
            if (s.isActive()) {
                scheduleSystemTask(s);
            }
        }
    }

    public synchronized void refreshAllWalSchedules() {
        // Cancel existing
        walScheduledTasks.forEach((id, future) -> {
            if (future != null) future.cancel(false);
        });
        walScheduledTasks.clear();

        for (WalAlertSchedule s : walScheduleRepo.findAll()) {
            if (s.isActive()) {
                scheduleWalTask(s);
            }
        }
    }

    public synchronized void scheduleSystemTask(SystemAlertSchedule s) {
        if (s == null || s.getId() == null) return;
        ScheduledFuture<?> existing = systemScheduledTasks.remove(s.getId());
        if (existing != null) existing.cancel(false);

        if (!s.isActive()) return;

        try {
            String cron = normalizeCron(s.getCronExpression());
            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                runSystemAlertCheck(s.getId());
            }, new CronTrigger(cron));

            systemScheduledTasks.put(s.getId(), future);
            logger.info("Scheduled System Alert job [{}] with cron: {}", s.getName(), cron);
        } catch (Exception ex) {
            logger.error("Failed to schedule System Alert job [{}]: {}", s.getName(), ex.getMessage());
        }
    }

    public synchronized void scheduleWalTask(WalAlertSchedule s) {
        if (s == null || s.getId() == null) return;
        ScheduledFuture<?> existing = walScheduledTasks.remove(s.getId());
        if (existing != null) existing.cancel(false);

        if (!s.isActive()) return;

        try {
            String cron = normalizeCron(s.getCronExpression());
            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                runWalAlertCheck(s.getId());
            }, new CronTrigger(cron));

            walScheduledTasks.put(s.getId(), future);
            logger.info("Scheduled WAL Alert job [{}] with cron: {}", s.getName(), cron);
        } catch (Exception ex) {
            logger.error("Failed to schedule WAL Alert job [{}]: {}", s.getName(), ex.getMessage());
        }
    }

    public synchronized void cancelSystemTask(String id) {
        ScheduledFuture<?> future = systemScheduledTasks.remove(id);
        if (future != null) future.cancel(false);
    }

    public synchronized void cancelWalTask(String id) {
        ScheduledFuture<?> future = walScheduledTasks.remove(id);
        if (future != null) future.cancel(false);
    }

    public void runSystemAlertCheck(String scheduleId) {
        try {
            SystemAlertSchedule s = systemScheduleRepo.findById(scheduleId);
            if (s == null || !s.isActive()) return;

            // Check Cooldown
            if (s.getLastAlertTime() != null && s.getCooldownMinutes() > 0) {
                LocalDateTime cooldownUntil = s.getLastAlertTime().plusMinutes(s.getCooldownMinutes());
                if (LocalDateTime.now().isBefore(cooldownUntil)) {
                    systemScheduleRepo.updateStatusAndLastRun(scheduleId, "COOLDOWN");
                    return;
                }
            }

            boolean alerted = systemMonitorService.evaluateAndSendAlert(s, false);
            if (alerted) {
                systemScheduleRepo.updateAlertTime(scheduleId);
                systemScheduleRepo.updateStatusAndLastRun(scheduleId, "ALERT_SENT");
            } else {
                systemScheduleRepo.updateStatusAndLastRun(scheduleId, "OK");
            }
        } catch (Exception ex) {
            logger.error("Error running system alert check for schedule {}", scheduleId, ex);
            systemScheduleRepo.updateStatusAndLastRun(scheduleId, "ERROR: " + ex.getMessage());
        }
    }

    public void runWalAlertCheck(String scheduleId) {
        try {
            WalAlertSchedule s = walScheduleRepo.findById(scheduleId);
            if (s == null || !s.isActive()) return;

            boolean alerted = walAlertService.evaluateAndSendAlert(s, false);
            if (alerted) {
                walScheduleRepo.updateAlertTime(scheduleId);
                walScheduleRepo.updateStatusAndLastRun(scheduleId, "ALERT_SENT");
            } else {
                walScheduleRepo.updateStatusAndLastRun(scheduleId, "OK");
            }
        } catch (Exception ex) {
            logger.error("Error running WAL alert check for schedule {}", scheduleId, ex);
            walScheduleRepo.updateStatusAndLastRun(scheduleId, "ERROR: " + ex.getMessage());
        }
    }

    public boolean testSystemAlert(String scheduleId) {
        SystemAlertSchedule s = systemScheduleRepo.findById(scheduleId);
        if (s == null) throw new RuntimeException("Schedule not found");
        return systemMonitorService.evaluateAndSendAlert(s, true);
    }

    public boolean testWalAlert(String scheduleId) {
        WalAlertSchedule s = walScheduleRepo.findById(scheduleId);
        if (s == null) throw new RuntimeException("Schedule not found");
        return walAlertService.evaluateAndSendAlert(s, true);
    }

    private String normalizeCron(String cron) {
        if (cron == null) return "0 0 * * * *";
        String trimmed = cron.trim();
        if (trimmed.split("\\s+").length == 5) {
            return "0 " + trimmed;
        }
        return trimmed;
    }
}
