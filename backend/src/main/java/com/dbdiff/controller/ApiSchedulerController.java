package com.dbdiff.controller;

import com.dbdiff.model.ApiSchedulerConfig;
import com.dbdiff.repository.ApiSchedulerRepository;
import com.dbdiff.service.ApiSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/api-schedulers")
@CrossOrigin(origins = "*")
public class ApiSchedulerController {

    private static final Logger logger = LoggerFactory.getLogger(ApiSchedulerController.class);

    private final ApiSchedulerRepository repository;
    private final ApiSchedulerService service;

    @Autowired
    public ApiSchedulerController(ApiSchedulerRepository repository, ApiSchedulerService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ApiSchedulerConfig>> getAllSchedulers() {
        return ResponseEntity.ok(repository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getSchedulerById(@PathVariable String id) {
        Optional<ApiSchedulerConfig> opt = repository.findById(id);
        if (opt.isPresent()) {
            return ResponseEntity.ok(opt.get());
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> createScheduler(@RequestBody ApiSchedulerConfig config) {
        try {
            if (config.getId() == null || config.getId().trim().isEmpty()) {
                config.setId(UUID.randomUUID().toString());
            }
            repository.insert(config);
            if (config.isActive()) {
                service.refreshSchedule(config.getId());
            }
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            logger.error("Failed to create API scheduler: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateScheduler(@PathVariable String id, @RequestBody ApiSchedulerConfig config) {
        try {
            config.setId(id);
            repository.update(config);
            service.refreshSchedule(id);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            logger.error("Failed to update API scheduler {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PatchMapping("/{id}/group")
    public ResponseEntity<?> updateGroupName(@PathVariable String id, @RequestBody Map<String, String> body) {
        String groupName = body.get("groupName");
        try {
            repository.updateGroupName(id, groupName);
            Optional<ApiSchedulerConfig> updated = repository.findById(id);
            return ResponseEntity.ok(updated.orElse(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PutMapping("/groups/rename")
    public ResponseEntity<?> renameGroup(@RequestBody Map<String, String> body) {
        String oldName = body.get("oldName");
        String newName = body.get("newName");
        try {
            int affected = repository.renameGroup(oldName, newName);
            return ResponseEntity.ok(Map.of("success", true, "message", "Renamed group successfully", "affected", affected));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to rename group"));
        }
    }

    @DeleteMapping("/groups/{groupName}")
    public ResponseEntity<?> deleteGroup(@PathVariable String groupName) {
        try {
            int affected = repository.deleteGroup(groupName);
            return ResponseEntity.ok(Map.of("success", true, "message", "Group deleted successfully and items moved to General", "affected", affected));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to delete group"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteScheduler(@PathVariable String id) {
        try {
            service.stopSchedule(id);
            repository.deleteById(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PostMapping("/test")
    public ResponseEntity<?> testEndpoint(@RequestBody ApiSchedulerConfig config) {
        try {
            Map<String, Object> result = service.testHttpEndpoint(config);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "statusCode", 500,
                "durationMs", 0,
                "body", "Execution Error: " + (e.getMessage() != null ? e.getMessage() : e.toString()),
                "headers", Map.of()
            ));
        }
    }

    @PostMapping("/{id}/run-now")
    public ResponseEntity<?> runNow(@PathVariable String id) {
        try {
            service.executeAndSaveSchedule(id);
            Optional<ApiSchedulerConfig> updated = repository.findById(id);
            return ResponseEntity.ok(updated.orElse(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    // =========================================================================
    // AUTOMATED MATERIALIZED VIEW (AUTO-MV) EXTRACTOR ENDPOINTS
    // =========================================================================

    @GetMapping("/mv-pipelines/tables")
    public ResponseEntity<?> getExistingTables(@RequestParam(required = false) String connectionId) {
        try {
            return ResponseEntity.ok(service.getExistingTables(connectionId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @GetMapping("/mv-pipelines/inspect")
    public ResponseEntity<?> inspectJsonSchema(
            @RequestParam(defaultValue = "api_test") String sourceTable,
            @RequestParam(required = false) String kodeData,
            @RequestParam(required = false) String connectionId) {
        try {
            return ResponseEntity.ok(service.inspectJsonSchema(sourceTable, kodeData, connectionId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @PostMapping("/mv-pipelines/deploy")
    public ResponseEntity<?> deployAutoMvPipeline(@RequestBody Map<String, Object> req) {
        try {
            return ResponseEntity.ok(service.deployAutoMvPipeline(req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @GetMapping("/mv-pipelines")
    public ResponseEntity<?> getAllAutoMvPipelines(@RequestParam(required = false) String connectionId) {
        try {
            return ResponseEntity.ok(service.getAllAutoMvPipelines(connectionId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    @DeleteMapping("/mv-pipelines/{mvName}")
    public ResponseEntity<?> deleteAutoMvPipeline(
            @PathVariable String mvName,
            @RequestParam(required = false) String connectionId) {
        try {
            return ResponseEntity.ok(service.deleteAutoMvPipeline(mvName, connectionId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }
}
