package com.dbdiff.controller;

import com.dbdiff.model.ScheduleConfig;
import com.dbdiff.model.ScheduleResult;
import com.dbdiff.model.ScheduleResultRow;
import com.dbdiff.service.DynamicSchedulerService;
import com.dbdiff.service.ScheduleManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedules")
@CrossOrigin(origins = "*")
public class ScheduleController {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleController.class);

    private final ScheduleManagerService scheduleManagerService;
    private final DynamicSchedulerService dynamicSchedulerService;

    @Autowired
    private org.springframework.core.task.TaskExecutor taskExecutor;

    @Autowired
    public ScheduleController(ScheduleManagerService scheduleManagerService, DynamicSchedulerService dynamicSchedulerService) {
        this.scheduleManagerService = scheduleManagerService;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    @GetMapping
    public ResponseEntity<List<ScheduleConfig>> getAllSchedules() {
        return ResponseEntity.ok(scheduleManagerService.getAllSchedules());
    }

    @PostMapping
    public ResponseEntity<?> createSchedule(@RequestBody ScheduleConfig config) {
        try {
            ScheduleConfig created = scheduleManagerService.createSchedule(config);
            if (created.isActive()) {
                dynamicSchedulerService.refreshSchedule(created.getId());
            }
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateSchedule(@PathVariable String id, @RequestBody ScheduleConfig config) {
        try {
            logger.info("Updating schedule {}, isActive: {}", id, config.isActive());
            ScheduleConfig updated = scheduleManagerService.updateSchedule(id, config);
            dynamicSchedulerService.refreshSchedule(updated.getId());
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            logger.error("Error updating schedule {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable String id) {
        try {
            dynamicSchedulerService.cancelSchedule(id);
            scheduleManagerService.deleteSchedule(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PatchMapping("/{id}/group")
    public ResponseEntity<?> updateGroup(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            String groupName = body.getOrDefault("groupName", "General");
            scheduleManagerService.updateGroupName(id, groupName);
            return ResponseEntity.ok(Map.of("success", true, "message", "Group updated successfully", "groupName", groupName));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @Autowired
    private com.dbdiff.repository.AppGroupRepository appGroupRepository;

    @PutMapping("/groups/rename")
    public ResponseEntity<?> renameGroup(@RequestBody Map<String, String> body) {
        String oldName = body.get("oldName");
        String newName = body.get("newName");
        try {
            int affected = scheduleManagerService.renameGroup(oldName, newName);
            if (appGroupRepository != null) {
                appGroupRepository.renameGroup("SCHEDULE_JOB", oldName, newName);
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "Renamed group successfully", "affected", affected));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to rename group"));
        }
    }

    @DeleteMapping("/groups/{groupName}")
    public ResponseEntity<?> deleteGroup(@PathVariable String groupName) {
        try {
            int affected = scheduleManagerService.deleteGroup(groupName);
            if (appGroupRepository != null) {
                appGroupRepository.deleteGroup("SCHEDULE_JOB", groupName);
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "Group deleted successfully and jobs moved to General", "affected", affected));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to delete group"));
        }
    }

    @GetMapping("/{id}/results")
    public ResponseEntity<List<ScheduleResult>> getScheduleResults(@PathVariable String id) {
        return ResponseEntity.ok(scheduleManagerService.getResultsForSchedule(id));
    }

    @DeleteMapping("/{id}/results")
    public ResponseEntity<?> clearScheduleResults(@PathVariable String id) {
        try {
            scheduleManagerService.clearResultsForSchedule(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @GetMapping("/results/{resultId}/rows")
    public ResponseEntity<List<ScheduleResultRow>> getResultRows(
            @PathVariable String resultId,
            @RequestParam(required = false) String tableName) {
        return ResponseEntity.ok(scheduleManagerService.getRowsForResult(resultId, tableName));
    }

    @PostMapping("/{id}/trigger")
    public ResponseEntity<?> triggerJob(@PathVariable String id) {
        try {
            // Gunakan managed TaskExecutor — bukan raw Thread — agar thread dikelola oleh Spring pool
            // dan tidak bisa dibuat tanpa batas oleh request concurrent
            taskExecutor.execute(() -> dynamicSchedulerService.executeCompareJob(id));
            return ResponseEntity.ok(Map.of("success", true, "message", "Job triggered successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }
}
