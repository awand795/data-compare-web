package com.dbdiff.controller;

import com.dbdiff.model.SystemAlertSchedule;
import com.dbdiff.model.SystemMetrics;
import com.dbdiff.repository.SystemAlertScheduleRepository;
import com.dbdiff.service.SystemAlertSchedulerService;
import com.dbdiff.service.SystemMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SystemMonitorController {

    @Autowired
    private SystemMonitorService systemMonitorService;

    @Autowired
    private SystemAlertScheduleRepository scheduleRepository;

    @Autowired
    private SystemAlertSchedulerService schedulerService;

    @GetMapping("/system-monitor/metrics")
    public ResponseEntity<SystemMetrics> getMetrics() {
        return ResponseEntity.ok(systemMonitorService.getSystemMetrics());
    }

    @GetMapping("/system-alert-schedules")
    public ResponseEntity<List<SystemAlertSchedule>> getSchedules() {
        return ResponseEntity.ok(scheduleRepository.findAll());
    }

    @GetMapping("/system-alert-schedules/{id}")
    public ResponseEntity<SystemAlertSchedule> getScheduleById(@PathVariable String id) {
        SystemAlertSchedule s = scheduleRepository.findById(id);
        if (s == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(s);
    }

    @PostMapping("/system-alert-schedules")
    public ResponseEntity<SystemAlertSchedule> createSchedule(@RequestBody SystemAlertSchedule schedule) {
        SystemAlertSchedule saved = scheduleRepository.save(schedule);
        if (saved.isActive()) {
            schedulerService.scheduleSystemTask(saved);
        }
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/system-alert-schedules/{id}")
    public ResponseEntity<SystemAlertSchedule> updateSchedule(@PathVariable String id, @RequestBody SystemAlertSchedule schedule) {
        scheduleRepository.update(id, schedule);
        SystemAlertSchedule updated = scheduleRepository.findById(id);
        if (updated.isActive()) {
            schedulerService.scheduleSystemTask(updated);
        } else {
            schedulerService.cancelSystemTask(id);
        }
        return ResponseEntity.ok(updated);
    }

    @PutMapping("/system-alert-schedules/{id}/active")
    public ResponseEntity<Void> toggleActive(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        boolean active = body.getOrDefault("active", false);
        scheduleRepository.setActive(id, active);
        SystemAlertSchedule s = scheduleRepository.findById(id);
        if (s != null) {
            if (active) {
                schedulerService.scheduleSystemTask(s);
            } else {
                schedulerService.cancelSystemTask(id);
            }
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/system-alert-schedules/{id}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable String id) {
        schedulerService.cancelSystemTask(id);
        scheduleRepository.delete(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/system-alert-schedules/{id}/test")
    public ResponseEntity<Map<String, Object>> testSchedule(@PathVariable String id) {
        try {
            boolean sent = schedulerService.testSystemAlert(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Test alert notification sent successfully!"));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", ex.getMessage()));
        }
    }
}
