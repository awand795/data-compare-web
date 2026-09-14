package com.dbdiff.service;

import com.dbdiff.model.AuthApp;
import com.dbdiff.model.RefreshToken;
import com.dbdiff.model.User;
import com.dbdiff.repository.RefreshTokenRepository;
import com.dbdiff.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class JwtServiceTest {

    private RefreshTokenRepository refreshTokenRepository;
    private UserRepository userRepository;
    private JwtService jwtService;

    private User sampleUser;
    private AuthApp sampleApp;

    @BeforeEach
    void setUp() {
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        userRepository = mock(UserRepository.class);

        jwtService = new JwtService(refreshTokenRepository, userRepository);
        ReflectionTestUtils.setField(jwtService, "jwtSecret", "test_secret_key_minimum_256_bits_length_for_hmac_sha256");
        ReflectionTestUtils.setField(jwtService, "defaultJwtExpirationMs", 900000L); // 15 mins

        sampleApp = new AuthApp();
        sampleApp.setId("bengkel-kim3");
        sampleApp.setName("Bengkel KIM3");
        sampleApp.setAllowedRoles("CUSTOMER,SECURITY,SA,ADMIN");
        sampleApp.setAccessTokenTtlMinutes(15);
        sampleApp.setRefreshTokenTtlDays(30);
        sampleApp.setIsActive(true);

        sampleUser = new User();
        sampleUser.setId("user-123");
        sampleUser.setAppId("bengkel-kim3");
        sampleUser.setName("Budi Santoso");
        sampleUser.setEmail("budi@customer.com");
        sampleUser.setUserType("CUSTOMER");
        sampleUser.setRole("CUSTOMER");
        sampleUser.setCompanyId("comp-999");
        sampleUser.setIsActive(true);
    }

    @Test
    @DisplayName("issueTokenPair generates valid access token with appId and saves hashed refresh token")
    void testIssueTokenPair() {
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        JwtService.TokenPair pair = jwtService.issueTokenPair(sampleUser, sampleApp);

        assertThat(pair.getAccessToken()).isNotNull().isNotEmpty();
        assertThat(pair.getRefreshToken()).isNotNull().isNotEmpty();
        assertThat(pair.getExpiresIn()).isEqualTo(900); // 15 mins in seconds

        // Validate access token claims
        Claims claims = jwtService.validateAccessToken(pair.getAccessToken());
        assertThat(claims.getSubject()).isEqualTo("user-123");
        assertThat(claims.get("appId", String.class)).isEqualTo("bengkel-kim3");
        assertThat(claims.get("role", String.class)).isEqualTo("CUSTOMER");
        assertThat(claims.get("userType", String.class)).isEqualTo("CUSTOMER");
        assertThat(claims.get("companyId", String.class)).isEqualTo("comp-999");
        assertThat(claims.get("email", String.class)).isEqualTo("budi@customer.com");

        // Verify that the refresh token stored in DB is hashed with app_id, NOT raw
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken savedToken = captor.getValue();
        assertThat(savedToken.getAppId()).isEqualTo("bengkel-kim3");
        assertThat(savedToken.getTokenHash()).isNotEqualTo(pair.getRefreshToken());
        assertThat(savedToken.getTokenHash()).isEqualTo(JwtService.hashToken(pair.getRefreshToken()));
        assertThat(savedToken.isRevoked()).isFalse();
    }

    @Test
    @DisplayName("validateAccessToken throws 401 when token is invalid or expired")
    void testValidateAccessToken_Invalid() {
        assertThatThrownBy(() -> jwtService.validateAccessToken("invalid.jwt.token"))
                .isInstanceOf(JwtService.AuthSecurityException.class)
                .hasMessageContaining("tidak valid");
    }

    @Test
    @DisplayName("refresh rotates token: revokes old and creates new pair")
    void testRefresh_Success() {
        String oldRawToken = "raw-refresh-token-123";
        String oldHash = JwtService.hashToken(oldRawToken);

        RefreshToken oldRecord = new RefreshToken();
        oldRecord.setId("token-id-1");
        oldRecord.setAppId("bengkel-kim3");
        oldRecord.setUserId("user-123");
        oldRecord.setTokenHash(oldHash);
        oldRecord.setExpiresAt(LocalDateTime.now().plusDays(10));
        oldRecord.setRevoked(false);

        when(refreshTokenRepository.findByTokenHash(oldHash)).thenReturn(oldRecord);
        when(userRepository.findById("user-123")).thenReturn(sampleUser);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        JwtService.TokenPair newPair = jwtService.refresh(oldRawToken, sampleApp);

        assertThat(newPair.getAccessToken()).isNotNull();
        assertThat(newPair.getRefreshToken()).isNotNull().isNotEqualTo(oldRawToken);

        // Verify old token was revoked with replaced_by
        verify(refreshTokenRepository).revokeToken(eq("token-id-1"), anyString());
        // Verify new token was saved
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("refresh reuse detection: using already revoked token triggers revokeAllByUserId and throws 401")
    void testRefresh_ReuseDetection() {
        String reusedRawToken = "stolen-or-reused-token";
        String tokenHash = JwtService.hashToken(reusedRawToken);

        RefreshToken revokedRecord = new RefreshToken();
        revokedRecord.setId("token-id-old");
        revokedRecord.setAppId("bengkel-kim3");
        revokedRecord.setUserId("user-123");
        revokedRecord.setTokenHash(tokenHash);
        revokedRecord.setExpiresAt(LocalDateTime.now().plusDays(5));
        revokedRecord.setRevoked(true); // Already revoked!

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(revokedRecord);

        assertThatThrownBy(() -> jwtService.refresh(reusedRawToken, sampleApp))
                .isInstanceOf(JwtService.AuthSecurityException.class)
                .hasMessageContaining("indikasi reuse");

        // CRITICAL: Verify that all tokens for that user were revoked immediately!
        verify(refreshTokenRepository).revokeAllByUserId("user-123");
    }

    @Test
    @DisplayName("hashToken produces deterministic 64-char SHA-256 hex string")
    void testHashToken() {
        String token = "my-secret-token-value";
        String hash1 = JwtService.hashToken(token);
        String hash2 = JwtService.hashToken(token);

        assertThat(hash1).isNotNull().hasSize(64);
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo(token);
    }
}
