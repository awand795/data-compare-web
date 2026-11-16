package com.dbdiff.controller;

import com.dbdiff.model.ClickHouseOptimizeSchedule;
import com.dbdiff.repository.ClickHouseOptimizeScheduleRepository;
import com.dbdiff.service.ClickHouseOptimizeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dwh/optimize-schedules")
public class ClickHouseOptimizeController {

    @Autowired
    private ClickHouseOptimizeScheduleRepository scheduleRepo;

    @Autowired
    private ClickHouseOptimizeService optimizeService;

    @GetMapping
    public ResponseEntity<List<ClickHouseOptimizeSchedule>> getAll() {
        List<ClickHouseOptimizeSchedule> list = scheduleRepo.findAll();
        for (ClickHouseOptimizeSchedule s : list) {
            s.setNextRun(optimizeService.computeNextRun(s.getCronExpression(), s.isActive()));
        }
        return ResponseEntity.ok(list);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ClickHouseOptimizeSchedule schedule) {
        try {
            String normalized = optimizeService.normalizeCron(schedule.getCronExpression());
            optimizeService.validateCron(normalized);
            schedule.setCronExpression(normalized);
            ClickHouseOptimizeSchedule saved = scheduleRepo.save(schedule);
            if (saved.isActive()) {
                optimizeService.scheduleTask(saved);
            }
            saved.setNextRun(optimizeService.computeNextRun(saved.getCronExpression(), saved.isActive()));
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid schedule/cron: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ClickHouseOptimizeSchedule schedule) {
        try {
            String normalized = optimizeService.normalizeCron(schedule.getCronExpression());
            optimizeService.validateCron(normalized);
            schedule.setCronExpression(normalized);
            scheduleRepo.update(id, schedule);
            ClickHouseOptimizeSchedule updated = scheduleRepo.findById(id);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            if (updated.isActive()) {
                optimizeService.scheduleTask(updated);
            } else {
                optimizeService.cancelTask(id);
            }
            updated.setNextRun(optimizeService.computeNextRun(updated.getCronExpression(), updated.isActive()));
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid schedule/cron: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}/active")
    public ResponseEntity<?> toggleActive(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        boolean active = body.getOrDefault("active", false);
        scheduleRepo.setActive(id, active);
        ClickHouseOptimizeSchedule s = scheduleRepo.findById(id);
        if (s == null) {
            return ResponseEntity.notFound().build();
        }
        if (active) {
            optimizeService.scheduleTask(s);
        } else {
            optimizeService.cancelTask(id);
        }
        s.setNextRun(optimizeService.computeNextRun(s.getCronExpression(), s.isActive()));
        return ResponseEntity.ok(s);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        optimizeService.cancelTask(id);
        scheduleRepo.delete(id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> runNow(@PathVariable String id) {
        try {
            boolean started = optimizeService.triggerManualRun(id);
            if (!started) {
                return ResponseEntity.ok(Map.of("status", "already_running", "message", "Optimize is already running for this schedule."));
            }
            return ResponseEntity.ok(Map.of("status", "started", "message", "ClickHouse Version Cleanup started in background."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
