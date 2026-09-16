package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.repository.ApiEndpointRepository;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.service.ApiParameterValidator;
import com.dbdiff.service.ConnectionManagerService;
import com.dbdiff.service.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamicApiControllerAuthTest {

    private ApiParameterValidator apiParameterValidator;
    private ApiEndpointRepository apiEndpointRepository;
    private ConnectionRepository connectionRepository;
    private ConnectionManagerService connectionManagerService;
    private JwtService jwtService;
    private DynamicApiController controller;

    @BeforeEach
    void setUp() {
        apiParameterValidator = mock(ApiParameterValidator.class);
        apiEndpointRepository = mock(ApiEndpointRepository.class);
        connectionRepository = mock(ConnectionRepository.class);
        connectionManagerService = mock(ConnectionManagerService.class);
        jwtService = mock(JwtService.class);

        controller = new DynamicApiController();
        ReflectionTestUtils.setField(controller, "apiParameterValidator", apiParameterValidator);
        ReflectionTestUtils.setField(controller, "apiEndpointRepository", apiEndpointRepository);
        ReflectionTestUtils.setField(controller, "connectionRepository", connectionRepository);
        ReflectionTestUtils.setField(controller, "connectionManagerService", connectionManagerService);
        ReflectionTestUtils.setField(controller, "jwtService", jwtService);

        ApiParameterValidator.ValidationResult validResult = mock(ApiParameterValidator.ValidationResult.class);
        when(validResult.isValid()).thenReturn(true);
        when(validResult.getParams()).thenAnswer(inv -> new HashMap<String, Object>());
        when(apiParameterValidator.validate(any(), any())).thenReturn(validResult);
    }

    @Test
    @DisplayName("Backward Compatibility: Endpoint with static token accepts matching static token")
    void testStaticTokenAccepted() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setEndpointPath("/test-static");
        endpoint.setMethod("GET");
        endpoint.setPublic(false);
        endpoint.setAuthToken("secret-static-token");
        endpoint.setConnectionId("conn-1");

        when(apiEndpointRepository.findByPathAndMethod("/test-static", "GET")).thenReturn(Optional.of(endpoint));
        when(jwtService.validateAccessToken("secret-static-token")).thenThrow(new RuntimeException("Not a JWT"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/data/test-static");
        req.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/api/data/test-static");
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.handleRequest(req, res, Map.of(), null, "Bearer secret-static-token", null);

        assertThat(res.getStatus()).isNotEqualTo(401);
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("JWT Integration: Endpoint accepts valid JWT token and authenticates")
    void testValidJwtAccepted() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setEndpointPath("/fleet-vehicles");
        endpoint.setMethod("GET");
        endpoint.setPublic(false);
        endpoint.setAuthToken(null);
        endpoint.setConnectionId("conn-1");

        when(apiEndpointRepository.findByPathAndMethod("/fleet-vehicles", "GET")).thenReturn(Optional.of(endpoint));

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-cust-456");
        when(claims.get("appId", String.class)).thenReturn("bengkel-kim3");
        when(claims.get("role", String.class)).thenReturn("CUSTOMER");
        when(claims.get("companyId", String.class)).thenReturn("comp-pt-sejahtera");
        when(jwtService.validateAccessToken("valid-user-jwt")).thenReturn(claims);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/data/fleet-vehicles");
        req.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/api/data/fleet-vehicles");
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.handleRequest(req, res, Map.of(), null, "Bearer valid-user-jwt", null);

        assertThat(res.getStatus()).isNotEqualTo(401);
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("Multi-App Isolation: Token from bengkel-kim3 accessing endpoint restricted to bikinpos is rejected 403")
    void testJwtAppIdMismatchRejected() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setEndpointPath("/pos/transactions");
        endpoint.setMethod("GET");
        endpoint.setPublic(false);
        endpoint.setRequiredAppId("bikinpos"); // RESTRICTED TO BIKINPOS!
        endpoint.setConnectionId("conn-1");

        when(apiEndpointRepository.findByPathAndMethod("/pos/transactions", "GET")).thenReturn(Optional.of(endpoint));

        Claims bengkelClaims = mock(Claims.class);
        when(bengkelClaims.getSubject()).thenReturn("user-bengkel-1");
        when(bengkelClaims.get("appId", String.class)).thenReturn("bengkel-kim3"); // DIFFERENT APP!
        when(jwtService.validateAccessToken("bengkel-jwt-token")).thenReturn(bengkelClaims);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/data/pos/transactions");
        req.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/api/data/pos/transactions");
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.handleRequest(req, res, Map.of(), null, "Bearer bengkel-jwt-token", null);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("Token tidak valid untuk endpoint ini");
    }

    @Test
    @DisplayName("Multi-App Matching: Token from bikinpos accessing endpoint restricted to bikinpos is accepted")
    void testJwtAppIdMatchAccepted() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setEndpointPath("/pos/transactions");
        endpoint.setMethod("GET");
        endpoint.setPublic(false);
        endpoint.setRequiredAppId("bikinpos"); // RESTRICTED TO BIKINPOS
        endpoint.setConnectionId("conn-1");

        when(apiEndpointRepository.findByPathAndMethod("/pos/transactions", "GET")).thenReturn(Optional.of(endpoint));

        Claims posClaims = mock(Claims.class);
        when(posClaims.getSubject()).thenReturn("user-cashier-1");
        when(posClaims.get("appId", String.class)).thenReturn("bikinpos"); // MATCHING APP!
        when(posClaims.get("role", String.class)).thenReturn("CASHIER");
        when(jwtService.validateAccessToken("bikinpos-jwt-token")).thenReturn(posClaims);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/data/pos/transactions");
        req.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/api/data/pos/transactions");
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.handleRequest(req, res, Map.of(), null, "Bearer bikinpos-jwt-token", null);

        assertThat(res.getStatus()).isNotEqualTo(401);
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("RBAC: Endpoint with allowedRoles allows user with matching role")
    void testJwtRoleAllowed() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setEndpointPath("/bengkel/spk");
        endpoint.setMethod("GET");
        endpoint.setPublic(false);
        endpoint.setSecurityMode("JWT_AUTH");
        endpoint.setAllowedRoles("ADMIN, MEKANIK, SPV");
        endpoint.setConnectionId("conn-1");

        when(apiEndpointRepository.findByPathAndMethod("/bengkel/spk", "GET")).thenReturn(Optional.of(endpoint));

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-mekanik-10");
        when(claims.get("role", String.class)).thenReturn("MEKANIK");
        when(jwtService.validateAccessToken("mekanik-token")).thenReturn(claims);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/data/bengkel/spk");
        req.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/api/data/bengkel/spk");
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.handleRequest(req, res, Map.of(), null, "Bearer mekanik-token", null);

        assertThat(res.getStatus()).isNotEqualTo(401);
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("RBAC: Endpoint with allowedRoles rejects user with non-matching role with 403")
    void testJwtRoleForbidden() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setEndpointPath("/bengkel/spk");
        endpoint.setMethod("GET");
        endpoint.setPublic(false);
        endpoint.setSecurityMode("JWT_AUTH");
        endpoint.setAllowedRoles("ADMIN, SPV");
        endpoint.setConnectionId("conn-1");

        when(apiEndpointRepository.findByPathAndMethod("/bengkel/spk", "GET")).thenReturn(Optional.of(endpoint));

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-mekanik-10");
        when(claims.get("role", String.class)).thenReturn("MEKANIK"); // Not in ADMIN, SPV!
        when(jwtService.validateAccessToken("mekanik-token")).thenReturn(claims);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/data/bengkel/spk");
        req.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/api/data/bengkel/spk");
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.handleRequest(req, res, Map.of(), null, "Bearer mekanik-token", null);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("tidak diizinkan mengakses endpoint ini");
    }

    @Test
    @DisplayName("Security Mode API_KEY: Rejects JWT token when mode is strictly API_KEY")
    void testApiKeyModeRejectsJwt() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setEndpointPath("/webhook/payment");
        endpoint.setMethod("POST");
        endpoint.setPublic(false);
        endpoint.setSecurityMode("API_KEY");
        endpoint.setAuthToken("static-secret-key-123");
        endpoint.setConnectionId("conn-1");

        when(apiEndpointRepository.findByPathAndMethod("/webhook/payment", "POST")).thenReturn(Optional.of(endpoint));

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-jwt");
        when(jwtService.validateAccessToken("some-jwt-token")).thenReturn(claims);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/data/webhook/payment");
        req.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/api/data/webhook/payment");
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.handleRequest(req, res, Map.of(), null, "Bearer some-jwt-token", null);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("Static API Key");
    }
}
