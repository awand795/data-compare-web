package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ApiEndpointRepository;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.service.ConnectionManagerService;
import com.dbdiff.service.ApiParameterValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/data")
public class DynamicApiController {

    @Autowired
    private ApiParameterValidator apiParameterValidator;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<?> handleRequest(
            HttpServletRequest request,
            @RequestParam Map<String, Object> queryParams,
            @RequestBody(required = false) Map<String, Object> bodyParams,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey) {

        // Extract path after /api/data
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (path == null) {
            path = request.getRequestURI();
        }
        String prefix = "/api/data";
        if (path.startsWith(prefix)) {
            path = path.substring(prefix.length());
        }
        
        // Ensure path starts with /
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        String method = request.getMethod().toUpperCase();

        Optional<ApiEndpoint> optEndpoint = apiEndpointRepository.findByPathAndMethod(path, method);
        if (optEndpoint.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Endpoint not found: " + method + " " + path));
        }

        ApiEndpoint endpoint = optEndpoint.get();

        // ── IP Allowlist Security Check ─────────────────────────────────────────
        String clientIp = getClientIpAddress(request);
        String ipAllowlist = endpoint.getIpAllowlist();
        if (!isIpAllowed(clientIp, ipAllowlist)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                        "error", "Forbidden",
                        "message", "Access denied: Client IP [" + clientIp + "] is not in the allowed IP list for this endpoint."
                    ));
        }
        // ────────────────────────────────────────────────────────────────────────

        // Check authentication
        if (endpoint.isPublic() == false) {
            String token = endpoint.getAuthToken();
            if (token != null && !token.isEmpty()) {
                String providedToken = null;
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    providedToken = authHeader.substring(7);
                } else if (xApiKey != null) {
                    providedToken = xApiKey;
                }

                if (providedToken == null || !providedToken.equals(token)) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("error", "Unauthorized. Invalid or missing token."));
                }
            }
        }

        // Merge parameters (body overrides query params)
        Map<String, Object> allParams = new HashMap<>();
        if (queryParams != null) allParams.putAll(queryParams);
        if (bodyParams != null) allParams.putAll(bodyParams);

        ApiParameterValidator.ValidationResult validationResult = apiParameterValidator.validate(endpoint.getParameters(), allParams);
        if (!validationResult.isValid()) {
            return ResponseEntity.badRequest().body(Map.of("errors", validationResult.getErrors()));
        }
        allParams = validationResult.getParams();

        // Fetch Connection
        ConnectionDetails optConn = connectionRepository.findById(endpoint.getConnectionId());
        if (optConn == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Database connection configuration not found"));
        }

        try {
            DataSource dataSource = connectionManagerService.getDataSource(optConn);
            NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);

            String sql = endpoint.getSqlQuery();

            // ── Raw SQL Condition Support (Opsi 1) ──────────────────────────────────
            String rawCondition = null;
            for (String key : new String[]{"where_condition", "raw_sql", "condition", "whereCondition"}) {
                if (allParams.containsKey(key) && allParams.get(key) != null) {
                    rawCondition = allParams.get(key).toString().trim();
                    break;
                }
            }
            
            // Remove raw condition keys from params so NamedParameterJdbcTemplate never expects them
            allParams.remove("where_condition");
            allParams.remove("raw_sql");
            allParams.remove("condition");
            allParams.remove("whereCondition");

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
                Object filtersObj = allParams.remove("filters");
                String builtClause = buildFilterClause(filtersObj, allParams);
                sql = sql.replace("{{filters}}", builtClause);
            }
            // ───────────────────────────────────────────────────────────────────────

            // Pagination support
            if (endpoint.isEnablePagination()) {
                int limit = 10;
                int offset = 0;
                int page = 1;

                if (allParams.containsKey("limit")) {
                    String val = allParams.get("limit").toString();
                    if (!val.matches("-?\\d+")) return ResponseEntity.badRequest().body(Map.of("errors", List.of("Parameter 'limit' must be a valid integer")));
                    limit = Integer.parseInt(val);
                } else if (allParams.containsKey("size")) {
                    String val = allParams.get("size").toString();
                    if (!val.matches("-?\\d+")) return ResponseEntity.badRequest().body(Map.of("errors", List.of("Parameter 'size' must be a valid integer")));
                    limit = Integer.parseInt(val);
                }
                
                if (allParams.containsKey("page")) {
                    String val = allParams.get("page").toString();
                    if (!val.matches("-?\\d+")) return ResponseEntity.badRequest().body(Map.of("errors", List.of("Parameter 'page' must be a valid integer")));
                    page = Integer.parseInt(val);
                    if (page < 1) page = 1;
                    offset = (page - 1) * limit;
                } else if (allParams.containsKey("offset")) {
                    String val = allParams.get("offset").toString();
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
                    Integer count = jdbcTemplate.queryForObject(countSql, allParams, Integer.class);
                    if (count != null) totalRecords = count;
                } catch (Exception ex) {
                    // Ignore count fallback if dialect query fails
                }

                String paginatedSql;
                if (optConn.getType() != null && optConn.getType().equalsIgnoreCase("SQLSERVER")) {
                    paginatedSql = cleanSql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
                } else if (optConn.getType() != null && optConn.getType().equalsIgnoreCase("ORACLE")) {
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
                final Map<String, Object> finalParams = allParams;

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
                        org.slf4j.LoggerFactory.getLogger(DynamicApiController.class).error("Streaming error: {}", e.getMessage());
                    }
                };

                return ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header("X-Accel-Buffering", "no")
                        .header("Cache-Control", "no-cache")
                        .body(stream);
            }

            final String finalSql = sql;
            final Map<String, Object> finalParams = allParams;

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
                    org.slf4j.LoggerFactory.getLogger(DynamicApiController.class).error("Streaming error: {}", e.getMessage());
                }
            };

            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header("X-Accel-Buffering", "no")
                    .header("Cache-Control", "no-cache")
                    .body(stream);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Database execution error: " + e.getMessage()));
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

    // ── Allowed SQL operators whitelist (prevents injection via operator field) ──
    private static final Set<String> ALLOWED_OPS = new HashSet<>(Arrays.asList(
        "=", "!=", "<>", "<", ">", "<=", ">=",
        "LIKE", "NOT LIKE", "ILIKE", "NOT ILIKE",
        "IN", "NOT IN",
        "IS NULL", "IS NOT NULL"
    ));

    /**
     * Safely builds a SQL WHERE clause fragment from a structured filters list.
     * Each filter item is a Map with keys: field, op, value (value omitted for IS NULL / IS NOT NULL).
     * Named parameters are injected into sqlParams for safe Prepared Statement binding.
     *
     * @param filtersObj  raw value of the 'filters' key from the request body
     * @param sqlParams   mutable parameter map that will be passed to NamedParameterJdbcTemplate
     * @return SQL fragment to replace {{filters}}, e.g. "kode_barang = :f_kode_barang_0 AND ..."
     */
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

            // Whitelist check: field name must be alphanumeric + underscore + optional dot (schema.table)
            if (!field.matches("[a-zA-Z_][a-zA-Z0-9_.]*")) {
                throw new IllegalArgumentException("Invalid field name in filters: '" + field + "'");
            }

            // Whitelist check: operator must be one of the allowed set
            if (!ALLOWED_OPS.contains(op)) {
                throw new IllegalArgumentException("Invalid operator in filters: '" + op + "'");
            }

            if (op.equals("IS NULL") || op.equals("IS NOT NULL")) {
                clauses.add(field + " " + op);
            } else if (op.equals("IN") || op.equals("NOT IN")) {
                // value should be a List or comma-separated string
                List<Object> inValues = new ArrayList<>();
                if (value instanceof List) {
                    inValues.addAll((List<Object>) value);
                } else if (value != null) {
                    for (String v : value.toString().split(",")) {
                        inValues.add(v.trim());
                    }
                }
                if (inValues.isEmpty()) {
                    // IN () is invalid SQL — use always-false / always-true condition
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

    /**
     * Extracts the real client IP address from request headers or remote socket.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String[] headerCandidates = {
            "X-Forwarded-For",
            "X-Real-IP",
            "CF-Connecting-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP",
            "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED",
            "HTTP_VIA",
            "REMOTE_ADDR"
        };

        for (String header : headerCandidates) {
            String ipList = request.getHeader(header);
            if (ipList != null && !ipList.trim().isEmpty() && !"unknown".equalsIgnoreCase(ipList.trim())) {
                // X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2"
                String firstIp = ipList.split(",")[0].trim();
                return normalizeIp(firstIp);
            }
        }

        return normalizeIp(request.getRemoteAddr());
    }

    private String normalizeIp(String ip) {
        if (ip == null) return "127.0.0.1";
        String clean = ip.trim();
        if ("0:0:0:0:0:0:0:1".equals(clean) || "::1".equals(clean)) {
            return "127.0.0.1";
        }
        if (clean.startsWith("::ffff:")) {
            return clean.substring(7);
        }
        return clean;
    }

    /**
     * Evaluates whether a client IP is authorized under the given IP allowlist rules.
     * Supports comma/newline-separated single IPs, wildcards (*, 192.168.1.*), and CIDR ranges (10.0.0.0/8).
     */
    private boolean isIpAllowed(String clientIp, String allowlist) {
        if (allowlist == null || allowlist.trim().isEmpty() || "*".equals(allowlist.trim()) || "0.0.0.0/0".equals(allowlist.trim())) {
            return true;
        }

        String normalizedClient = normalizeIp(clientIp);
        String[] rules = allowlist.split("[,;\\r\\n]+");

        for (String rawRule : rules) {
            String rule = rawRule.trim();
            if (rule.isEmpty()) continue;

            if ("*".equals(rule) || "0.0.0.0/0".equals(rule)) {
                return true;
            }

            String normalizedRule = normalizeIp(rule);

            // Exact match
            if (normalizedClient.equalsIgnoreCase(normalizedRule)) {
                return true;
            }

            // Localhost match
            if (isLocalhost(normalizedClient) && isLocalhost(normalizedRule)) {
                return true;
            }

            // Wildcard match (e.g. 192.168.1.*)
            if (rule.endsWith(".*")) {
                String prefix = rule.substring(0, rule.length() - 1); // "192.168.1."
                if (normalizedClient.startsWith(prefix)) {
                    return true;
                }
            }

            // CIDR range match (e.g. 192.168.1.0/24 or 10.0.0.0/8)
            if (rule.contains("/")) {
                if (matchesCidr(normalizedClient, rule)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isLocalhost(String ip) {
        return "127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase(ip) || "::1".equals(ip);
    }

    private boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) return false;

            String baseIp = parts[0].trim();
            int prefixLength = Integer.parseInt(parts[1].trim());

            long ipLong = ipToLong(ip);
            long baseLong = ipToLong(baseIp);

            if (ipLong == -1 || baseLong == -1) return false;

            long mask = (prefixLength == 0) ? 0 : (-1L << (32 - prefixLength)) & 0xFFFFFFFFL;
            return (ipLong & mask) == (baseLong & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private long ipToLong(String ip) {
        try {
            String[] octets = ip.split("\\.");
            if (octets.length != 4) return -1;
            long result = 0;
            for (int i = 0; i < 4; i++) {
                long octet = Long.parseLong(octets[i]);
                if (octet < 0 || octet > 255) return -1;
                result = (result << 8) | octet;
            }
            return result;
        } catch (Exception e) {
            return -1;
        }
    }
}
