package com.dbdiff.controller;

import com.dbdiff.model.DataWarehouseDeployRequest;
import com.dbdiff.service.DataWarehouseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dwh")
public class DataWarehouseController {

    @Autowired
    private DataWarehouseService dataWarehouseService;

    @PostMapping("/deploy")
    public ResponseEntity<?> deployPipeline(@RequestBody DataWarehouseDeployRequest request) {
        try {
            String result = dataWarehouseService.deployPipeline(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}
