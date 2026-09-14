package com.dbdiff.service;

import com.dbdiff.model.AuthApp;
import com.dbdiff.model.RefreshToken;
import com.dbdiff.model.User;
import com.dbdiff.repository.RefreshTokenRepository;
import com.dbdiff.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);
    private static final SecureRandom secureRandom = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Value("${jwt.secret:darkosync_super_secure_jwt_secret_key_change_me_in_production_min_256_bits_2026}")
    private String jwtSecret;

    // Default 15 minutes (900000 ms)
    @Value("${jwt.expiration-ms:900000}")
    private long defaultJwtExpirationMs;

    @Autowired
    public JwtService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
    }

    public static class TokenPair {
        private String accessToken;
        private String refreshToken;
        private long expiresIn;
        private User user;

        public TokenPair() {}

        public TokenPair(String accessToken, String refreshToken, long expiresIn, User user) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
            this.user = user;
        }

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public long getExpiresIn() { return expiresIn; }
        public void setExpiresIn(long expiresIn) { this.expiresIn = expiresIn; }
        public User getUser() { return user; }
        public void setUser(User user) { this.user = user; }
    }

    public static class AuthSecurityException extends RuntimeException {
        private final int status;
        public AuthSecurityException(String message, int status) {
            super(message);
            this.status = status;
        }
        public int getStatus() { return status; }
    }

    private SecretKey getSigningKey() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            throw new RuntimeException("Gagal menginisialisasi JWT secret key", e);
        }
    }

    public static String hashToken(String rawToken) {
        if (rawToken == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }

    public String generateRawRefreshToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return UUID.randomUUID().toString() + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public String generateAccessToken(User user, AuthApp app) {
        long now = System.currentTimeMillis();
        long ttlMinutes = (app != null && app.getAccessTokenTtlMinutes() != null && app.getAccessTokenTtlMinutes() > 0)
                ? app.getAccessTokenTtlMinutes()
                : 15;
        long expirationMs = ttlMinutes * 60 * 1000L;
        String appId = (app != null && app.getId() != null) ? app.getId() : (user.getAppId() != null ? user.getAppId() : "bengkel-kim3");

        return Jwts.builder()
                .subject(user.getId())
                .claim("appId", appId)
                .claim("role", user.getRole() != null ? user.getRole() : "")
                .claim("userType", user.getUserType() != null ? user.getUserType() : "")
                .claim("companyId", user.getCompanyId() != null ? user.getCompanyId() : "")
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMs))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    public Claims validateAccessToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new AuthSecurityException("Token tidak boleh kosong", 401);
        }
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new AuthSecurityException("Access token telah kedaluwarsa", 401);
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthSecurityException("Access token tidak valid: " + e.getMessage(), 401);
        }
    }

    public TokenPair issueTokenPair(User user, AuthApp app) {
        String accessToken = generateAccessToken(user, app);
        String rawRefreshToken = generateRawRefreshToken();
        String tokenHash = hashToken(rawRefreshToken);

        int refreshTtlDays = (app != null && app.getRefreshTokenTtlDays() != null && app.getRefreshTokenTtlDays() > 0)
                ? app.getRefreshTokenTtlDays()
                : 30;

        String appId = (app != null && app.getId() != null) ? app.getId() : (user.getAppId() != null ? user.getAppId() : "bengkel-kim3");

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setId(UUID.randomUUID().toString());
        refreshToken.setAppId(appId);
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(tokenHash);
        refreshToken.setExpiresAt(LocalDateTime.now().plusDays(refreshTtlDays));
        refreshToken.setRevoked(false);
        refreshTokenRepository.save(refreshToken);

        long ttlMinutes = (app != null && app.getAccessTokenTtlMinutes() != null && app.getAccessTokenTtlMinutes() > 0)
                ? app.getAccessTokenTtlMinutes()
                : 15;
        long expiresInSeconds = ttlMinutes * 60L;

        return new TokenPair(accessToken, rawRefreshToken, expiresInSeconds, user);
    }

    public TokenPair refresh(String rawRefreshToken, AuthApp app) {
        if (rawRefreshToken == null || rawRefreshToken.trim().isEmpty()) {
            throw new AuthSecurityException("Refresh token tidak boleh kosong", 400);
        }

        String tokenHash = hashToken(rawRefreshToken.trim());
        RefreshToken tokenRecord = refreshTokenRepository.findByTokenHash(tokenHash);

        if (tokenRecord == null) {
            throw new AuthSecurityException("Refresh token tidak valid atau tidak ditemukan", 401);
        }

        // Validate App ID matching
        if (app != null && tokenRecord.getAppId() != null && !tokenRecord.getAppId().equalsIgnoreCase(app.getId())) {
            throw new AuthSecurityException("Refresh token tidak valid untuk app '" + app.getId() + "'", 401);
        }

        // Reuse detection: Token has already been revoked!
        if (tokenRecord.isRevoked()) {
            refreshTokenRepository.revokeAllByUserId(tokenRecord.getUserId());
            logger.warn("Security Alert: Reuse of revoked refresh token detected for userId: {}. Revoked all user tokens!", tokenRecord.getUserId());
            throw new AuthSecurityException("Refresh token telah kedaluwarsa atau digunakan sebelumnya (indikasi reuse). Semua sesi aktif dicabut demi keamanan. Silakan login kembali.", 401);
        }

        // Expiry check
        if (tokenRecord.getExpiresAt() != null && tokenRecord.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.revokeToken(tokenRecord.getId(), null);
            throw new AuthSecurityException("Refresh token telah kedaluwarsa. Silakan login kembali.", 401);
        }

        User user = userRepository.findById(tokenRecord.getUserId());
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            throw new AuthSecurityException("User tidak ditemukan atau akun dinonaktifkan", 401);
        }

        // Issue new token pair (Token Rotation)
        String newAccessToken = generateAccessToken(user, app);
        String newRawRefreshToken = generateRawRefreshToken();
        String newTokenHash = hashToken(newRawRefreshToken);

        int refreshTtlDays = (app != null && app.getRefreshTokenTtlDays() != null && app.getRefreshTokenTtlDays() > 0)
                ? app.getRefreshTokenTtlDays()
                : 30;

        RefreshToken newTokenRecord = new RefreshToken();
        newTokenRecord.setId(UUID.randomUUID().toString());
        newTokenRecord.setAppId(tokenRecord.getAppId());
        newTokenRecord.setUserId(user.getId());
        newTokenRecord.setTokenHash(newTokenHash);
        newTokenRecord.setExpiresAt(LocalDateTime.now().plusDays(refreshTtlDays));
        newTokenRecord.setRevoked(false);
        refreshTokenRepository.save(newTokenRecord);

        // Revoke the old token and point replaced_by to the new token ID
        refreshTokenRepository.revokeToken(tokenRecord.getId(), newTokenRecord.getId());

        long ttlMinutes = (app != null && app.getAccessTokenTtlMinutes() != null && app.getAccessTokenTtlMinutes() > 0)
                ? app.getAccessTokenTtlMinutes()
                : 15;
        long expiresInSeconds = ttlMinutes * 60L;

        return new TokenPair(newAccessToken, newRawRefreshToken, expiresInSeconds, user);
    }

    public void revokeRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.trim().isEmpty()) return;
        String tokenHash = hashToken(rawRefreshToken.trim());
        refreshTokenRepository.revokeByTokenHash(tokenHash);
    }

    public void revokeAllUserTokens(String userId) {
        if (userId != null && !userId.trim().isEmpty()) {
            refreshTokenRepository.revokeAllByUserId(userId);
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    public void cleanupExpiredTokens() {
        try {
            int deleted = refreshTokenRepository.deleteExpiredAndRevoked();
            if (deleted > 0) {
                logger.info("Cleaned up {} expired and revoked refresh tokens", deleted);
            }
        } catch (Exception e) {
            logger.warn("Scheduled cleanup of refresh tokens failed: {}", e.getMessage());
        }
    }
}
