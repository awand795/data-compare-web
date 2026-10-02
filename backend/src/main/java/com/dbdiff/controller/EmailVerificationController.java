package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ApiEndpointRepository;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.service.ConnectionManagerService;
import com.dbdiff.service.EmailService;
import com.dbdiff.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class EmailVerificationController {

    private static final Logger logger = LoggerFactory.getLogger(EmailVerificationController.class);

    @Autowired
    private EmailService emailService;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired(required = false)
    private JwtService jwtService;

    private static final Pattern INSERT_TABLE_PATTERN = Pattern.compile("(?i)INSERT\\s+INTO\\s+([a-zA-Z0-9_\\.]+)", Pattern.CASE_INSENSITIVE);

    private String extractTargetTable(ApiEndpoint endpoint) {
        if (endpoint != null && endpoint.getVerificationUserTable() != null && !endpoint.getVerificationUserTable().isBlank()) {
            return endpoint.getVerificationUserTable().trim();
        }
        if (endpoint != null && endpoint.getSqlQuery() != null) {
            Matcher m = INSERT_TABLE_PATTERN.matcher(endpoint.getSqlQuery());
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return "public.pengguna";
    }

    private DataSource getDataSourceForEndpoint(ApiEndpoint endpoint) {
        if (endpoint != null && endpoint.getConnectionId() != null) {
            ConnectionDetails conn = connectionRepository.findById(endpoint.getConnectionId());
            if (conn != null) {
                return connectionManagerService.getDataSource(conn);
            }
        }
        return getFallbackDataSource();
    }

    private DataSource getFallbackDataSource() {
        try {
            Optional<ApiEndpoint> epOpt = apiEndpointRepository.findByPathAndMethod("/kim3/auth/register", "POST");
            if (epOpt.isEmpty()) epOpt = apiEndpointRepository.findByPathAndMethod("/kim3/auth/login", "POST");
            if (epOpt.isPresent() && epOpt.get().getConnectionId() != null) {
                ConnectionDetails conn = connectionRepository.findById(epOpt.get().getConnectionId());
                if (conn != null) return connectionManagerService.getDataSource(conn);
            }
        } catch (Exception ignored) {}

        try {
            ConnectionDetails conn = connectionRepository.findById("1790047562834");
            if (conn != null) return connectionManagerService.getDataSource(conn);
        } catch (Exception ignored) {}

        try {
            for (ConnectionDetails c : connectionRepository.findAll()) {
                if (c.getName() != null && c.getName().toLowerCase().contains("web fleet")) {
                    return connectionManagerService.getDataSource(c);
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String getBaseUrl(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) proto = request.getScheme();
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) host = request.getHeader("Host");
        return proto + "://" + host;
    }

    @GetMapping(value = {"/api/auth/verify-email", "/api/data/kim3/auth/verify-email"}, produces = MediaType.TEXT_HTML_VALUE)
    public void verifyEmail(
            @RequestParam(value = "token", required = false) String token,
            @RequestParam(value = "endpoint_id", required = false) String endpointId,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        response.setContentType("text/html;charset=UTF-8");

        String baseUrl = getBaseUrl(request);
        String defaultLoginUrl = emailService.getFrontendUrl().replaceAll("/+$", "") + "/#login";

        if (token == null || token.trim().isEmpty()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.getWriter().write(renderHtmlPage(false, "Token Verifikasi Tidak Valid", 
                    "Tautan verifikasi yang Anda buka tidak valid atau tidak memiliki token verifikasi.", defaultLoginUrl, "Layanan Keamanan Akun"));
            return;
        }

        // 1. Resolve ApiEndpoint
        ApiEndpoint endpoint = null;
        if (endpointId != null && !endpointId.isBlank()) {
            endpoint = apiEndpointRepository.findById(endpointId.trim()).orElse(null);
        }

        // 2. Resolve target database connection & table dynamically
        List<ApiEndpoint> candidateEndpoints = new ArrayList<>();
        if (endpoint != null) {
            candidateEndpoints.add(endpoint);
        } else {
            // Find all candidate endpoints with authAction = REGISTER or enableEmailVerification = true
            for (ApiEndpoint ep : apiEndpointRepository.findAll()) {
                if ("REGISTER".equalsIgnoreCase(ep.getAuthAction()) || ep.isEnableEmailVerification()) {
                    candidateEndpoints.add(ep);
                }
            }
        }

        if (candidateEndpoints.isEmpty()) {
            response.setStatus(HttpStatus.OK.value());
            response.getWriter().write(renderHtmlPage(false, "Tautan Tidak Dikenali", 
                    "Sistem tidak menemukan konfigurasi verifikasi email yang aktif.", defaultLoginUrl, "Sistem Verifikasi Akun"));
            return;
        }

        boolean verified = false;
        String appName = "Sistem Verifikasi Akun";
        String loginUrl = defaultLoginUrl;

        for (ApiEndpoint ep : candidateEndpoints) {
            DataSource ds = getDataSourceForEndpoint(ep);
            if (ds == null) continue;

            String table = extractTargetTable(ep);
            String tokenCol = (ep.getVerificationTokenColumn() != null && !ep.getVerificationTokenColumn().isBlank()) ? ep.getVerificationTokenColumn().trim() : "verification_token";
            String statusCol = (ep.getVerificationStatusColumn() != null && !ep.getVerificationStatusColumn().isBlank()) ? ep.getVerificationStatusColumn().trim() : "is_verified";
            appName = (ep.getName() != null && !ep.getName().isBlank()) ? ep.getName().trim() : "Sistem Layanan";
            
            if (ep.getVerificationSuccessUrl() != null && !ep.getVerificationSuccessUrl().isBlank()) {
                loginUrl = ep.getVerificationSuccessUrl().trim();
            }

            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);

            try {
                String checkSql = "SELECT * FROM " + table + " WHERE " + tokenCol + " = :token LIMIT 1";
                List<Map<String, Object>> rows = jdbc.queryForList(checkSql, Map.of("token", token.trim()));

                if (!rows.isEmpty()) {
                    Map<String, Object> user = rows.get(0);
                    Object userId = user.get("id");
                    String nama = user.get("nama_lengkap") != null ? user.get("nama_lengkap").toString() : 
                            (user.get("nama") != null ? user.get("nama").toString() : 
                            (user.get("username") != null ? user.get("username").toString() : "Pengguna"));
                    String email = user.get("email") != null ? user.get("email").toString() : "";

                    // Execute update (gracefully handle email_verified_at column presence)
                    if (userId != null) {
                        try {
                            jdbc.update("UPDATE " + table + " SET " + statusCol + " = TRUE, " + tokenCol + " = NULL, email_verified_at = NOW() WHERE id = :id", Map.of("id", userId));
                        } catch (Exception exNoCol) {
                            jdbc.update("UPDATE " + table + " SET " + statusCol + " = TRUE, " + tokenCol + " = NULL WHERE id = :id", Map.of("id", userId));
                        }
                    } else {
                        try {
                            jdbc.update("UPDATE " + table + " SET " + statusCol + " = TRUE, " + tokenCol + " = NULL, email_verified_at = NOW() WHERE " + tokenCol + " = :token", Map.of("token", token.trim()));
                        } catch (Exception exNoCol) {
                            jdbc.update("UPDATE " + table + " SET " + statusCol + " = TRUE, " + tokenCol + " = NULL WHERE " + tokenCol + " = :token", Map.of("token", token.trim()));
                        }
                    }

                    logger.info("Successfully verified email for user '{}' in table '{}' via endpoint '{}'", email, table, ep.getName());

                    response.setStatus(HttpStatus.OK.value());
                    String successMsg = "Selamat, <strong>" + nama + "</strong>! Alamat email Anda (<strong>" + email + "</strong>) telah berhasil diverifikasi.<br><br>Akun Anda kini telah aktif sepenuhnya. Silakan kembali ke halaman login untuk masuk ke sistem.";
                    response.getWriter().write(renderHtmlPage(true, "Email Berhasil Diverifikasi!", successMsg, loginUrl, appName));
                    verified = true;
                    return;
                }
            } catch (Exception ex) {
                logger.warn("Query check failed on table {}: {}", table, ex.getMessage());
            }
        }

        if (!verified) {
            response.setStatus(HttpStatus.OK.value());
            response.getWriter().write(renderHtmlPage(false, "Tautan Sudah Tidak Berlaku / Kadaluarsa", 
                    "Akun Anda kemungkinan sudah terverifikasi sebelumnya atau tautan ini telah kadaluarsa. Silakan langsung masuk ke akun Anda.", loginUrl, appName));
        }
    }

    @PostMapping(value = {"/api/auth/resend-verification", "/api/data/kim3/auth/resend-verification"})
    public ResponseEntity<Map<String, Object>> resendVerification(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(value = "endpoint_id", required = false) String endpointIdParam,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {

        String email = null;
        String endpointId = endpointIdParam;

        if (body != null) {
            if (body.containsKey("email") && body.get("email") != null) {
                email = body.get("email").toString().trim();
            }
            if ((endpointId == null || endpointId.isBlank()) && body.containsKey("endpoint_id") && body.get("endpoint_id") != null) {
                endpointId = body.get("endpoint_id").toString().trim();
            }
        }

        // Try extracting email from JWT token if user already authenticated
        if ((email == null || email.isEmpty()) && authHeader != null && jwtService != null) {
            try {
                String token = authHeader.replace("Bearer ", "").trim();
                Claims claims = jwtService.validateAccessToken(token);
                if (claims != null && claims.get("email") != null) {
                    email = claims.get("email", String.class).trim();
                }
            } catch (Exception ignored) {}
        }

        if (email == null || email.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Alamat email wajib diisi untuk pengiriman ulang link verifikasi."
            ));
        }

        // Resolve candidate endpoint
        List<ApiEndpoint> candidateEndpoints = new ArrayList<>();
        if (endpointId != null && !endpointId.isBlank()) {
            apiEndpointRepository.findById(endpointId.trim()).ifPresent(candidateEndpoints::add);
        }
        if (candidateEndpoints.isEmpty()) {
            for (ApiEndpoint ep : apiEndpointRepository.findAll()) {
                if ("REGISTER".equalsIgnoreCase(ep.getAuthAction()) || ep.isEnableEmailVerification()) {
                    candidateEndpoints.add(ep);
                }
            }
        }
        if (candidateEndpoints.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Konfigurasi verifikasi email belum diaktifkan pada sistem."
            ));
        }

        String baseUrl = getBaseUrl(request);

        for (ApiEndpoint ep : candidateEndpoints) {
            DataSource ds = getDataSourceForEndpoint(ep);
            if (ds == null) continue;

            String table = extractTargetTable(ep);
            String tokenCol = (ep.getVerificationTokenColumn() != null && !ep.getVerificationTokenColumn().isBlank()) ? ep.getVerificationTokenColumn().trim() : "verification_token";
            String statusCol = (ep.getVerificationStatusColumn() != null && !ep.getVerificationStatusColumn().isBlank()) ? ep.getVerificationStatusColumn().trim() : "is_verified";
            String emailCol = (ep.getEmailParam() != null && !ep.getEmailParam().isBlank()) ? ep.getEmailParam().trim() : "email";
            String appName = (ep.getName() != null && !ep.getName().isBlank()) ? ep.getName().trim() : "Layanan Web";

            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);

            try {
                String sql = "SELECT * FROM " + table + " WHERE LOWER(TRIM(" + emailCol + ")) = LOWER(TRIM(:email)) LIMIT 1";
                List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of("email", email));

                if (!rows.isEmpty()) {
                    Map<String, Object> user = rows.get(0);
                    Object isVerifiedObj = user.get(statusCol);

                    boolean isVerified = Boolean.TRUE.equals(isVerifiedObj);
                    if (isVerified) {
                        return ResponseEntity.ok(Map.of(
                                "success", true,
                                "already_verified", true,
                                "message", "Alamat email Anda sudah terverifikasi sebelumnya. Silakan langsung masuk."
                        ));
                    }

                    String newToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
                    String updateSql;
                    if (user.get("id") != null) {
                        updateSql = "UPDATE " + table + " SET " + tokenCol + " = :token WHERE id = :id";
                        jdbc.update(updateSql, Map.of("token", newToken, "id", user.get("id")));
                    } else {
                        updateSql = "UPDATE " + table + " SET " + tokenCol + " = :token WHERE LOWER(TRIM(" + emailCol + ")) = LOWER(TRIM(:email))";
                        jdbc.update(updateSql, Map.of("token", newToken, "email", email));
                    }

                    String recipientName = user.get("nama_lengkap") != null ? user.get("nama_lengkap").toString() : 
                            (user.get("nama") != null ? user.get("nama").toString() : "Pengguna");

                    String verifyPath = (ep.getVerificationEndpointPath() != null && !ep.getVerificationEndpointPath().isBlank())
                            ? ep.getVerificationEndpointPath().trim()
                            : "/api/auth/verify-email";
                    if (!verifyPath.startsWith("/")) verifyPath = "/" + verifyPath;

                    String verifyUrl = baseUrl + verifyPath + "?token=" + newToken + (ep.getId() != null ? "&endpoint_id=" + ep.getId() : "");

                    emailService.sendDynamicVerificationEmailAsync(
                            email,
                            recipientName,
                            newToken,
                            ep.getVerificationMailFrom(),
                            ep.getVerificationEmailSubject(),
                            ep.getVerificationEmailTemplate(),
                            verifyUrl,
                            appName
                    );

                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "message", "Link verifikasi telah berhasil dikirim ulang ke " + email + ". Silakan periksa kotak masuk atau spam email Anda."
                    ));
                }
            } catch (Exception ex) {
                logger.warn("Resend check failed on table {}: {}", table, ex.getMessage());
            }
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "message", "Pengguna dengan email '" + email + "' tidak ditemukan dalam sistem."
        ));
    }

    private String renderHtmlPage(boolean success, String title, String message, String loginUrl, String appName) {
        String iconBg = success ? "#ecfdf5" : "#fffbeb";
        String iconColor = success ? "#059669" : "#d97706";
        String iconSvg = success
                ? "<svg width=\"48\" height=\"48\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"" + iconColor + "\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"></path><polyline points=\"22 4 12 14.01 9 11.01\"></polyline></svg>"
                : "<svg width=\"48\" height=\"48\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"" + iconColor + "\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"></circle><line x1=\"12\" y1=\"8\" x2=\"12\" y2=\"12\"></line><line x1=\"12\" y1=\"16\" x2=\"12.01\" y2=\"16\"></line></svg>";

        String cardBg = success ? "#f0fdf4" : "#fff7ed";
        String cardBorder = success ? "#bbf7d0" : "#fed7aa";
        String cardText = success ? "#166534" : "#9a3412";
        String cardMessage = success
                ? "Akun Anda telah aktif. Silakan kembali ke halaman login untuk masuk ke sistem."
                : "Tautan tidak valid atau telah kedaluwarsa. Silakan lakukan pendaftaran ulang atau hubungi tim bantuan.";

        return "<!DOCTYPE html>\n" +
                "<html lang=\"id\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>" + title + " - " + appName + "</title>\n" +
                "  <style>\n" +
                "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; min-height: 100vh; display: flex; align-items: center; justify-content: center; color: #1e293b; }\n" +
                "    .container { width: 100%; max-width: 520px; background: #ffffff; border-radius: 16px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 10px 25px -5px rgba(0,0,0,0.05), 0 8px 10px -6px rgba(0,0,0,0.01); text-align: center; }\n" +
                "    .header { background: linear-gradient(135deg, #042f2e 0%, #0f766e 100%); padding: 24px 20px; color: #ffffff; }\n" +
                "    .header h2 { margin: 0; font-size: 17px; font-weight: 700; letter-spacing: 0.5px; }\n" +
                "    .header p { margin: 4px 0 0 0; font-size: 12px; color: #99f6e4; }\n" +
                "    .body { padding: 36px 30px; }\n" +
                "    .icon-box { width: 84px; height: 84px; border-radius: 50%; background: " + iconBg + "; display: flex; align-items: center; justify-content: center; margin: 0 auto 24px auto; }\n" +
                "    h1 { font-size: 22px; font-weight: 700; color: #0f172a; margin: 0 0 14px 0; }\n" +
                "    .desc { font-size: 14px; line-height: 1.6; color: #475569; margin: 0 0 24px 0; }\n" +
                "    .info-card { display: inline-block; background-color: " + cardBg + "; border: 1px solid " + cardBorder + "; border-radius: 10px; padding: 14px 20px; font-size: 13.5px; font-weight: 500; color: " + cardText + "; line-height: 1.5; max-width: 90%; }\n" +
                "    .footer { border-top: 1px solid #f1f5f9; padding: 16px; font-size: 12px; color: #94a3b8; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"container\">\n" +
                "    <div class=\"header\">\n" +
                "      <h2>" + appName + "</h2>\n" +
                "      <p>Layanan Autentikasi &amp; Verifikasi Keamanan Akun</p>\n" +
                "    </div>\n" +
                "    <div class=\"body\">\n" +
                "      <div class=\"icon-box\">" + iconSvg + "</div>\n" +
                "      <h1>" + title + "</h1>\n" +
                "      <p class=\"desc\">" + message + "</p>\n" +
                "      <div class=\"info-card\">" + cardMessage + "</div>\n" +
                "    </div>\n" +
                "    <div class=\"footer\">\n" +
                "      &copy; " + java.time.Year.now().getValue() + " " + appName + ". Seluruh hak cipta dilindungi.\n" +
                "    </div>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";
    }
}
