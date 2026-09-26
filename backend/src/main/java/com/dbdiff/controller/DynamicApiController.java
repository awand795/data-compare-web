package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ApiEndpointRepository;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.service.ConnectionManagerService;
import com.dbdiff.service.ApiParameterValidator;
import com.dbdiff.service.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.HandlerMapping;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.tika.Tika;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/data")
public class DynamicApiController {

    private final Tika tika = new Tika();

    @Autowired
    private ApiParameterValidator apiParameterValidator;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired(required = false)
    private JwtService jwtService;

    @Autowired(required = false)
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public void handleRequest(
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            @RequestParam(required = false) Map<String, Object> queryParams,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey) throws Exception {
        handleRequestInternal(request, response, queryParams, null, authHeader, xApiKey);
    }

    public void handleRequest(
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            Map<String, Object> queryParams,
            Map<String, Object> bodyParams,
            String authHeader,
            String xApiKey) throws Exception {
        handleRequestInternal(request, response, queryParams, bodyParams, authHeader, xApiKey);
    }

    private void handleRequestInternal(
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            Map<String, Object> queryParams,
            Map<String, Object> bodyParams,
            String authHeader,
            String xApiKey) throws Exception {

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
            sendJsonError(response, HttpStatus.NOT_FOUND.value(), Map.of("error", "Endpoint not found: " + method + " " + path));
            return;
        }

        ApiEndpoint endpoint = optEndpoint.get();

        // ── IP Allowlist Security Check ─────────────────────────────────────────
        String clientIp = getClientIpAddress(request);
        String ipAllowlist = endpoint.getIpAllowlist();
        if (!isIpAllowed(clientIp, ipAllowlist)) {
            sendJsonError(response, HttpStatus.FORBIDDEN.value(), Map.of(
                "error", "Forbidden",
                "message", "Access denied: Client IP [" + clientIp + "] is not in the allowed IP list for this endpoint."
            ));
            return;
        }
        // ────────────────────────────────────────────────────────────────────────

        // Check authentication
        String providedToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            providedToken = authHeader.substring(7).trim();
        } else if (xApiKey != null) {
            providedToken = xApiKey.trim();
        }

        // Try validating as JWT token (if provided)
        Claims jwtClaims = null;
        if (providedToken != null && jwtService != null) {
            try {
                jwtClaims = jwtService.validateAccessToken(providedToken);
            } catch (Exception ignored) {
                // Not a valid JWT, fallback to static token behavior
                jwtClaims = null;
            }
        }
        boolean isValidJwt = (jwtClaims != null);
        boolean isAuthAction = "LOGIN".equalsIgnoreCase(endpoint.getAuthAction())
                || "REGISTER".equalsIgnoreCase(endpoint.getAuthAction())
                || "REFRESH_TOKEN".equalsIgnoreCase(endpoint.getAuthAction());

        String secMode = endpoint.getSecurityMode();
        if (secMode == null || secMode.trim().isEmpty()) {
            if (endpoint.isPublic()) secMode = "PUBLIC";
            else secMode = "API_KEY";
        }

        if (!"PUBLIC".equalsIgnoreCase(secMode) && !endpoint.isPublic() && !isAuthAction) {
            String token = endpoint.getAuthToken();
            boolean authorized = false;

            if ("JWT_AUTH".equalsIgnoreCase(secMode)) {
                // Must be a valid JWT
                authorized = isValidJwt;
            } else if ("API_KEY".equalsIgnoreCase(secMode)) {
                // Must match static API key
                authorized = (providedToken != null && token != null && !token.trim().isEmpty() && providedToken.equals(token));
            } else { // HYBRID
                authorized = (providedToken != null && token != null && !token.trim().isEmpty() && providedToken.equals(token)) || isValidJwt;
            }

            if (!authorized) {
                String errorMsg;
                if ("JWT_AUTH".equalsIgnoreCase(secMode)) {
                    errorMsg = "Unauthorized. Akses endpoint ini wajib menggunakan token JWT yang valid (hasil login).";
                } else if ("API_KEY".equalsIgnoreCase(secMode)) {
                    errorMsg = "Unauthorized. Akses endpoint ini memerlukan Static API Key yang valid.";
                } else {
                    errorMsg = "Unauthorized. Invalid or missing token (Static API Key atau JWT login diperlukan).";
                }
                sendJsonError(response, HttpStatus.UNAUTHORIZED.value(), Map.of(
                    "success", false,
                    "error", "Unauthorized",
                    "message", errorMsg,
                    "errors", List.of(errorMsg)
                ));
                return;
            }
        }

        // ── Auth App Restriction Check ──────────────────────────────────────────
        String requiredAppId = endpoint.getRequiredAppId();
        if (requiredAppId != null && !requiredAppId.trim().isEmpty() && !isAuthAction) {
            if (!isValidJwt || jwtClaims == null) {
                sendJsonError(response, HttpStatus.FORBIDDEN.value(), Map.of(
                    "success", false,
                    "error", "Forbidden",
                    "message", "Token tidak valid untuk endpoint ini",
                    "errors", List.of("Token tidak valid untuk endpoint ini")
                ));
                return;
            }
            String tokenAppId = jwtClaims.get("appId", String.class);
            if (tokenAppId == null || !tokenAppId.trim().equalsIgnoreCase(requiredAppId.trim())) {
                sendJsonError(response, HttpStatus.FORBIDDEN.value(), Map.of(
                    "success", false,
                    "error", "Forbidden",
                    "message", "Token tidak valid untuk endpoint ini",
                    "errors", List.of("Token tidak valid untuk endpoint ini")
                ));
                return;
            }
        }

        // ── Allowed Roles Check (RBAC) ──────────────────────────────────────────
        String allowedRoles = endpoint.getAllowedRoles();
        if (allowedRoles != null && !allowedRoles.trim().isEmpty() && !isAuthAction && !endpoint.isPublic() && !"PUBLIC".equalsIgnoreCase(secMode)) {
            if (!isValidJwt || jwtClaims == null) {
                sendJsonError(response, HttpStatus.FORBIDDEN.value(), Map.of(
                    "success", false,
                    "error", "Forbidden",
                    "message", "Akses ditolak: Autentikasi JWT diperlukan untuk endpoint dengan pembatasan role.",
                    "errors", List.of("Akses ditolak: Autentikasi JWT diperlukan untuk endpoint dengan pembatasan role.")
                ));
                return;
            }
            String userRole = jwtClaims.get("role", String.class);
            if (userRole == null) userRole = "";
            userRole = userRole.trim();

            String[] roles = allowedRoles.split("[,;\\s]+");
            boolean roleMatched = false;
            for (String r : roles) {
                String cleanR = r.trim();
                if (!cleanR.isEmpty() && cleanR.equalsIgnoreCase(userRole)) {
                    roleMatched = true;
                    break;
                }
            }

            if (!roleMatched) {
                String deniedMsg = "Akses ditolak: Role Anda ('" + (userRole.isEmpty() ? "UNKNOWN" : userRole) + "') tidak diizinkan mengakses endpoint ini.";
                sendJsonError(response, HttpStatus.FORBIDDEN.value(), Map.of(
                    "success", false,
                    "error", "Forbidden",
                    "message", deniedMsg,
                    "errors", List.of(deniedMsg)
                ));
                return;
            }
        }

        // Resolve bodyParams (JSON or Multipart / Form)
        Map<String, Object> resolvedBodyParams = new HashMap<>();
        if (bodyParams != null) {
            resolvedBodyParams.putAll(bodyParams);
        } else {
            String contentType = request.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("application/json")) {
                try {
                    byte[] raw = request.getInputStream().readAllBytes();
                    if (raw.length > 0) {
                        com.fasterxml.jackson.databind.ObjectMapper jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        Map<String, Object> parsed = jsonMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        if (parsed != null) resolvedBodyParams.putAll(parsed);
                    }
                } catch (Exception ignored) {}
            }
        }

        // Handle multipart/form-data
        MultipartHttpServletRequest multipartRequest = null;
        if (request instanceof MultipartHttpServletRequest) {
            multipartRequest = (MultipartHttpServletRequest) request;
        } else {
            try {
                StandardServletMultipartResolver resolver = new StandardServletMultipartResolver();
                if (resolver.isMultipart(request)) {
                    multipartRequest = resolver.resolveMultipart(request);
                }
            } catch (Exception ignored) {}
        }

        Map<String, Object> uploadMetadata = new HashMap<>();

        if (multipartRequest != null) {
            // Read form text parameters
            for (Map.Entry<String, String[]> formParam : multipartRequest.getParameterMap().entrySet()) {
                if (formParam.getValue() != null && formParam.getValue().length > 0) {
                    resolvedBodyParams.put(formParam.getKey(), formParam.getValue()[0]);
                }
            }

            // Process uploaded files
            Map<String, MultipartFile> fileMap = multipartRequest.getFileMap();
            for (Map.Entry<String, MultipartFile> fileEntry : fileMap.entrySet()) {
                String fieldName = fileEntry.getKey();
                MultipartFile file = fileEntry.getValue();
                if (file == null || file.isEmpty()) continue;

                // Validate max size (MB)
                int maxMb = endpoint.getMaxFileSizeMb() != null ? endpoint.getMaxFileSizeMb() : 10;
                long maxBytes = (long) maxMb * 1024L * 1024L;
                if (file.getSize() > maxBytes) {
                    sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of(
                        "success", false,
                        "error", "Bad Request",
                        "message", "Ukuran file '" + file.getOriginalFilename() + "' (" + (file.getSize() / 1024 / 1024) + "MB) melebihi batas maksimal " + maxMb + "MB."
                    ));
                    return;
                }

                // Validate extension
                String originalName = file.getOriginalFilename();
                if (originalName == null || originalName.trim().isEmpty()) {
                    originalName = "file_" + System.currentTimeMillis() + ".bin";
                }
                originalName = Paths.get(originalName).getFileName().toString();
                String ext = "";
                int dotIdx = originalName.lastIndexOf('.');
                if (dotIdx > 0) {
                    ext = originalName.substring(dotIdx + 1).toLowerCase();
                }

                String allowedExt = endpoint.getAllowedExtensions();
                if (allowedExt != null && !allowedExt.trim().isEmpty() && !allowedExt.equals("*")) {
                    Set<String> allowedSet = new HashSet<>(Arrays.asList(allowedExt.toLowerCase().split("[,\\s|]+")));
                    if (!allowedSet.contains(ext)) {
                        sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of(
                            "success", false,
                            "error", "Bad Request",
                            "message", "Format file '." + ext + "' tidak diizinkan. Format yang diterima: " + allowedExt.toUpperCase()
                        ));
                        return;
                    }
                }

                // Magic-byte MIME detection via Apache Tika
                String detectedMime = "application/octet-stream";
                try {
                    detectedMime = tika.detect(file.getInputStream(), originalName);
                } catch (Exception ignored) {}

                String lowerMime = detectedMime.toLowerCase();
                if (lowerMime.contains("dosexec") || lowerMime.contains("x-executable") || 
                    lowerMime.contains("x-sh") || lowerMime.contains("x-bat") || 
                    lowerMime.contains("javascript") || lowerMime.contains("x-msdownload")) {
                    sendJsonError(response, HttpStatus.FORBIDDEN.value(), Map.of(
                        "success", false,
                        "error", "Forbidden",
                        "message", "File ditolak karena alasan keamanan (tipe file berbahaya terdeteksi)."
                    ));
                    return;
                }

                long originalBytes = file.getSize();
                byte[] finalBytes = file.getBytes();
                boolean wasCompressed = false;

                // Compress image via Thumbnailator if auto-compress is enabled
                boolean isCompressible = lowerMime.startsWith("image/")
                        && (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp"));

                if (endpoint.isAutoCompressImage() && isCompressible) {
                    try {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        int maxWidth = endpoint.getImageMaxWidth() != null ? endpoint.getImageMaxWidth() : 1920;
                        int maxHeight = endpoint.getImageMaxHeight() != null ? endpoint.getImageMaxHeight() : 1920;
                        int qualityPct = endpoint.getImageQualityPercent() != null ? endpoint.getImageQualityPercent() : 80;
                        double quality = Math.min(1.0, Math.max(0.05, (double) qualityPct / 100.0));

                        Thumbnails.of(file.getInputStream())
                            .size(maxWidth, maxHeight)
                            .outputQuality(quality)
                            .toOutputStream(baos);

                        byte[] comp = baos.toByteArray();
                        if (comp.length > 0 && comp.length < finalBytes.length) {
                            finalBytes = comp;
                            wasCompressed = true;
                        }
                    } catch (Exception thumbEx) {
                        org.slf4j.LoggerFactory.getLogger(DynamicApiController.class).warn("Thumbnailator compression fallback: {}", thumbEx.getMessage());
                    }
                }

                // Encode Base64
                String base64Str = Base64.getEncoder().encodeToString(finalBytes);
                String dataUri = "data:" + detectedMime + ";base64," + base64Str;

                // Inject parameters:
                resolvedBodyParams.put(fieldName, finalBytes);
                resolvedBodyParams.put(fieldName + "_base64", base64Str);
                resolvedBodyParams.put(fieldName + "_base64_data", dataUri);
                resolvedBodyParams.put(fieldName + "_name", originalName);
                resolvedBodyParams.put(fieldName + "_filename", originalName);
                resolvedBodyParams.put(fieldName + "_mime", detectedMime);
                resolvedBodyParams.put(fieldName + "_content_type", detectedMime);
                resolvedBodyParams.put(fieldName + "_size", (long) finalBytes.length);
                resolvedBodyParams.put(fieldName + "_original_size", originalBytes);

                // Map to custom fileParamName if different (e.g. endpoint specifies 'foto' while field was 'file' or vice versa)
                String customParam = endpoint.getFileParamName();
                if (customParam != null && !customParam.trim().isEmpty() && !customParam.equalsIgnoreCase(fieldName)) {
                    resolvedBodyParams.putIfAbsent(customParam, finalBytes);
                    resolvedBodyParams.putIfAbsent(customParam + "_base64", base64Str);
                    resolvedBodyParams.putIfAbsent(customParam + "_base64_data", dataUri);
                    resolvedBodyParams.putIfAbsent(customParam + "_name", originalName);
                    resolvedBodyParams.putIfAbsent(customParam + "_filename", originalName);
                    resolvedBodyParams.putIfAbsent(customParam + "_mime", detectedMime);
                    resolvedBodyParams.putIfAbsent(customParam + "_content_type", detectedMime);
                    resolvedBodyParams.putIfAbsent(customParam + "_size", (long) finalBytes.length);
                }

                uploadMetadata.put("field", fieldName);
                uploadMetadata.put("filename", originalName);
                uploadMetadata.put("original_size", originalBytes);
                uploadMetadata.put("size", (long) finalBytes.length);
                uploadMetadata.put("mime_type", detectedMime);
                uploadMetadata.put("compressed", wasCompressed);
                if (wasCompressed && originalBytes > 0) {
                    int savedPct = (int) Math.round((1.0 - (double) finalBytes.length / originalBytes) * 100);
                    uploadMetadata.put("saved_percentage", Math.max(0, savedPct) + "%");
                }
            }
        }

        // Also check if any param in resolvedBodyParams is a Base64 image data URI (from JSON payload)
        for (Map.Entry<String, Object> pEntry : new HashMap<>(resolvedBodyParams).entrySet()) {
            if (pEntry.getValue() instanceof String strVal) {
                if (strVal.startsWith("data:image/") && strVal.contains(";base64,")) {
                    try {
                        String mime = strVal.substring(5, strVal.indexOf(";"));
                        String b64 = strVal.substring(strVal.indexOf(";base64,") + 8);
                        byte[] decoded = Base64.getDecoder().decode(b64);
                        byte[] finalBytes = decoded;
                        boolean wasComp = false;

                        if (endpoint.isAutoCompressImage() && mime.startsWith("image/")) {
                            try {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                int maxWidth = endpoint.getImageMaxWidth() != null ? endpoint.getImageMaxWidth() : 1920;
                                int maxHeight = endpoint.getImageMaxHeight() != null ? endpoint.getImageMaxHeight() : 1920;
                                int qualityPct = endpoint.getImageQualityPercent() != null ? endpoint.getImageQualityPercent() : 80;
                                double quality = Math.min(1.0, Math.max(0.05, (double) qualityPct / 100.0));

                                Thumbnails.of(new ByteArrayInputStream(decoded))
                                    .size(maxWidth, maxHeight)
                                    .outputQuality(quality)
                                    .toOutputStream(baos);
                                byte[] comp = baos.toByteArray();
                                if (comp.length > 0 && comp.length < finalBytes.length) {
                                    finalBytes = comp;
                                    wasComp = true;
                                }
                            } catch (Exception ignored) {}
                        }

                        String newB64 = Base64.getEncoder().encodeToString(finalBytes);
                        resolvedBodyParams.put(pEntry.getKey(), finalBytes);
                        resolvedBodyParams.put(pEntry.getKey() + "_base64", newB64);
                        resolvedBodyParams.put(pEntry.getKey() + "_base64_data", "data:" + mime + ";base64," + newB64);
                        resolvedBodyParams.put(pEntry.getKey() + "_mime", mime);
                        resolvedBodyParams.put(pEntry.getKey() + "_size", (long) finalBytes.length);
                        resolvedBodyParams.put(pEntry.getKey() + "_original_size", (long) decoded.length);

                        uploadMetadata.put("field", pEntry.getKey());
                        uploadMetadata.put("original_size", (long) decoded.length);
                        uploadMetadata.put("size", (long) finalBytes.length);
                        uploadMetadata.put("mime_type", mime);
                        uploadMetadata.put("compressed", wasComp);
                        if (wasComp && decoded.length > 0) {
                            int savedPct = (int) Math.round((1.0 - (double) finalBytes.length / decoded.length) * 100);
                            uploadMetadata.put("saved_percentage", Math.max(0, savedPct) + "%");
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // Merge parameters (body overrides query params)
        Map<String, Object> allParams = new HashMap<>();
        if (queryParams != null) allParams.putAll(queryParams);
        allParams.putAll(resolvedBodyParams);

        ApiParameterValidator.ValidationResult validationResult = apiParameterValidator.validate(endpoint.getParameters(), allParams);
        if (!validationResult.isValid()) {
            sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of(
                "success", false,
                "error", "Bad Request",
                "message", "Validasi parameter gagal.",
                "errors", validationResult.getErrors()
            ));
            return;
        }
        allParams = validationResult.getParams();

        // ── Auto-Inject System Variables ───────────────────────────────────────
        String nowTimestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String todayDate = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        allParams.putIfAbsent("sys_now", nowTimestamp);
        allParams.putIfAbsent("sys_today", todayDate);
        allParams.putIfAbsent("current_timestamp", nowTimestamp);
        allParams.putIfAbsent("current_date", todayDate);
        allParams.putIfAbsent("sys_client_ip", clientIp);

        if (isValidJwt && jwtClaims != null) {
            String userId = jwtClaims.getSubject();
            String tokenAppId = jwtClaims.get("appId", String.class);
            String role = jwtClaims.get("role", String.class);
            String companyId = jwtClaims.get("companyId", String.class);
            allParams.putIfAbsent("sys_user_id", userId != null ? userId : "");
            allParams.putIfAbsent("sys_app_id", tokenAppId != null ? tokenAppId : "");
            allParams.putIfAbsent("sys_role", role != null ? role : "");
            allParams.putIfAbsent("sys_company_id", companyId != null ? companyId : "");
            allParams.put("sys_user", userId != null ? userId : "anonymous");
        } else {
            allParams.putIfAbsent("sys_user", providedToken != null ? providedToken : "anonymous");
            allParams.putIfAbsent("sys_user_id", "");
            allParams.putIfAbsent("sys_app_id", "");
            allParams.putIfAbsent("sys_role", "");
            allParams.putIfAbsent("sys_company_id", "");
        }

        // ── Auth Action: REFRESH_TOKEN ───────────────────────────────────────────
        if ("REFRESH_TOKEN".equalsIgnoreCase(endpoint.getAuthAction())) {
            String rawRefreshToken = null;
            if (allParams.containsKey("refresh_token") && allParams.get("refresh_token") != null) {
                rawRefreshToken = allParams.get("refresh_token").toString().trim();
            } else if (allParams.containsKey("refreshToken") && allParams.get("refreshToken") != null) {
                rawRefreshToken = allParams.get("refreshToken").toString().trim();
            } else if (providedToken != null) {
                rawRefreshToken = providedToken.trim();
            }

            if (rawRefreshToken == null || rawRefreshToken.isEmpty()) {
                sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of(
                    "success", false,
                    "error", "Bad Request",
                    "message", "Parameter 'refresh_token' wajib diisi.",
                    "errors", List.of("Parameter 'refresh_token' wajib diisi.")
                ));
                return;
            }

            if (jwtService == null) {
                sendJsonError(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), Map.of(
                    "success", false,
                    "error", "Internal Server Error",
                    "message", "JWT service tidak aktif pada server."
                ));
                return;
            }

            try {
                JwtService.TokenPair tokenPair = jwtService.refreshDynamic(rawRefreshToken, endpoint.getRequiredAppId());
                response.setStatus(HttpStatus.OK.value());
                response.setContentType("application/json;charset=UTF-8");
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> respMap = new HashMap<>();
                respMap.put("success", true);
                respMap.put("message", (endpoint.getSuccessMessage() != null && !endpoint.getSuccessMessage().trim().isEmpty())
                        ? endpoint.getSuccessMessage() : "Token berhasil diperbarui.");
                respMap.put("access_token", tokenPair.getAccessToken());
                respMap.put("refresh_token", tokenPair.getRefreshToken());
                respMap.put("token_type", "Bearer");
                respMap.put("expires_in", tokenPair.getExpiresIn());
                respMap.put("user", tokenPair.getUserData() != null ? tokenPair.getUserData() : tokenPair.getUser());
                respMap.put("timestamp", nowTimestamp);

                response.getWriter().write(mapper.writeValueAsString(respMap));
                response.getWriter().flush();
                return;
            } catch (JwtService.AuthSecurityException aex) {
                sendJsonError(response, aex.getStatus(), Map.of(
                    "success", false,
                    "error", "Unauthorized",
                    "message", aex.getMessage(),
                    "errors", List.of(aex.getMessage())
                ));
                return;
            } catch (Exception ex) {
                sendJsonError(response, HttpStatus.UNAUTHORIZED.value(), Map.of(
                    "success", false,
                    "error", "Unauthorized",
                    "message", "Gagal memperbarui token: " + ex.getMessage(),
                    "errors", List.of("Gagal memperbarui token: " + ex.getMessage())
                ));
                return;
            }
        }

        // Fetch Connection
        ConnectionDetails optConn = connectionRepository.findById(endpoint.getConnectionId());
        if (optConn == null) {
            sendJsonError(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), Map.of("error", "Database connection configuration not found"));
            return;
        }

        try {
            DataSource dataSource = connectionManagerService.getDataSource(optConn);
            NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);

            // ── Pre-Validation Business Rules Check ────────────────────────────────
            if (endpoint.getValidationRules() != null && !endpoint.getValidationRules().trim().isEmpty() && !endpoint.getValidationRules().equals("[]")) {
                com.fasterxml.jackson.databind.ObjectMapper ruleMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                try {
                    List<Map<String, Object>> rules = ruleMapper.readValue(endpoint.getValidationRules(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                    for (Map<String, Object> rule : rules) {
                        String ruleName = (String) rule.getOrDefault("name", "Business Rule");
                        String ruleSql = (String) rule.get("sqlQuery");
                        String condition = (String) rule.getOrDefault("condition", "EQ_0");
                        String customErr = (String) rule.get("customErrorMessage");

                        // 1. SpEL Expression Validation
                        String expr = (String) rule.get("expression");
                        if (expr != null && !expr.trim().isEmpty()) {
                            try {
                                org.springframework.expression.ExpressionParser spelParser = new org.springframework.expression.spel.standard.SpelExpressionParser();
                                org.springframework.expression.spel.support.StandardEvaluationContext spelCtx = new org.springframework.expression.spel.support.StandardEvaluationContext();
                                spelCtx.setVariable("p", allParams);
                                for (Map.Entry<String, Object> entry : allParams.entrySet()) {
                                    spelCtx.setVariable(entry.getKey(), entry.getValue());
                                }
                                Boolean passed = spelParser.parseExpression(expr).getValue(spelCtx, Boolean.class);
                                if (passed == null || !passed) {
                                    String errMsg = (customErr != null && !customErr.trim().isEmpty())
                                            ? customErr
                                            : "Validasi logika bisnis '" + ruleName + "' tidak terpenuhi.";
                                    sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of(
                                        "success", false,
                                        "error", "Bad Request",
                                        "message", errMsg,
                                        "errors", List.of(errMsg)
                                    ));
                                    return;
                                }
                            } catch (Exception spex) {
                                org.slf4j.LoggerFactory.getLogger(DynamicApiController.class).warn("SpEL rule error: {}", spex.getMessage());
                            }
                        }

                        // 2. SQL Query Assertion
                        if (ruleSql != null && !ruleSql.trim().isEmpty()) {
                            try {
                                Long count = jdbcTemplate.queryForObject(ruleSql, allParams, Long.class);
                                if (count == null) count = 0L;

                                boolean passed = true;
                                if ("EQ_0".equalsIgnoreCase(condition)) {
                                    passed = (count == 0);
                                } else if ("GT_0".equalsIgnoreCase(condition)) {
                                    passed = (count > 0);
                                } else if ("EQ_1".equalsIgnoreCase(condition)) {
                                    passed = (count == 1);
                                }

                                if (!passed) {
                                    String errMsg = (customErr != null && !customErr.trim().isEmpty())
                                            ? customErr
                                            : "Validasi bisnis '" + ruleName + "' tidak terpenuhi.";
                                    sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of(
                                        "success", false,
                                        "error", "Bad Request",
                                        "message", errMsg,
                                        "errors", List.of(errMsg)
                                    ));
                                    return;
                                }
                            } catch (Exception rex) {
                                org.slf4j.LoggerFactory.getLogger(DynamicApiController.class).warn("Pre-validation rule evaluation error: {}", rex.getMessage());
                            }
                        }
                    }
                } catch (Exception ex) {
                    // Ignore parse errors on malformed rule JSON
                }
            }

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
            // ── Auth Action: REGISTER ───────────────────────────────────────────────
            if ("REGISTER".equalsIgnoreCase(endpoint.getAuthAction())) {
                String passParam = endpoint.getPasswordParam();
                if (allParams.containsKey(passParam) && allParams.get(passParam) != null) {
                    String rawPass = allParams.get(passParam).toString();
                    String hashed = (passwordEncoder != null) ? passwordEncoder.encode(rawPass) : rawPass;
                    allParams.put(endpoint.getPasswordHashColumn(), hashed);
                    allParams.put("password_hash", hashed);
                    allParams.put(passParam, hashed);
                }
            }

            // ── Auth Action: LOGIN ───────────────────────────────────────────────────
            if ("LOGIN".equalsIgnoreCase(endpoint.getAuthAction())) {
                String passParam = endpoint.getPasswordParam();
                Object inputPassObj = allParams.get(passParam);
                if (inputPassObj == null || inputPassObj.toString().trim().isEmpty()) {
                    sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of(
                        "success", false,
                        "error", "Bad Request",
                        "message", "Parameter password '" + passParam + "' wajib diisi.",
                        "errors", List.of("Parameter password '" + passParam + "' wajib diisi.")
                    ));
                    return;
                }
                String inputPassword = inputPassObj.toString();

                List<Map<String, Object>> userRows = jdbcTemplate.queryForList(sql, allParams);
                if (userRows == null || userRows.isEmpty()) {
                    sendJsonError(response, HttpStatus.UNAUTHORIZED.value(), Map.of(
                        "success", false,
                        "error", "Unauthorized",
                        "message", "User tidak ditemukan atau kredensial salah.",
                        "errors", List.of("User tidak ditemukan atau kredensial salah.")
                    ));
                    return;
                }

                Map<String, Object> userRow = new HashMap<>(userRows.get(0));
                String hashCol = endpoint.getPasswordHashColumn();
                Object storedHashObj = userRow.get(hashCol);
                if (storedHashObj == null) storedHashObj = userRow.get("password_hash");
                if (storedHashObj == null) storedHashObj = userRow.get("password");
                String storedHash = (storedHashObj != null) ? storedHashObj.toString() : "";

                boolean matches = false;
                if (passwordEncoder != null && !storedHash.isEmpty()) {
                    try {
                        matches = passwordEncoder.matches(inputPassword, storedHash);
                    } catch (Exception ignored) {}
                }
                if (!matches && (inputPassword.equals(storedHash) || storedHash.isEmpty())) {
                    matches = inputPassword.equals(storedHash);
                }

                if (!matches) {
                    sendJsonError(response, HttpStatus.UNAUTHORIZED.value(), Map.of(
                        "success", false,
                        "error", "Unauthorized",
                        "message", "Password salah.",
                        "errors", List.of("Password salah.")
                    ));
                    return;
                }

                // Sanitize sensitive fields before returning user object
                userRow.remove("password_hash");
                userRow.remove("password");
                userRow.remove("passwordHash");
                userRow.remove("passwd");

                JwtService.TokenPair tokenPair = (jwtService != null)
                    ? jwtService.issueDynamicTokenPair(userRow, endpoint.getRequiredAppId(), endpoint.getTokenTtlMinutes(), endpoint.getRefreshTokenTtlDays())
                    : null;

                response.setStatus(HttpStatus.OK.value());
                response.setContentType("application/json;charset=UTF-8");
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> respMap = new HashMap<>();
                respMap.put("success", true);
                respMap.put("message", (endpoint.getSuccessMessage() != null && !endpoint.getSuccessMessage().trim().isEmpty())
                        ? endpoint.getSuccessMessage() : "Login berhasil.");
                if (tokenPair != null) {
                    respMap.put("access_token", tokenPair.getAccessToken());
                    respMap.put("refresh_token", tokenPair.getRefreshToken());
                    respMap.put("token_type", "Bearer");
                    respMap.put("expires_in", tokenPair.getExpiresIn());
                }
                respMap.put("user", userRow);
                respMap.put("timestamp", nowTimestamp);

                response.getWriter().write(mapper.writeValueAsString(respMap));
                response.getWriter().flush();
                return;
            }

            // ── Mutation Check (INSERT, UPDATE, DELETE) ────────────────────────────
            String upperSql = sql.trim().toUpperCase();
            boolean isMutation = upperSql.startsWith("INSERT") || upperSql.startsWith("UPDATE") || upperSql.startsWith("DELETE")
                    || (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH") && !upperSql.startsWith("EXPLAIN") && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE")));

            if (isMutation) {
                String[] rawStatements = sql.split(";(?=(?:[^']*'[^']*')*[^']*$)");
                List<String> statements = new ArrayList<>();
                for (String s : rawStatements) {
                    if (s != null && !s.trim().isEmpty()) {
                        statements.add(s.trim());
                    }
                }

                int rowsAffected = 0;
                List<Map<String, Object>> returningRows = new ArrayList<>();
                boolean hasReturning = false;

                if (statements.size() > 1) {
                    org.springframework.jdbc.datasource.DataSourceTransactionManager txManager = 
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
                    org.springframework.transaction.support.DefaultTransactionDefinition def = 
                        new org.springframework.transaction.support.DefaultTransactionDefinition();
                    def.setName("AtomicTx_" + System.currentTimeMillis());
                    def.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED);
                    org.springframework.transaction.TransactionStatus txStatus = txManager.getTransaction(def);
                    try {
                        for (String singleStmt : statements) {
                            boolean isRet = singleStmt.toUpperCase().matches("(?s).*\\bRETURNING\\b.*");
                            if (isRet) {
                                hasReturning = true;
                                List<Map<String, Object>> ret = jdbcTemplate.queryForList(singleStmt, allParams);
                                returningRows = ret;
                                rowsAffected += ret.size();
                            } else {
                                rowsAffected += jdbcTemplate.update(singleStmt, allParams);
                            }
                        }
                        txManager.commit(txStatus);
                    } catch (Exception ex) {
                        txManager.rollback(txStatus);
                        throw ex;
                    }
                } else {
                    boolean isRet = sql.toUpperCase().matches("(?s).*\\bRETURNING\\b.*");
                    if (isRet) {
                        hasReturning = true;
                        List<Map<String, Object>> ret = jdbcTemplate.queryForList(sql, allParams);
                        returningRows = ret;
                        rowsAffected = ret.size();
                    } else {
                        rowsAffected = jdbcTemplate.update(sql, allParams);
                    }
                }

                String operation = "MUTATION";
                if (upperSql.startsWith("INSERT")) operation = "INSERT";
                else if (upperSql.startsWith("UPDATE")) operation = "UPDATE";
                else if (upperSql.startsWith("DELETE")) operation = "DELETE";
                else operation = method;

                String successMsg = endpoint.getSuccessMessage();
                if (successMsg == null || successMsg.trim().isEmpty()) {
                    switch (operation) {
                        case "INSERT": successMsg = "Data berhasil disimpan."; break;
                        case "UPDATE": successMsg = "Data berhasil diperbarui."; break;
                        case "DELETE": successMsg = "Data berhasil dihapus."; break;
                        default: successMsg = "Operasi berhasil dieksekusi."; break;
                    }
                }

                // Broadcast live event via SSE
                try {
                    RealtimeController.broadcastToTopic(endpoint.getEndpointPath(), "DATA_MUTATION", Map.of(
                        "operation", operation,
                        "path", endpoint.getEndpointPath(),
                        "name", endpoint.getName(),
                        "rows_affected", rowsAffected,
                        "timestamp", nowTimestamp
                    ));
                    // Also broadcast to general channel
                    RealtimeController.broadcastToTopic("general", "DATA_MUTATION", Map.of(
                        "operation", operation,
                        "path", endpoint.getEndpointPath(),
                        "name", endpoint.getName(),
                        "rows_affected", rowsAffected,
                        "timestamp", nowTimestamp
                    ));
                } catch (Exception bEx) {
                    // Ignore broadcast error
                }

                response.setStatus(HttpStatus.OK.value());
                response.setContentType("application/json;charset=UTF-8");
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> respMap = new HashMap<>();
                respMap.put("success", true);
                respMap.put("operation", operation);
                respMap.put("rows_affected", rowsAffected);
                respMap.put("message", successMsg);
                respMap.put("timestamp", nowTimestamp);
                if (!uploadMetadata.isEmpty()) {
                    respMap.put("upload", uploadMetadata);
                }
                if (hasReturning) {
                    if (returningRows.size() == 1) {
                        respMap.put("data", returningRows.get(0));
                    } else {
                        respMap.put("data", returningRows);
                    }
                }
                response.getWriter().write(mapper.writeValueAsString(respMap));
                response.getWriter().flush();
                return;
            }

            response.setStatus(HttpStatus.OK.value());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("X-Accel-Buffering", "no");
            response.setHeader("Cache-Control", "no-cache");

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            // Pagination support
            if (endpoint.isEnablePagination()) {
                int limit = 10;
                int offset = 0;
                int page = 1;

                if (allParams.containsKey("limit")) {
                    String val = allParams.get("limit").toString();
                    if (!val.matches("-?\\d+")) {
                        sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of("errors", List.of("Parameter 'limit' must be a valid integer")));
                        return;
                    }
                    limit = Integer.parseInt(val);
                } else if (allParams.containsKey("size")) {
                    String val = allParams.get("size").toString();
                    if (!val.matches("-?\\d+")) {
                        sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of("errors", List.of("Parameter 'size' must be a valid integer")));
                        return;
                    }
                    limit = Integer.parseInt(val);
                }
                
                if (allParams.containsKey("page")) {
                    String val = allParams.get("page").toString();
                    if (!val.matches("-?\\d+")) {
                        sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of("errors", List.of("Parameter 'page' must be a valid integer")));
                        return;
                    }
                    page = Integer.parseInt(val);
                    if (page < 1) page = 1;
                    offset = (page - 1) * limit;
                } else if (allParams.containsKey("offset")) {
                    String val = allParams.get("offset").toString();
                    if (!val.matches("-?\\d+")) {
                        sendJsonError(response, HttpStatus.BAD_REQUEST.value(), Map.of("errors", List.of("Parameter 'offset' must be a valid integer")));
                        return;
                    }
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

                try (com.fasterxml.jackson.core.JsonGenerator gen = mapper.getFactory().createGenerator(response.getOutputStream(), com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
                    gen.writeStartObject(); // {

                    // Pagination metadata
                    gen.writeObjectFieldStart("pagination");
                    gen.writeNumberField("current_page", page);
                    gen.writeNumberField("limit", limit);
                    gen.writeNumberField("offset", offset);
                    gen.writeNumberField("total_records", totalRecords);
                    gen.writeNumberField("total_pages", totalPages);
                    gen.writeEndObject();

                    // Data array start
                    gen.writeArrayFieldStart("data");

                    final String[] colNamesRef = new String[1];
                    final int[] colCountRef = new int[1];
                    final int[] rowCount = new int[]{0};

                    jdbcTemplate.query(paginatedSql, allParams, (java.sql.ResultSet rs) -> {
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
                }
            } else {
                try (com.fasterxml.jackson.core.JsonGenerator gen = mapper.getFactory().createGenerator(response.getOutputStream(), com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
                    gen.writeStartArray(); // [

                    final String[] colNamesRef = new String[1];
                    final int[] colCountRef = new int[1];
                    final int[] rowCount = new int[]{0};

                    jdbcTemplate.query(sql, allParams, (java.sql.ResultSet rs) -> {
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
                }
            }

        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DynamicApiController.class).error("Database execution error: {}", e.getMessage());
            sendJsonError(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), Map.of("error", "Database execution error: " + e.getMessage()));
        }
    }

    private void sendJsonError(jakarta.servlet.http.HttpServletResponse response, int status, Object body) {
        try {
            if (!response.isCommitted()) {
                response.setStatus(status);
                response.setContentType("application/json;charset=UTF-8");
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                response.getWriter().write(mapper.writeValueAsString(body));
                response.getWriter().flush();
            }
        } catch (Exception ignored) {}
    }

    private Object getSafeObject(java.sql.ResultSet rs, int colIdx) throws java.sql.SQLException {
        Object val = rs.getObject(colIdx);
        if (val == null) return null;
        if (val instanceof java.sql.Blob) {
            java.sql.Blob b = (java.sql.Blob) val;
            try {
                byte[] bytes = b.getBytes(1, (int) Math.min(b.length(), 20 * 1024 * 1024));
                return Base64.getEncoder().encodeToString(bytes);
            } catch (Exception ex) {
                return "[BLOB Data: " + b.length() + " bytes]";
            }
        } else if (val instanceof java.sql.Clob) {
            java.sql.Clob c = (java.sql.Clob) val;
            return c.getSubString(1, (int) Math.min(c.length(), 100000));
        } else if (val instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) val);
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
            "X-Real-IP",
            "CF-Connecting-IP",
            "X-Forwarded-For",
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
                // In case of multiple IPs, take the last IP added by trusted proxies, or first if single
                String[] ips = ipList.split(",");
                String resolvedIp = ips[ips.length - 1].trim();
                return normalizeIp(resolvedIp);
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
