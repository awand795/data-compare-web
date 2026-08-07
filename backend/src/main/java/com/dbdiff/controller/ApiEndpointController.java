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

import com.dbdiff.model.ApiShareToken;
import com.dbdiff.repository.ApiShareTokenRepository;

import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.service.ConnectionManagerService;
import com.dbdiff.service.ApiParameterValidator;

@RestController
@RequestMapping("/api/api-builder")
public class ApiEndpointController {

    @Autowired
    private ApiParameterValidator apiParameterValidator;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;
    
    @Autowired
    private ApiShareTokenRepository apiShareTokenRepository;
    
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

        ApiParameterValidator.ValidationResult result = apiParameterValidator.validate(endpoint.getParameters(), params);
        if (!result.isValid()) {
            return ResponseEntity.badRequest().body(Map.of("errors", result.getErrors()));
        }
        params = result.getParams();

        ConnectionDetails conn = connectionRepository.findById(endpoint.getConnectionId());
        if (conn == null) {
             return ResponseEntity.status(500).body(Map.of("error", "Connection not found"));
        }
        try {
            javax.sql.DataSource dataSource = connectionManagerService.getDataSource(conn);
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource);

            String sql = endpoint.getSqlQuery();
            if (endpoint.isEnablePagination()) {
                int limit = 10;
                int offset = 0;
                int page = 1;

                if (params.containsKey("limit")) {
                    String val = params.get("limit").toString();
                    if (!val.matches("-?\\d+")) return ResponseEntity.badRequest().body(Map.of("errors", List.of("Parameter 'limit' must be a valid integer")));
                    limit = Integer.parseInt(val);
                } else if (params.containsKey("size")) {
                    String val = params.get("size").toString();
                    if (!val.matches("-?\\d+")) return ResponseEntity.badRequest().body(Map.of("errors", List.of("Parameter 'size' must be a valid integer")));
                    limit = Integer.parseInt(val);
                }
                
                if (params.containsKey("page")) {
                    String val = params.get("page").toString();
                    if (!val.matches("-?\\d+")) return ResponseEntity.badRequest().body(Map.of("errors", List.of("Parameter 'page' must be a valid integer")));
                    page = Integer.parseInt(val);
                    if (page < 1) page = 1;
                    offset = (page - 1) * limit;
                } else if (params.containsKey("offset")) {
                    String val = params.get("offset").toString();
                    if (!val.matches("-?\\d+")) return ResponseEntity.badRequest().body(Map.of("errors", List.of("Parameter 'offset' must be a valid integer")));
                    offset = Integer.parseInt(val);
                    page = (limit > 0 ? (offset / limit) + 1 : 1);
                }
                
                // Clean SQL query by removing trailing semicolon if present
                String cleanSql = sql.trim();
                if (cleanSql.endsWith(";")) {
                    cleanSql = cleanSql.substring(0, cleanSql.length() - 1).trim();
                }

                // Calculate total records for pagination metadata
                int totalRecords = 0;
                try {
                    String countSql = "SELECT COUNT(*) FROM (" + cleanSql + ") AS _total_count_subquery";
                    Integer count = jdbcTemplate.queryForObject(countSql, params, Integer.class);
                    if (count != null) totalRecords = count;
                } catch (Exception ex) {
                    // Ignore count fallback if dialect query fails
                }

                String paginatedSql;
                if (conn.getType() != null && conn.getType().equalsIgnoreCase("SQLSERVER")) {
                    paginatedSql = cleanSql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
                } else if (conn.getType() != null && conn.getType().equalsIgnoreCase("ORACLE")) {
                    paginatedSql = cleanSql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
                } else {
                    paginatedSql = cleanSql + " LIMIT " + limit + " OFFSET " + offset;
                }

                List<Map<String, Object>> data = jdbcTemplate.queryForList(paginatedSql, params);

                int totalPages = limit > 0 ? (int) Math.ceil((double) totalRecords / limit) : (totalRecords > 0 ? 1 : 0);

                Map<String, Object> paginationMeta = new HashMap<>();
                paginationMeta.put("current_page", page);
                paginationMeta.put("limit", limit);
                paginationMeta.put("offset", offset);
                paginationMeta.put("total_records", totalRecords);
                paginationMeta.put("total_pages", totalPages);

                Map<String, Object> responseBody = new HashMap<>();
                responseBody.put("data", data);
                responseBody.put("pagination", paginationMeta);

                return ResponseEntity.ok(responseBody);
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
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to create endpoint"));
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
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to update endpoint"));
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

    @PostMapping("/{id}/share")
    public ResponseEntity<?> share(@PathVariable String id) {
        Optional<ApiEndpoint> endpointOpt = apiEndpointRepository.findById(id);
        if (endpointOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        ApiShareToken shareToken = new ApiShareToken();
        String tokenId = UUID.randomUUID().toString();
        shareToken.setId(tokenId);
        shareToken.setApiEndpointId(id);
        shareToken.setToken(tokenId);
        shareToken.setUsed(false);
        
        try {
            apiShareTokenRepository.deleteByApiEndpointId(id);
            apiShareTokenRepository.insert(shareToken);
            Map<String, String> response = new HashMap<>();
            response.put("token", tokenId);
            response.put("shareUrl", "/share/" + tokenId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
