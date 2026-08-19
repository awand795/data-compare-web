package com.dbdiff.controller;

import com.dbdiff.model.WalAlertSchedule;
import com.dbdiff.repository.WalAlertScheduleRepository;
import com.dbdiff.service.SystemAlertSchedulerService;
import com.dbdiff.service.WalAlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WalAlertScheduleController {

    @Autowired
    private WalAlertScheduleRepository walScheduleRepo;

    @Autowired
    private SystemAlertSchedulerService schedulerService;

    @Autowired
    private WalAlertService walAlertService;

    @GetMapping("/wal-alert-schedules")
    public ResponseEntity<List<WalAlertSchedule>> getSchedules() {
        return ResponseEntity.ok(walScheduleRepo.findAll());
    }

    @GetMapping("/wal-alert-schedules/{id}")
    public ResponseEntity<WalAlertSchedule> getScheduleById(@PathVariable String id) {
        WalAlertSchedule s = walScheduleRepo.findById(id);
        if (s == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(s);
    }

    @PostMapping("/wal-alert-schedules")
    public ResponseEntity<WalAlertSchedule> createSchedule(@RequestBody WalAlertSchedule schedule) {
        WalAlertSchedule saved = walScheduleRepo.save(schedule);
        if (saved.isActive()) {
            schedulerService.scheduleWalTask(saved);
        }
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/wal-alert-schedules/{id}")
    public ResponseEntity<WalAlertSchedule> updateSchedule(@PathVariable String id, @RequestBody WalAlertSchedule schedule) {
        walScheduleRepo.update(id, schedule);
        WalAlertSchedule updated = walScheduleRepo.findById(id);
        if (updated.isActive()) {
            schedulerService.scheduleWalTask(updated);
        } else {
            schedulerService.cancelWalTask(id);
        }
        return ResponseEntity.ok(updated);
    }

    @PutMapping("/wal-alert-schedules/{id}/active")
    public ResponseEntity<Void> toggleActive(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        boolean active = body.getOrDefault("active", false);
        walScheduleRepo.setActive(id, active);
        WalAlertSchedule s = walScheduleRepo.findById(id);
        if (s != null) {
            if (active) {
                schedulerService.scheduleWalTask(s);
            } else {
                schedulerService.cancelWalTask(id);
            }
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/wal-alert-schedules/{id}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable String id) {
        schedulerService.cancelWalTask(id);
        walScheduleRepo.delete(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/wal-alert-schedules/{id}/test")
    public ResponseEntity<Map<String, Object>> testSchedule(@PathVariable String id) {
        try {
            boolean sent = schedulerService.testWalAlert(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Test WAL alert notification sent successfully!"));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", ex.getMessage()));
        }
    }
}
