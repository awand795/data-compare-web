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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

            // Pagination support
            if (endpoint.isEnablePagination()) {
                int limit = 100;
                int offset = 0;
                if (allParams.containsKey("limit")) {
                    limit = Integer.parseInt(allParams.get("limit").toString());
                } else if (allParams.containsKey("size")) {
                    limit = Integer.parseInt(allParams.get("size").toString());
                }
                
                if (allParams.containsKey("offset")) {
                    offset = Integer.parseInt(allParams.get("offset").toString());
                } else if (allParams.containsKey("page")) {
                    int page = Integer.parseInt(allParams.get("page").toString());
                    offset = (page > 0 ? page - 1 : 0) * limit;
                }

                // A simplistic way to append pagination. In real life, dialect matters (MySQL/PG vs SQLServer)
                // Assuming MySQL/PostgreSQL/Clickhouse dialect:
                sql = sql + " LIMIT " + limit + " OFFSET " + offset;
            }

            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, allParams);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Database execution error: " + e.getMessage()));
        }
    }
}
