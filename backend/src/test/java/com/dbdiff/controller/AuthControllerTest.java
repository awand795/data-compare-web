package com.dbdiff.controller;

import com.dbdiff.model.AuthApp;
import com.dbdiff.model.Company;
import com.dbdiff.model.User;
import com.dbdiff.repository.AuthAppRepository;
import com.dbdiff.repository.CompanyRepository;
import com.dbdiff.repository.UserRepository;
import com.dbdiff.service.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private UserRepository userRepository;
    private CompanyRepository companyRepository;
    private AuthAppRepository authAppRepository;
    private JwtService jwtService;
    private PasswordEncoder passwordEncoder;
    private AuthController authController;

    private AuthApp bengkelApp;
    private AuthApp bikinposApp;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        companyRepository = mock(CompanyRepository.class);
        authAppRepository = mock(AuthAppRepository.class);
        jwtService = mock(JwtService.class);
        passwordEncoder = new BCryptPasswordEncoder();

        authController = new AuthController(userRepository, companyRepository, authAppRepository, jwtService, passwordEncoder);

        bengkelApp = new AuthApp();
        bengkelApp.setId("bengkel-kim3");
        bengkelApp.setName("Bengkel KIM3");
        bengkelApp.setAllowedRoles("CUSTOMER,SECURITY,SA,FOREMAN,MEKANIK,WAREHOUSE,ADMIN_INVOICE,ADMIN");
        bengkelApp.setAccessTokenTtlMinutes(15);
        bengkelApp.setRefreshTokenTtlDays(30);
        bengkelApp.setIsActive(true);

        bikinposApp = new AuthApp();
        bikinposApp.setId("bikinpos");
        bikinposApp.setName("BikinPOS System");
        bikinposApp.setAllowedRoles("CUSTOMER,CASHIER,SUPERVISOR,ADMIN");
        bikinposApp.setAccessTokenTtlMinutes(30);
        bikinposApp.setRefreshTokenTtlDays(60);
        bikinposApp.setIsActive(true);

        when(authAppRepository.findById("bengkel-kim3")).thenReturn(bengkelApp);
        when(authAppRepository.findById("bikinpos")).thenReturn(bikinposApp);
    }

    @Test
    @DisplayName("register-customer successfully registers new customer scoped to appId")
    void testRegisterCustomer_Success() {
        when(userRepository.findByAppIdAndEmail("bengkel-kim3", "customer@fleet.com")).thenReturn(null);
        when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueTokenPair(any(User.class), eq(bengkelApp))).thenReturn(
                new JwtService.TokenPair("mock-access-token", "mock-refresh-token", 900, null)
        );

        Map<String, Object> body = Map.of(
                "name", "Fleet Admin",
                "email", "customer@fleet.com",
                "password", "secret12345",
                "companyName", "PT Logistik Maju"
        );

        ResponseEntity<?> response = authController.registerCustomer("bengkel-kim3", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> respBody = (Map<?, ?>) response.getBody();
        assertThat(respBody.get("success")).isEqualTo(true);
        assertThat(respBody.get("accessToken")).isEqualTo("mock-access-token");
        assertThat(respBody.get("refreshToken")).isEqualTo("mock-refresh-token");

        verify(userRepository).save(argThat(user ->
                "bengkel-kim3".equals(user.getAppId()) &&
                passwordEncoder.matches("secret12345", user.getPasswordHash()) &&
                "CUSTOMER".equals(user.getUserType()) &&
                "CUSTOMER".equals(user.getRole())
        ));
    }

    @Test
    @DisplayName("Multi-App: Same email can register in both bengkel-kim3 and bikinpos without conflict")
    void testRegisterCustomer_SameEmailDifferentApps() {
        when(userRepository.findByAppIdAndEmail("bengkel-kim3", "owner@toko.com")).thenReturn(null);
        when(userRepository.findByAppIdAndEmail("bikinpos", "owner@toko.com")).thenReturn(null);
        when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueTokenPair(any(User.class), any(AuthApp.class))).thenReturn(
                new JwtService.TokenPair("token1", "ref1", 900, null)
        );

        Map<String, Object> body = Map.of(
                "name", "Owner Toko",
                "email", "owner@toko.com",
                "password", "password123"
        );

        ResponseEntity<?> resp1 = authController.registerCustomer("bengkel-kim3", body);
        ResponseEntity<?> resp2 = authController.registerCustomer("bikinpos", body);

        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("register-customer rejects duplicate email in the SAME app with 409 Conflict")
    void testRegisterCustomer_DuplicateEmailInSameApp() {
        User existing = new User();
        existing.setEmail("existing@fleet.com");
        when(userRepository.findByAppIdAndEmail("bengkel-kim3", "existing@fleet.com")).thenReturn(existing);

        Map<String, Object> body = Map.of(
                "name", "Fleet Admin",
                "email", "existing@fleet.com",
                "password", "secret12345"
        );

        ResponseEntity<?> response = authController.registerCustomer("bengkel-kim3", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<?, ?> respBody = (Map<?, ?>) response.getBody();
        assertThat(respBody.get("error").toString()).contains("Email sudah terdaftar di app ini");
    }

    @Test
    @DisplayName("register-staff without ADMIN role returns 403 Forbidden")
    void testRegisterStaff_ForbiddenForNonAdmin() {
        Claims nonAdminClaims = mock(Claims.class);
        when(nonAdminClaims.get("role", String.class)).thenReturn("SA");
        when(nonAdminClaims.get("appId", String.class)).thenReturn("bengkel-kim3");
        when(jwtService.validateAccessToken("non-admin-token")).thenReturn(nonAdminClaims);

        Map<String, Object> body = Map.of(
                "name", "Foreman Budi",
                "email", "foreman@bengkel.com",
                "password", "secret12345",
                "role", "FOREMAN"
        );

        ResponseEntity<?> response = authController.registerStaff("bengkel-kim3", "Bearer non-admin-token", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("register-staff with ADMIN role from DIFFERENT app returns 403 Forbidden")
    void testRegisterStaff_ForbiddenDifferentApp() {
        Claims adminFromOtherApp = mock(Claims.class);
        when(adminFromOtherApp.get("role", String.class)).thenReturn("ADMIN");
        when(adminFromOtherApp.get("appId", String.class)).thenReturn("bikinpos"); // DIFFERENT APP!
        when(jwtService.validateAccessToken("bikinpos-admin-token")).thenReturn(adminFromOtherApp);

        Map<String, Object> body = Map.of(
                "name", "Foreman Budi",
                "email", "foreman@bengkel.com",
                "password", "secret12345",
                "role", "FOREMAN"
        );

        ResponseEntity<?> response = authController.registerStaff("bengkel-kim3", "Bearer bikinpos-admin-token", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<?, ?> respBody = (Map<?, ?>) response.getBody();
        assertThat(respBody.get("error").toString()).contains("Hanya role ADMIN di app 'bengkel-kim3'");
    }

    @Test
    @DisplayName("login succeeds with valid credentials for specific app")
    void testLogin() {
        User user = new User();
        user.setId("user-1");
        user.setAppId("bengkel-kim3");
        user.setName("John");
        user.setEmail("john@test.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        user.setIsActive(true);
        user.setRole("SA");
        user.setUserType("STAFF");

        when(userRepository.findByAppIdAndEmail("bengkel-kim3", "john@test.com")).thenReturn(user);
        when(jwtService.issueTokenPair(user, bengkelApp)).thenReturn(
                new JwtService.TokenPair("mock-access", "mock-refresh", 900, user)
        );

        ResponseEntity<?> okResp = authController.login("bengkel-kim3", Map.of("email", "john@test.com", "password", "correct-password"));
        assertThat(okResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> okBody = (Map<?, ?>) okResp.getBody();
        assertThat(okBody.get("accessToken")).isEqualTo("mock-access");
    }
}
