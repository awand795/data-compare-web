package com.dbdiff.controller;

import com.dbdiff.model.DataWarehouseDeployRequest;
import com.dbdiff.service.DataWarehouseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/dwh")
public class DataWarehouseController {

    @Autowired
    private DataWarehouseService dataWarehouseService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping(value = "/deploy", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter deployPipeline(@RequestBody DataWarehouseDeployRequest request) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes timeout
        
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
}
