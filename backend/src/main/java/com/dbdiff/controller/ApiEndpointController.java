package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.repository.ApiEndpointRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.service.ConnectionManagerService;

@RestController
@RequestMapping("/api/api-builder")
public class ApiEndpointController {

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;
    
    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionManagerService connectionManagerService;

    public static class TestRequest {
        public ApiEndpoint api;
        public Map<String, Object> params;
    }

    @PostMapping("/test-query")
    public ResponseEntity<?> testQuery(@RequestBody TestRequest request) {
        ApiEndpoint endpoint = request.api;
        Map<String, Object> params = request.params;
        if (params == null) params = new HashMap<>();

        ConnectionDetails conn = connectionRepository.findById(endpoint.getConnectionId());
        if (conn == null) {
             return ResponseEntity.status(500).body(Map.of("error", "Connection not found"));
        }
        try {
            javax.sql.DataSource dataSource = connectionManagerService.getDataSource(conn);
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource);

            String sql = endpoint.getSqlQuery();
            if (endpoint.isEnablePagination()) {
                int limit = 100;
                int offset = 0;
                if (params.containsKey("limit")) limit = Integer.parseInt(params.get("limit").toString());
                else if (params.containsKey("size")) limit = Integer.parseInt(params.get("size").toString());
                
                if (params.containsKey("offset")) offset = Integer.parseInt(params.get("offset").toString());
                else if (params.containsKey("page")) {
                    int page = Integer.parseInt(params.get("page").toString());
                    offset = (page > 0 ? page - 1 : 0) * limit;
                }
                
                if (conn.getDbType() != null && conn.getDbType().equalsIgnoreCase("SQLSERVER")) {
                    sql += " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
                } else if (conn.getDbType() != null && conn.getDbType().equalsIgnoreCase("ORACLE")) {
                    sql += " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
                } else {
                    sql += " LIMIT " + limit + " OFFSET " + offset;
                }
            }

            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, params);
            return ResponseEntity.ok(results);
        } catch(Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

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
