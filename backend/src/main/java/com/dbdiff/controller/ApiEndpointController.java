package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.repository.ApiEndpointRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
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

            // ── Raw SQL Condition Support (Opsi 1) ──────────────────────────────────
            String rawCondition = null;
            for (String key : new String[]{"where_condition", "raw_sql", "condition", "whereCondition"}) {
                if (params.containsKey(key) && params.get(key) != null) {
                    rawCondition = params.get(key).toString().trim();
                    break;
                }
            }
            
            // Remove raw condition keys from params so NamedParameterJdbcTemplate never expects them
            params.remove("where_condition");
            params.remove("raw_sql");
            params.remove("condition");
            params.remove("whereCondition");

            boolean hasRawPlaceholder = sql.contains(":where_condition") || sql.contains("{{where_condition}}")
                                     || sql.contains(":raw_sql") || sql.contains("{{raw_sql}}");

            if (rawCondition != null && !rawCondition.isEmpty()) {
                if (hasRawPlaceholder) {
                    sql = sql.replace(":where_condition", rawCondition)
                             .replace("{{where_condition}}", rawCondition)
                             .replace(":raw_sql", rawCondition)
                             .replace("{{raw_sql}}", rawCondition);
                } else if (endpoint.isAllowRawSql()) {
                    String trimmed = sql.trim();
                    if (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
                    if (trimmed.toUpperCase().contains(" WHERE ")) {
                        sql = trimmed + " AND (" + rawCondition + ")";
                    } else {
                        sql = trimmed + " WHERE " + rawCondition;
                    }
                }
            } else {
                // Replace any placeholders with 1=1 if no condition supplied
                sql = sql.replace(":where_condition", "1=1")
                         .replace("{{where_condition}}", "1=1")
                         .replace(":raw_sql", "1=1")
                         .replace("{{raw_sql}}", "1=1");
            }
            // ───────────────────────────────────────────────────────────────────────

            // ── Structured JSON Filter Support (Opsi 2) ─────────────────────────────
            if (sql.contains("{{filters}}")) {
                Object filtersObj = params.remove("filters");
                String builtClause = buildFilterClause(filtersObj, params);
                sql = sql.replace("{{filters}}", builtClause);
            }
            // ───────────────────────────────────────────────────────────────────────

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

                int totalPages = limit > 0 ? (int) Math.ceil((double) totalRecords / limit) : (totalRecords > 0 ? 1 : 0);

                final int finalPage = page;
                final int finalLimit = limit;
                final int finalOffset = offset;
                final int finalTotalRecords = totalRecords;
                final int finalTotalPages = totalPages;
                final String finalPaginatedSql = paginatedSql;
                final Map<String, Object> finalParams = params;

                org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody stream = out -> {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    try (com.fasterxml.jackson.core.JsonGenerator gen = mapper.getFactory().createGenerator(out, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
                        gen.writeStartObject(); // {

                        // Pagination metadata
                        gen.writeObjectFieldStart("pagination");
                        gen.writeNumberField("current_page", finalPage);
                        gen.writeNumberField("limit", finalLimit);
                        gen.writeNumberField("offset", finalOffset);
                        gen.writeNumberField("total_records", finalTotalRecords);
                        gen.writeNumberField("total_pages", finalTotalPages);
                        gen.writeEndObject();

                        // Data array start
                        gen.writeArrayFieldStart("data");

                        final String[] colNamesRef = new String[1];
                        final int[] colCountRef = new int[1];
                        final int[] rowCount = new int[]{0};

                        jdbcTemplate.query(finalPaginatedSql, finalParams, (java.sql.ResultSet rs) -> {
                            try {
                                if (colNamesRef[0] == null) {
                                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                                    colCountRef[0] = meta.getColumnCount();
                                    String[] cols = new String[colCountRef[0]];
                                    for (int i = 1; i <= colCountRef[0]; i++) {
                                        cols[i - 1] = meta.getColumnLabel(i);
                                    }
                                    colNamesRef[0] = String.join("\u0000", cols);
                                }
                                String[] colNames = colNamesRef[0].split("\u0000", -1);
                                gen.writeStartObject();
                                for (int i = 1; i <= colCountRef[0]; i++) {
                                    gen.writeObjectField(colNames[i - 1], getSafeObject(rs, i));
                                }
                                gen.writeEndObject();
                                rowCount[0]++;
                                if (rowCount[0] % 1000 == 0) {
                                    gen.flush();
                                }
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        });

                        gen.writeEndArray(); // end data array
                        gen.writeEndObject(); // end root object
                        gen.flush();
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(ApiEndpointController.class).error("Test query streaming error: {}", e.getMessage());
                    }
                };

                return ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header("X-Accel-Buffering", "no")
                        .header("Cache-Control", "no-cache")
                        .body(stream);
            }

            final String finalSql = sql;
            final Map<String, Object> finalParams = params;

            org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody stream = out -> {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                try (com.fasterxml.jackson.core.JsonGenerator gen = mapper.getFactory().createGenerator(out, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
                    gen.writeStartArray(); // [

                    final String[] colNamesRef = new String[1];
                    final int[] colCountRef = new int[1];
                    final int[] rowCount = new int[]{0};

                    jdbcTemplate.query(finalSql, finalParams, (java.sql.ResultSet rs) -> {
                        try {
                            if (colNamesRef[0] == null) {
                                java.sql.ResultSetMetaData meta = rs.getMetaData();
                                colCountRef[0] = meta.getColumnCount();
                                String[] cols = new String[colCountRef[0]];
                                for (int i = 1; i <= colCountRef[0]; i++) {
                                    cols[i - 1] = meta.getColumnLabel(i);
                                }
                                colNamesRef[0] = String.join("\u0000", cols);
                            }
                            String[] colNames = colNamesRef[0].split("\u0000", -1);
                            gen.writeStartObject();
                            for (int i = 1; i <= colCountRef[0]; i++) {
                                gen.writeObjectField(colNames[i - 1], getSafeObject(rs, i));
                            }
                            gen.writeEndObject();
                            rowCount[0]++;
                            if (rowCount[0] % 1000 == 0) {
                                gen.flush();
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });

                    gen.writeEndArray(); // ]
                    gen.flush();
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(ApiEndpointController.class).error("Test query streaming error: {}", e.getMessage());
                }
            };

            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header("X-Accel-Buffering", "no")
                    .header("Cache-Control", "no-cache")
                    .body(stream);
        } catch(Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    private Object getSafeObject(java.sql.ResultSet rs, int colIdx) throws java.sql.SQLException {
        Object val = rs.getObject(colIdx);
        if (val == null) return null;
        if (val instanceof java.sql.Blob) {
            java.sql.Blob b = (java.sql.Blob) val;
            return "[BLOB Data: " + b.length() + " bytes]";
        } else if (val instanceof java.sql.Clob) {
            java.sql.Clob c = (java.sql.Clob) val;
            return "[CLOB Data: " + c.length() + " chars]";
        } else if (val instanceof byte[]) {
            return "[BINARY Data: " + ((byte[]) val).length + " bytes]";
        } else if (val instanceof java.sql.Timestamp) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) val);
        } else if (val instanceof java.sql.Date) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd").format((java.util.Date) val);
        } else if (val instanceof java.sql.Time) {
            return new java.text.SimpleDateFormat("HH:mm:ss").format((java.util.Date) val);
        } else if (val instanceof java.util.Date) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) val);
        } else if (val instanceof java.time.LocalDateTime) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((java.time.LocalDateTime) val);
        } else if (val instanceof java.time.LocalDate) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").format((java.time.LocalDate) val);
        } else if (val instanceof java.time.LocalTime) {
            return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format((java.time.LocalTime) val);
        } else if (val instanceof java.time.ZonedDateTime) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").format((java.time.ZonedDateTime) val);
        } else if (val instanceof java.time.OffsetDateTime) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").format((java.time.OffsetDateTime) val);
        }
        return val;
    }

    private static final Set<String> ALLOWED_OPS = new HashSet<>(Arrays.asList(
        "=", "!=", "<>", "<", ">", "<=", ">=",
        "LIKE", "NOT LIKE", "ILIKE", "NOT ILIKE",
        "IN", "NOT IN",
        "IS NULL", "IS NOT NULL"
    ));

    @SuppressWarnings("unchecked")
    private String buildFilterClause(Object filtersObj, Map<String, Object> sqlParams) {
        if (filtersObj == null) return "1=1";
        if (!(filtersObj instanceof List)) return "1=1";

        List<?> filters = (List<?>) filtersObj;
        if (filters.isEmpty()) return "1=1";

        List<String> clauses = new ArrayList<>();
        int idx = 0;

        for (Object filterObj : filters) {
            if (!(filterObj instanceof Map)) continue;
            Map<String, Object> filter = (Map<String, Object>) filterObj;

            String field = filter.get("field") != null ? filter.get("field").toString().trim() : null;
            String op    = filter.get("op")    != null ? filter.get("op").toString().trim().toUpperCase() : null;
            Object value = filter.get("value");

            if (field == null || op == null) continue;

            if (!field.matches("[a-zA-Z_][a-zA-Z0-9_.]*")) {
                throw new IllegalArgumentException("Invalid field name in filters: '" + field + "'");
            }

            if (!ALLOWED_OPS.contains(op)) {
                throw new IllegalArgumentException("Invalid operator in filters: '" + op + "'");
            }

            if (op.equals("IS NULL") || op.equals("IS NOT NULL")) {
                clauses.add(field + " " + op);
            } else if (op.equals("IN") || op.equals("NOT IN")) {
                List<Object> inValues = new ArrayList<>();
                if (value instanceof List) {
                    inValues.addAll((List<Object>) value);
                } else if (value != null) {
                    for (String v : value.toString().split(",")) {
                        inValues.add(v.trim());
                    }
                }
                if (inValues.isEmpty()) {
                    clauses.add(op.equals("IN") ? "1=0" : "1=1");
                } else {
                    String paramName = "f_" + field.replace(".", "_") + "_" + idx;
                    sqlParams.put(paramName, inValues);
                    clauses.add(field + " " + op + " (:" + paramName + ")");
                }
            } else {
                String paramName = "f_" + field.replace(".", "_") + "_" + idx;
                sqlParams.put(paramName, value != null ? value.toString() : null);
                clauses.add(field + " " + op + " :" + paramName);
            }
            idx++;
        }

        return clauses.isEmpty() ? "1=1" : String.join(" AND ", clauses);
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

    @PatchMapping("/{id}/ip-allowlist")
    public ResponseEntity<?> updateIpAllowlist(@PathVariable String id, @RequestBody Map<String, String> body) {
        String ipAllowlist = body.get("ipAllowlist");
        try {
            apiEndpointRepository.updateIpAllowlist(id, ipAllowlist);
            Optional<ApiEndpoint> updated = apiEndpointRepository.findById(id);
            return ResponseEntity.ok(updated.orElse(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to update IP allowlist"));
        }
    }

    @PatchMapping("/{id}/group")
    public ResponseEntity<?> updateGroupName(@PathVariable String id, @RequestBody Map<String, String> body) {
        String groupName = body.get("groupName");
        try {
            apiEndpointRepository.updateGroupName(id, groupName);
            Optional<ApiEndpoint> updated = apiEndpointRepository.findById(id);
            return ResponseEntity.ok(updated.orElse(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to update group name"));
        }
    }

    @Autowired
    private com.dbdiff.repository.AppGroupRepository appGroupRepository;

    @PutMapping("/groups/rename")
    public ResponseEntity<?> renameGroup(@RequestBody Map<String, String> body) {
        String oldName = body.get("oldName");
        String newName = body.get("newName");
        try {
            int affected = apiEndpointRepository.renameGroup(oldName, newName);
            if (appGroupRepository != null) {
                appGroupRepository.renameGroup("API_BUILDER", oldName, newName);
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "Renamed group successfully", "affected", affected));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to rename group"));
        }
    }

    @DeleteMapping("/groups/{groupName}")
    public ResponseEntity<?> deleteGroup(@PathVariable String groupName) {
        try {
            int affected = apiEndpointRepository.deleteGroup(groupName);
            if (appGroupRepository != null) {
                appGroupRepository.deleteGroup("API_BUILDER", groupName);
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "Group deleted successfully and items moved to General", "affected", affected));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to delete group"));
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
