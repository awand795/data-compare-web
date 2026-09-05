package com.dbdiff.controller;

import com.dbdiff.model.DataWarehouseDeployRequest;
import com.dbdiff.service.DataWarehouseService;
import com.dbdiff.repository.ConnectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;

@RestController
@RequestMapping("/api/dwh")
public class DataWarehouseController {

    @Autowired
    private DataWarehouseService dataWarehouseService;
    
    @Autowired
    private ConnectionRepository connectionRepository;

    private final ExecutorService executor = Executors.newFixedThreadPool(5); // Fixed pool to prevent OOM

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    @PostMapping(value = "/deploy", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter deployPipeline(@RequestBody DataWarehouseDeployRequest request) {
        // Fetch full connection details from the database so that passwords and metadata are included
        if (request.getSourceConnections() != null && !request.getSourceConnections().isEmpty()) {
            java.util.List<com.dbdiff.model.ConnectionDetails> enrichedList = new java.util.ArrayList<>();
            for (com.dbdiff.model.ConnectionDetails c : request.getSourceConnections()) {
                if (c != null && c.getId() != null) {
                    com.dbdiff.model.ConnectionDetails fullConn = connectionRepository.findById(c.getId());
                    enrichedList.add(fullConn != null ? fullConn : c);
                } else {
                    enrichedList.add(c);
                }
            }
            request.setSourceConnections(enrichedList);
        }
        if (request.getSourceConnection() != null && request.getSourceConnection().getId() != null) {
            request.setSourceConnection(connectionRepository.findById(request.getSourceConnection().getId()));
        }
        if (request.getTargetConnection() != null && request.getTargetConnection().getId() != null) {
            request.setTargetConnection(connectionRepository.findById(request.getTargetConnection().getId()));
        }
        
        SseEmitter emitter = new SseEmitter(7_200_000L); // 2 hours timeout
        
        executor.execute(() -> {
            try {
                dataWarehouseService.deployPipeline(request, emitter);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }

    @GetMapping("/pipelines")
    public ResponseEntity<?> getPipelines() {
        return ResponseEntity.ok(dataWarehouseService.getPipelinesStatus());
    }

    @PostMapping("/pipelines/{connectorName}/action")
    public ResponseEntity<?> manageConnector(@PathVariable String connectorName, @RequestParam String action) {
        dataWarehouseService.manageConnector(connectorName, action);
        return ResponseEntity.ok(java.util.Map.of("status", "success", "action", action));
    }

    @DeleteMapping("/pipelines/{connectorName}")
    public ResponseEntity<?> deleteConnector(@PathVariable String connectorName) {
        dataWarehouseService.deleteConnector(connectorName);
        return ResponseEntity.ok(java.util.Map.of("status", "deleted"));
    }

    @DeleteMapping("/pipelines/group/{deployId}")
    public ResponseEntity<?> deletePipeline(@PathVariable String deployId) {
        dataWarehouseService.deletePipeline(deployId);
        return ResponseEntity.ok(java.util.Map.of("status", "deleted"));
    }

    @GetMapping("/pipelines/query/{deployId}")
    public ResponseEntity<?> getOriginalQuery(@PathVariable String deployId) {
        String query = dataWarehouseService.getOriginalQuery(deployId);
        if (query == null) {
            return ResponseEntity.status(404).body(java.util.Map.of("error", "Query not found"));
        }
        return ResponseEntity.ok(java.util.Map.of("query", query));
    }

    @GetMapping("/pipelines/metadata/{deployId}")
    public ResponseEntity<?> getPipelineMetadata(@PathVariable String deployId) {
        java.util.Map<String, Object> meta = dataWarehouseService.getPipelineMetadata(deployId);
        if (meta == null) return ResponseEntity.status(404).body(java.util.Map.of("error", "Not found"));
        return ResponseEntity.ok(meta);
    }

    @GetMapping("/pipelines/sources/{deployId}")
    public ResponseEntity<?> getPipelineSources(@PathVariable String deployId) {
        return ResponseEntity.ok(dataWarehouseService.getPipelineSources(deployId));
    }

    @PostMapping(value = "/pipelines/add-source/{deployId}", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter addSourceToPipeline(@PathVariable String deployId, @RequestBody java.util.Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(7_200_000L); // 2 hours timeout
        executor.execute(() -> {
            try {
                Object idsObj = body.get("sourceConnectionIds");
                java.util.List<String> connIds = new java.util.ArrayList<>();
                if (idsObj instanceof java.util.List) {
                    for (Object item : (java.util.List<?>) idsObj) {
                        if (item != null) connIds.add(item.toString());
                    }
                } else if (idsObj != null) {
                    connIds.add(idsObj.toString());
                }
                dataWarehouseService.addSourceToPipeline(deployId, connIds, emitter);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @PostMapping(value = "/pipelines/backfill-cdc/{deployId}", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter backfillCdcPipeline(
            @PathVariable String deployId) {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(1800000L);
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.initialize();
        executor.execute(() -> {
            try {
                dataWarehouseService.backfillCdcFromPostgres(deployId, emitter);
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().data("DONE"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().data("ERROR: " + e.getMessage()));
                    emitter.completeWithError(e);
                } catch (Exception ex) {}
            }
        });
        return emitter;
    }

    @PostMapping(value = "/pipelines/update-query/{deployId}", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter updatePipelineQuery(
            @PathVariable String deployId,
            @RequestBody java.util.Map<String, String> body) {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(1800000L);
        String newQuery = body.get("query");
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.initialize();
        executor.execute(() -> {
            try {
                dataWarehouseService.updatePipelineQuery(deployId, newQuery, emitter);
            } catch (Exception e) {
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        });
        return emitter;
    }

    @PostMapping("/pipelines/rename/{deployId}")
    public ResponseEntity<?> renamePipeline(@PathVariable String deployId, @RequestParam String newName) {
        dataWarehouseService.renamePipeline(deployId, newName);
        return ResponseEntity.ok(java.util.Map.of("status", "success", "newName", newName));
    }

    @GetMapping("/pipelines/{connectorName}/config")
    public ResponseEntity<?> getConnectorConfig(@PathVariable String connectorName) {
        return ResponseEntity.ok(dataWarehouseService.getConnectorConfig(connectorName));
    }

    @PutMapping("/pipelines/{connectorName}/config")
    public ResponseEntity<?> updateConnectorConfig(@PathVariable String connectorName, @RequestBody java.util.Map<String, Object> config) {
        dataWarehouseService.updateConnectorConfig(connectorName, config);
        return ResponseEntity.ok(java.util.Map.of("status", "success"));
    }

    @GetMapping("/pipelines/{connectorName}/peek")
    public ResponseEntity<?> peekTopicData(@PathVariable String connectorName) {
        return ResponseEntity.ok(dataWarehouseService.peekTopicData(connectorName));
    }

    @GetMapping("/pipelines/progress/{deployId}")
    public ResponseEntity<?> getSnapshotProgress(@PathVariable String deployId) {
        return ResponseEntity.ok(dataWarehouseService.getSnapshotProgress(deployId));
    }

    @GetMapping("/replication-slots")
    public ResponseEntity<?> getReplicationSlots(@RequestParam(required = false) String connectionId) {
        return ResponseEntity.ok(dataWarehouseService.getReplicationSlots(connectionId));
    }

    @PostMapping("/replication-slots/cleanup")
    public ResponseEntity<?> cleanupReplicationSlots(
            @RequestParam(required = false) String connectionId,
            @RequestParam(required = false) String slotName,
            @RequestParam(required = false, defaultValue = "true") Boolean inactiveOnly) {
        java.util.List<String> dropped = dataWarehouseService.cleanupReplicationSlots(connectionId, slotName, inactiveOnly);
        return ResponseEntity.ok(java.util.Map.of("status", "success", "droppedSlots", dropped));
    }
}
