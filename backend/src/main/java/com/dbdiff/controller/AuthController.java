package com.dbdiff.controller;

import com.dbdiff.model.AuthApp;
import com.dbdiff.model.Company;
import com.dbdiff.model.User;
import com.dbdiff.repository.AuthAppRepository;
import com.dbdiff.repository.CompanyRepository;
import com.dbdiff.repository.UserRepository;
import com.dbdiff.service.JwtService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final AuthAppRepository authAppRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthController(UserRepository userRepository,
                          CompanyRepository companyRepository,
                          AuthAppRepository authAppRepository,
                          JwtService jwtService,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.authAppRepository = authAppRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return null;
    }

    private AuthApp getActiveAppOrThrow(String appId) {
        if (appId == null || appId.trim().isEmpty()) {
            throw new JwtService.AuthSecurityException("App ID diperlukan", 400);
        }
        AuthApp app = authAppRepository.findById(appId.trim().toLowerCase());
        if (app == null || !Boolean.TRUE.equals(app.getIsActive())) {
            throw new JwtService.AuthSecurityException("Auth App '" + appId + "' tidak ditemukan atau nonaktif", 404);
        }
        return app;
    }

    // ── POST /api/auth/{appId}/register-customer ─────────────────────────────
    @PostMapping("/{appId}/register-customer")
    public ResponseEntity<?> registerCustomer(@PathVariable("appId") String appId, @RequestBody Map<String, Object> body) {
        try {
            AuthApp app = getActiveAppOrThrow(appId);

            if (!app.isRoleAllowed("CUSTOMER")) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Role CUSTOMER tidak diizinkan untuk app ini"));
            }

            String name = (String) body.get("name");
            String email = (String) body.get("email");
            String password = (String) body.get("password");
            String phone = (String) body.get("phone");
            String companyId = (String) body.get("companyId");
            String companyName = (String) body.get("companyName");
            String companyPhone = (String) body.get("companyPhone");
            String companyAddress = (String) body.get("companyAddress");

            if (name == null || name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Nama wajib diisi"));
            }
            if (email == null || email.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Email wajib diisi"));
            }
            email = email.trim().toLowerCase();
            if (!email.contains("@") || !email.contains(".")) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Format email tidak valid"));
            }
            if (password == null || password.length() < 8) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Password minimal 8 karakter"));
            }

            // Per-app email uniqueness check!
            if (userRepository.findByAppIdAndEmail(app.getId(), email) != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("success", false, "error", "Email sudah terdaftar di app ini"));
            }

            // Company handling scoped to appId
            if (companyId != null && !companyId.trim().isEmpty()) {
                Company existing = companyRepository.findByIdAndAppId(companyId.trim(), app.getId());
                if (existing == null) {
                    return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Perusahaan dengan ID tersebut tidak ditemukan di app ini"));
                }
            } else if (companyName != null && !companyName.trim().isEmpty()) {
                Company existing = companyRepository.findByNameAndAppId(companyName.trim(), app.getId());
                if (existing != null) {
                    companyId = existing.getId();
                } else {
                    Company newComp = new Company(
                            UUID.randomUUID().toString(),
                            app.getId(),
                            companyName.trim(),
                            companyPhone != null ? companyPhone.trim() : phone,
                            email,
                            companyAddress != null ? companyAddress.trim() : null,
                            null
                    );
                    companyRepository.save(newComp);
                    companyId = newComp.getId();
                }
            } else {
                Company defaultComp = new Company(
                        UUID.randomUUID().toString(),
                        app.getId(),
                        name.trim(),
                        phone,
                        email,
                        null,
                        null
                );
                companyRepository.save(defaultComp);
                companyId = defaultComp.getId();
            }

            User user = new User();
            user.setId(UUID.randomUUID().toString());
            user.setAppId(app.getId());
            user.setUserType("CUSTOMER");
            user.setRole("CUSTOMER");
            user.setCompanyId(companyId);
            user.setName(name.trim());
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setPhone(phone != null ? phone.trim() : null);
            user.setIsActive(true);

            User savedUser = userRepository.save(user);
            JwtService.TokenPair tokenPair = jwtService.issueTokenPair(savedUser, app);

            Map<String, Object> userData = new HashMap<>();
            userData.put("id", savedUser.getId());
            userData.put("appId", savedUser.getAppId());
            userData.put("name", savedUser.getName());
            userData.put("email", savedUser.getEmail());
            userData.put("role", savedUser.getRole());
            userData.put("userType", savedUser.getUserType());
            userData.put("companyId", savedUser.getCompanyId());
            userData.put("phone", savedUser.getPhone());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Registrasi customer berhasil");
            response.put("accessToken", tokenPair.getAccessToken());
            response.put("refreshToken", tokenPair.getRefreshToken());
            response.put("expiresIn", tokenPair.getExpiresIn());
            response.put("user", userData);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (JwtService.AuthSecurityException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error saat registrasi customer", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── POST /api/auth/{appId}/register-staff (WAJIB ROLE ADMIN DI APP YANG SAMA) ──
    @PostMapping("/{appId}/register-staff")
    public ResponseEntity<?> registerStaff(@PathVariable("appId") String appId,
                                          @RequestHeader(value = "Authorization", required = false) String authHeader,
                                          @RequestBody Map<String, Object> body) {
        try {
            AuthApp app = getActiveAppOrThrow(appId);

            // Validasi JWT dan Role ADMIN di app yang sama
            String token = extractBearerToken(authHeader);
            if (token == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "Akses ditolak: Header Authorization Bearer diperlukan"));
            }

            Claims claims;
            try {
                claims = jwtService.validateAccessToken(token);
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "Akses ditolak: Token tidak valid atau kedaluwarsa"));
            }

            String callerRole = claims.get("role", String.class);
            String callerAppId = claims.get("appId", String.class);
            if (!"ADMIN".equalsIgnoreCase(callerRole) || callerAppId == null || !callerAppId.equalsIgnoreCase(app.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "Akses ditolak: Hanya role ADMIN di app '" + app.getId() + "' yang dapat mendaftarkan staff"));
            }

            String name = (String) body.get("name");
            String email = (String) body.get("email");
            String password = (String) body.get("password");
            String role = (String) body.get("role");
            String phone = (String) body.get("phone");

            if (name == null || name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Nama wajib diisi"));
            }
            if (email == null || email.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Email wajib diisi"));
            }
            email = email.trim().toLowerCase();
            if (!email.contains("@") || !email.contains(".")) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Format email tidak valid"));
            }
            if (password == null || password.length() < 8) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Password minimal 8 karakter"));
            }
            if (role == null || role.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Role staff wajib diisi"));
            }
            role = role.trim().toUpperCase();
            if (!app.isRoleAllowed(role)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Role tidak diizinkan untuk app ini. Pilihan: " + app.getAllowedRoles()
                ));
            }

            if (userRepository.findByAppIdAndEmail(app.getId(), email) != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("success", false, "error", "Email sudah terdaftar di app ini"));
            }

            User user = new User();
            user.setId(UUID.randomUUID().toString());
            user.setAppId(app.getId());
            user.setUserType("STAFF");
            user.setRole(role);
            user.setCompanyId(null);
            user.setName(name.trim());
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setPhone(phone != null ? phone.trim() : null);
            user.setIsActive(true);

            User savedUser = userRepository.save(user);

            Map<String, Object> userData = new HashMap<>();
            userData.put("id", savedUser.getId());
            userData.put("appId", savedUser.getAppId());
            userData.put("name", savedUser.getName());
            userData.put("email", savedUser.getEmail());
            userData.put("role", savedUser.getRole());
            userData.put("userType", savedUser.getUserType());
            userData.put("phone", savedUser.getPhone());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Registrasi staff berhasil");
            response.put("user", userData);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (JwtService.AuthSecurityException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error saat registrasi staff", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── POST /api/auth/{appId}/login ─────────────────────────────────────────
    @PostMapping("/{appId}/login")
    public ResponseEntity<?> login(@PathVariable("appId") String appId, @RequestBody Map<String, Object> body) {
        try {
            AuthApp app = getActiveAppOrThrow(appId);

            String email = (String) body.get("email");
            String password = (String) body.get("password");

            if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Email dan password wajib diisi"
                ));
            }

            email = email.trim().toLowerCase();
            User user = userRepository.findByAppIdAndEmail(app.getId(), email);

            if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "success", false,
                        "error", "Email atau password salah"
                ));
            }

            if (!Boolean.TRUE.equals(user.getIsActive())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "Akun Anda dinonaktifkan. Silakan hubungi administrator."
                ));
            }

            JwtService.TokenPair tokenPair = jwtService.issueTokenPair(user, app);

            Map<String, Object> userData = new HashMap<>();
            userData.put("id", user.getId());
            userData.put("appId", user.getAppId());
            userData.put("name", user.getName());
            userData.put("email", user.getEmail());
            userData.put("role", user.getRole());
            userData.put("userType", user.getUserType());
            userData.put("companyId", user.getCompanyId());

            Map<String, Object> response = new HashMap<>();
            response.put("accessToken", tokenPair.getAccessToken());
            response.put("refreshToken", tokenPair.getRefreshToken());
            response.put("expiresIn", tokenPair.getExpiresIn());
            response.put("user", userData);

            return ResponseEntity.ok(response);
        } catch (JwtService.AuthSecurityException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error saat login", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── POST /api/auth/{appId}/refresh ───────────────────────────────────────
    @PostMapping("/{appId}/refresh")
    public ResponseEntity<?> refresh(@PathVariable("appId") String appId, @RequestBody Map<String, Object> body) {
        try {
            AuthApp app = getActiveAppOrThrow(appId);

            String refreshToken = (String) body.get("refreshToken");
            if (refreshToken == null || refreshToken.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Refresh token wajib diisi"
                ));
            }

            JwtService.TokenPair tokenPair = jwtService.refresh(refreshToken.trim(), app);
            User user = tokenPair.getUser();

            Map<String, Object> userData = new HashMap<>();
            if (user != null) {
                userData.put("id", user.getId());
                userData.put("appId", user.getAppId());
                userData.put("name", user.getName());
                userData.put("email", user.getEmail());
                userData.put("role", user.getRole());
                userData.put("userType", user.getUserType());
                userData.put("companyId", user.getCompanyId());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("accessToken", tokenPair.getAccessToken());
            response.put("refreshToken", tokenPair.getRefreshToken());
            response.put("expiresIn", tokenPair.getExpiresIn());
            if (!userData.isEmpty()) {
                response.put("user", userData);
            }

            return ResponseEntity.ok(response);
        } catch (JwtService.AuthSecurityException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Error saat refresh token", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── POST /api/auth/{appId}/logout ────────────────────────────────────────
    @PostMapping("/{appId}/logout")
    public ResponseEntity<?> logout(@PathVariable("appId") String appId, @RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body != null && body.containsKey("refreshToken")) {
                String refreshToken = (String) body.get("refreshToken");
                jwtService.revokeRefreshToken(refreshToken);
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "Logout berhasil"));
        } catch (Exception e) {
            logger.error("Error saat logout", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── GET /api/auth/{appId}/me ─────────────────────────────────────────────
    @GetMapping("/{appId}/me")
    public ResponseEntity<?> getCurrentUser(@PathVariable("appId") String appId,
                                           @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            AuthApp app = getActiveAppOrThrow(appId);

            String token = extractBearerToken(authHeader);
            if (token == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "error", "Access token tidak ditemukan"));
            }

            Claims claims;
            try {
                claims = jwtService.validateAccessToken(token);
            } catch (JwtService.AuthSecurityException e) {
                return ResponseEntity.status(e.getStatus()).body(Map.of("success", false, "error", e.getMessage()));
            }

            String tokenAppId = claims.get("appId", String.class);
            if (tokenAppId == null || !tokenAppId.equalsIgnoreCase(app.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "Token tidak valid untuk app '" + app.getId() + "'"));
            }

            String userId = claims.getSubject();
            User user = userRepository.findByAppIdAndId(app.getId(), userId);
            if (user == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "User tidak ditemukan"));
            }

            Map<String, Object> data = new HashMap<>();
            data.put("id", user.getId());
            data.put("appId", user.getAppId());
            data.put("name", user.getName());
            data.put("email", user.getEmail());
            data.put("role", user.getRole());
            data.put("userType", user.getUserType());
            data.put("companyId", user.getCompanyId());
            data.put("phone", user.getPhone());
            data.put("isActive", user.getIsActive());
            data.put("createdAt", user.getCreatedAt());

            if (user.getCompanyId() != null && !user.getCompanyId().trim().isEmpty()) {
                Company comp = companyRepository.findByIdAndAppId(user.getCompanyId(), app.getId());
                if (comp != null) {
                    data.put("company", comp);
                }
            }

            return ResponseEntity.ok(data);
        } catch (JwtService.AuthSecurityException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error saat mengambil data user /me", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── POST /api/auth/{appId}/change-password ───────────────────────────────
    @PostMapping("/{appId}/change-password")
    public ResponseEntity<?> changePassword(@PathVariable("appId") String appId,
                                           @RequestHeader(value = "Authorization", required = false) String authHeader,
                                           @RequestBody Map<String, Object> body) {
        try {
            AuthApp app = getActiveAppOrThrow(appId);

            String token = extractBearerToken(authHeader);
            if (token == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "error", "Access token tidak ditemukan"));
            }

            Claims claims;
            try {
                claims = jwtService.validateAccessToken(token);
            } catch (JwtService.AuthSecurityException e) {
                return ResponseEntity.status(e.getStatus()).body(Map.of("success", false, "error", e.getMessage()));
            }

            String tokenAppId = claims.get("appId", String.class);
            if (tokenAppId == null || !tokenAppId.equalsIgnoreCase(app.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "Token tidak valid untuk app '" + app.getId() + "'"));
            }

            String currentPassword = (String) body.get("currentPassword");
            String newPassword = (String) body.get("newPassword");

            if (currentPassword == null || currentPassword.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Password saat ini wajib diisi"));
            }
            if (newPassword == null || newPassword.length() < 8) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Password baru minimal 8 karakter"));
            }

            String userId = claims.getSubject();
            User user = userRepository.findByAppIdAndId(app.getId(), userId);
            if (user == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "User tidak ditemukan"));
            }

            if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Password saat ini tidak cocok"));
            }

            // Update password
            userRepository.updatePassword(userId, passwordEncoder.encode(newPassword));

            // Revoke ALL refresh tokens for this user
            jwtService.revokeAllUserTokens(userId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Password berhasil diperbarui. Silakan login kembali dengan password baru."
            ));
        } catch (JwtService.AuthSecurityException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error saat ganti password", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── Backward Compatibility aliases for default app (bengkel-kim3) ───────
    @PostMapping("/register-customer")
    public ResponseEntity<?> registerCustomerDefault(@RequestBody Map<String, Object> body) {
        return registerCustomer("bengkel-kim3", body);
    }

    @PostMapping("/register-staff")
    public ResponseEntity<?> registerStaffDefault(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                 @RequestBody Map<String, Object> body) {
        return registerStaff("bengkel-kim3", authHeader, body);
    }

    @PostMapping("/login")
    public ResponseEntity<?> loginDefault(@RequestBody Map<String, Object> body) {
        return login("bengkel-kim3", body);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshDefault(@RequestBody Map<String, Object> body) {
        return refresh("bengkel-kim3", body);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logoutDefault(@RequestBody(required = false) Map<String, Object> body) {
        return logout("bengkel-kim3", body);
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUserDefault(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        return getCurrentUser("bengkel-kim3", authHeader);
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePasswordDefault(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                   @RequestBody Map<String, Object> body) {
        return changePassword("bengkel-kim3", authHeader, body);
    }
}
