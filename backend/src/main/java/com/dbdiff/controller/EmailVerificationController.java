package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ApiEndpointRepository;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.service.ConnectionManagerService;
import com.dbdiff.service.EmailService;
import com.dbdiff.service.JwtService;
import io.jsonwebtoken.Claims;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private DataSource getWebFleetDataSource() {
        try {
            java.util.Optional<ApiEndpoint> epOpt = apiEndpointRepository.findByPathAndMethod("/kim3/auth/register", "POST");
            if (epOpt.isEmpty()) {
                epOpt = apiEndpointRepository.findByPathAndMethod("/kim3/auth/login", "POST");
            }
            if (epOpt.isPresent() && epOpt.get().getConnectionId() != null) {
                ConnectionDetails conn = connectionRepository.findById(epOpt.get().getConnectionId());
                if (conn != null) {
                    return connectionManagerService.getDataSource(conn);
                }
            }
        } catch (Exception ignored) {}

        try {
            ConnectionDetails conn = connectionRepository.findById("1790047562834");
            if (conn != null) {
                return connectionManagerService.getDataSource(conn);
            }
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

    @GetMapping(value = {"/api/auth/verify-email", "/api/data/kim3/auth/verify-email"}, produces = MediaType.TEXT_HTML_VALUE)
    public void verifyEmail(
            @RequestParam(value = "token", required = false) String token,
            HttpServletResponse response) throws Exception {

        response.setContentType("text/html;charset=UTF-8");
        String loginUrl = emailService.getFrontendUrl().replaceAll("/+$", "") + "/#login";

        if (token == null || token.trim().isEmpty()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.getWriter().write(renderHtmlPage(false, "Token Verifikasi Tidak Valid", 
                    "Tautan verifikasi yang Anda buka tidak valid atau tidak memiliki token verifikasi.", loginUrl));
            return;
        }

        DataSource ds = getWebFleetDataSource();
        if (ds == null) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.getWriter().write(renderHtmlPage(false, "Gangguan Layanan Database", 
                    "Koneksi database Web Fleet saat ini sedang tidak dapat dijangkau. Silakan coba kembali beberapa saat lagi.", loginUrl));
            return;
        }

        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);

        try {
            String checkSql = "SELECT id, nama_lengkap, email, email_verifikasi FROM public.pengguna WHERE email_verification_token = :token LIMIT 1";
            List<Map<String, Object>> rows = jdbc.queryForList(checkSql, Map.of("token", token.trim()));

            if (rows.isEmpty()) {
                // Token tidak ditemukan. Mungkin sudah terverifikasi sebelumnya
                response.setStatus(HttpStatus.OK.value());
                response.getWriter().write(renderHtmlPage(false, "Tautan Sudah Tidak Berlaku / Kadaluarsa", 
                        "Akun Anda kemungkinan sudah terverifikasi sebelumnya atau tautan ini telah kadaluarsa. Silakan langsung masuk ke akun Web Fleet Anda.", loginUrl));
                return;
            }

            Map<String, Object> user = rows.get(0);
            Object userId = user.get("id");
            String nama = (String) user.get("nama_lengkap");
            String email = (String) user.get("email");

            // Update verification status
            String updateSql = "UPDATE public.pengguna SET email_verifikasi = TRUE, email_verified_at = NOW(), email_verification_token = NULL WHERE id = :id";
            jdbc.update(updateSql, Map.of("id", userId));

            logger.info("Successfully verified email for user ID {} ({})", userId, email);

            response.setStatus(HttpStatus.OK.value());
            String successMsg = "Selamat, <strong>" + (nama != null ? nama : "Mitra") + "</strong>! Alamat email Anda (<strong>" + email + "</strong>) telah berhasil diverifikasi.<br><br>Akun kemitraan Web Fleet Anda kini telah aktif sepenuhnya. Silakan kembali ke halaman login untuk masuk ke sistem.";
            response.getWriter().write(renderHtmlPage(true, "Email Berhasil Diverifikasi!", successMsg, loginUrl));

        } catch (Exception ex) {
            logger.error("Error verifying email token: {}", ex.getMessage(), ex);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.getWriter().write(renderHtmlPage(false, "Terjadi Kesalahan Sistem", 
                    "Gagal memverifikasi email Anda karena gangguan sistem: " + ex.getMessage(), loginUrl));
        }
    }

    @PostMapping(value = {"/api/auth/resend-verification", "/api/data/kim3/auth/resend-verification"})
    public ResponseEntity<Map<String, Object>> resendVerification(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String email = null;
        if (body != null && body.containsKey("email") && body.get("email") != null) {
            email = body.get("email").toString().trim();
        }

        // Jika email tidak ada di body, coba ambil dari token JWT jika user sudah login
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

        DataSource ds = getWebFleetDataSource();
        if (ds == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "message", "Database Web Fleet tidak dapat dijangkau."
            ));
        }

        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);

        try {
            String sql = "SELECT id, nama_lengkap, email, email_verifikasi FROM public.pengguna WHERE LOWER(TRIM(email)) = LOWER(TRIM(:email)) LIMIT 1";
            List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of("email", email));

            if (rows.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "success", false,
                        "message", "Pengguna dengan email '" + email + "' tidak ditemukan dalam sistem."
                ));
            }

            Map<String, Object> user = rows.get(0);
            Boolean isVerified = (Boolean) user.get("email_verifikasi");
            if (Boolean.TRUE.equals(isVerified)) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "already_verified", true,
                        "message", "Alamat email Anda sudah terverifikasi sebelumnya. Silakan langsung masuk."
                ));
            }

            String newToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            String updateSql = "UPDATE public.pengguna SET email_verification_token = :token WHERE id = :id";
            jdbc.update(updateSql, Map.of("token", newToken, "id", user.get("id")));

            String recipientName = user.get("nama_lengkap") != null ? user.get("nama_lengkap").toString() : "Mitra";
            emailService.sendVerificationEmailAsync(email, recipientName, newToken);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Link verifikasi telah berhasil dikirim ulang ke " + email + ". Silakan periksa kotak masuk atau spam email Anda."
            ));

        } catch (Exception ex) {
            logger.error("Error resending verification email: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "message", "Gagal mengirim ulang email verifikasi: " + ex.getMessage()
            ));
        }
    }

    private String renderHtmlPage(boolean success, String title, String message, String loginUrl) {
        String iconBg = success ? "#ecfdf5" : "#fffbeb";
        String iconColor = success ? "#059669" : "#d97706";
        String iconSvg = success
                ? "<svg width=\"48\" height=\"48\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"" + iconColor + "\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M22 11.08V12a10 10 0 1 1-5.93-9.14\"></path><polyline points=\"22 4 12 14.01 9 11.01\"></polyline></svg>"
                : "<svg width=\"48\" height=\"48\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"" + iconColor + "\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"></circle><line x1=\"12\" y1=\"8\" x2=\"12\" y2=\"12\"></line><line x1=\"12\" y1=\"16\" x2=\"12.01\" y2=\"16\"></line></svg>";

        return "<!DOCTYPE html>\n" +
                "<html lang=\"id\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>" + title + " - PT Lotus Pradipta Mulia</title>\n" +
                "  <style>\n" +
                "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; min-height: 100vh; display: flex; align-items: center; justify-content: center; color: #1e293b; }\n" +
                "    .container { width: 100%; max-width: 520px; background: #ffffff; border-radius: 16px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 10px 25px -5px rgba(0,0,0,0.05), 0 8px 10px -6px rgba(0,0,0,0.01); text-align: center; }\n" +
                "    .header { background: linear-gradient(135deg, #042f2e 0%, #0f766e 100%); padding: 24px 20px; color: #ffffff; }\n" +
                "    .header h2 { margin: 0; font-size: 17px; font-weight: 700; letter-spacing: 0.5px; }\n" +
                "    .header p { margin: 4px 0 0 0; font-size: 12px; color: #99f6e4; }\n" +
                "    .body { padding: 36px 30px; }\n" +
                "    .icon-box { width: 84px; height: 84px; border-radius: 50%; background: " + iconBg + "; display: flex; align-items: center; justify-content: center; margin: 0 auto 24px auto; }\n" +
                "    h1 { font-size: 22px; font-weight: 700; color: #0f172a; margin: 0 0 14px 0; }\n" +
                "    .desc { font-size: 14px; line-height: 1.6; color: #475569; margin: 0 0 32px 0; }\n" +
                "    .btn-login { display: inline-flex; align-items: center; justify-content: center; background-color: #0f766e; color: #ffffff !important; text-decoration: none; padding: 14px 36px; font-size: 15px; font-weight: 600; border-radius: 10px; box-shadow: 0 4px 12px rgba(15, 118, 110, 0.25); transition: background 0.15s ease; }\n" +
                "    .btn-login:hover { background-color: #115e59; }\n" +
                "    .footer { border-top: 1px solid #f1f5f9; padding: 16px; font-size: 12px; color: #94a3b8; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"container\">\n" +
                "    <div class=\"header\">\n" +
                "      <h2>PT LOTUS PRADIPTA MULIA</h2>\n" +
                "      <p>Layanan Pemantauan &amp; Tata Kelola Web Fleet Kendaraan</p>\n" +
                "    </div>\n" +
                "    <div class=\"body\">\n" +
                "      <div class=\"icon-box\">" + iconSvg + "</div>\n" +
                "      <h1>" + title + "</h1>\n" +
                "      <p class=\"desc\">" + message + "</p>\n" +
                "      <a href=\"" + loginUrl + "\" class=\"btn-login\">Kembali ke Halaman Login</a>\n" +
                "    </div>\n" +
                "    <div class=\"footer\">\n" +
                "      &copy; 2026 PT Lotus Pradipta Mulia &bull; KIM 3, Medan\n" +
                "    </div>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";
    }
}
