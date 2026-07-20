package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.repository.ApiEndpointRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/api-builder")
public class ApiEndpointController {

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @GetMapping
    public ResponseEntity<List<ApiEndpoint>> getAll() {
        return ResponseEntity.ok(apiEndpointRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable String id) {
        Optional<ApiEndpoint> endpoint = apiEndpointRepository.findById(id);
        if (endpoint.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(endpoint.get());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ApiEndpoint apiEndpoint) {
        if (apiEndpoint.getId() == null || apiEndpoint.getId().isEmpty()) {
            apiEndpoint.setId(UUID.randomUUID().toString());
        }
        
        // Ensure path starts with /
        if (!apiEndpoint.getEndpointPath().startsWith("/")) {
            apiEndpoint.setEndpointPath("/" + apiEndpoint.getEndpointPath());
        }
        
        try {
            apiEndpointRepository.insert(apiEndpoint);
            return ResponseEntity.ok(apiEndpoint);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ApiEndpoint apiEndpoint) {
        apiEndpoint.setId(id);
        if (!apiEndpoint.getEndpointPath().startsWith("/")) {
            apiEndpoint.setEndpointPath("/" + apiEndpoint.getEndpointPath());
        }
        
        try {
            apiEndpointRepository.update(apiEndpoint);
            return ResponseEntity.ok(apiEndpoint);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            apiEndpointRepository.deleteById(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }
}
