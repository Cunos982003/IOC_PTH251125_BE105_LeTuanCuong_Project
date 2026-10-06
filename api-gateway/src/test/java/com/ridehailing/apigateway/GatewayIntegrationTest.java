package com.ridehailing.apigateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewayIntegrationTest {

    static WireMockServer userService;
    static WireMockServer dispatchService;
    static WireMockServer paymentService;
    static WireMockServer pricingService;

    static final String JWT_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    static final String INTERNAL_KEY = "test-internal-key";

    @Autowired
    MockMvc mockMvc;

    static {
        userService = new WireMockServer(0);
        userService.start();

        dispatchService = new WireMockServer(0);
        dispatchService.start();

        paymentService = new WireMockServer(0);
        paymentService.start();

        pricingService = new WireMockServer(0);
        pricingService.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("USER_SERVICE_URL", () -> "http://localhost:" + userService.port());
        registry.add("DISPATCH_SERVICE_URL", () -> "http://localhost:" + dispatchService.port());
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:" + paymentService.port());
        registry.add("PRICING_SERVICE_URL", () -> "http://localhost:" + pricingService.port());
        registry.add("JWT_SECRET", () -> JWT_SECRET);
        registry.add("INTERNAL_KEY", () -> INTERNAL_KEY);
    }

    @BeforeAll
    static void setupWireMock() {
        // Already started in static block
    }

    @AfterAll
    static void teardownWireMock() {
        if (userService != null) userService.stop();
        if (dispatchService != null) dispatchService.stop();
        if (paymentService != null) paymentService.stop();
        if (pricingService != null) pricingService.stop();
    }

    @BeforeEach
    void resetWireMock() {
        userService.resetAll();
        dispatchService.resetAll();
        paymentService.resetAll();
        pricingService.resetAll();
    }

    @Test
    void testInternalPathsBlocked() throws Exception {
        // /internal/** should return 404 regardless of authentication
        mockMvc.perform(get("/internal/users/1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void testMissingTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/balance"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void testInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/balance")
                .header("Authorization", "Bearer invalid.token.here"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void testExpiredTokenReturns401() throws Exception {
        String expiredToken = generateToken(1001L, "CUSTOMER", Instant.now().minusSeconds(3600));

        mockMvc.perform(get("/api/v1/wallet/balance")
                .header("Authorization", "Bearer " + expiredToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void testCustomerCannotAccessDriverEndpoints() throws Exception {
        String customerToken = generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/v1/trips/arrive")
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tripId\":\"123\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void testDriverCannotCreateRides() throws Exception {
        String driverToken = generateToken(2001L, "DRIVER", Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/v1/rides")
                .header("Authorization", "Bearer " + driverToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickupLat\":10.0,\"pickupLng\":106.0}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void testClientSuppliedHeadersOverwritten() throws Exception {
        String customerToken = generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600));

        // Mock payment service to echo back headers
        paymentService.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/wallet/balance"))
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"balance\":100000}")));

        mockMvc.perform(get("/api/v1/wallet/balance")
                .header("Authorization", "Bearer " + customerToken)
                .header("X-User-Id", "9999")
                .header("X-User-Role", "ADMIN")
                .header("X-Internal-Key", "fake-key"))
            .andExpect(status().isOk());

        // Verify that gateway overwrote client-supplied headers
        paymentService.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/wallet/balance"))
            .withHeader("X-User-Id", WireMock.equalTo("1001"))
            .withHeader("X-User-Role", WireMock.equalTo("CUSTOMER"))
            .withHeader("X-Internal-Key", WireMock.equalTo(INTERNAL_KEY)));
    }

    @Test
    void testIdempotencyKeyForwarded() throws Exception {
        String customerToken = generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600));
        String idempotencyKey = "test-idempotency-123";

        dispatchService.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v1/rides"))
            .willReturn(WireMock.aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"tripId\":\"abc\",\"status\":\"MATCHING\"}")));

        mockMvc.perform(post("/api/v1/rides")
                .header("Authorization", "Bearer " + customerToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickupLat\":10.0,\"pickupLng\":106.0,\"dropoffLat\":10.1,\"dropoffLng\":106.1,\"idempotencyKey\":\"" + idempotencyKey + "\"}"))
            .andExpect(status().isCreated());

        dispatchService.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/rides"))
            .withHeader("Idempotency-Key", WireMock.equalTo(idempotencyKey)));
    }

    @Test
    void testRateLimitExceeded() throws Exception {
        userService.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v1/auth/login"))
            .willReturn(WireMock.aResponse().withStatus(200)));
        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\":\"+84901234567\",\"password\":\"pass\"}"))
                .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
                .header("X-Forwarded-For", "192.0.2.99"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        userService.verify(50, WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/auth/login")));
    }

    @Test
    void testUpstreamServiceTimeoutReturns504() throws Exception {
        paymentService.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/wallet/balance"))
            .willReturn(WireMock.aResponse().withFixedDelay(1500).withBody("{}")));

        mockMvc.perform(get("/api/v1/wallet/balance")
                .header("Authorization", "Bearer " + generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600))))
            .andExpect(status().isGatewayTimeout())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("GATEWAY_TIMEOUT"));
    }

    @Test
    void testUpstreamServiceDownReturns502() throws Exception {
        int port = paymentService.port();
        paymentService.stop();
        try {
            mockMvc.perform(get("/api/v1/wallet/balance")
                    .header("Authorization", "Bearer " + generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600))))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("BAD_GATEWAY"));
        } finally {
            // The Spring context retains the original URL, so the replacement must keep the same port.
            paymentService = new WireMockServer(port);
            paymentService.start();
        }
    }

    @Test
    void testCreateRideHasLongerReadTimeout() throws Exception {
        dispatchService.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v1/rides"))
            .willReturn(WireMock.aResponse().withStatus(201).withFixedDelay(1000).withBody("{}")));
        mockMvc.perform(post("/api/v1/rides")
                .header("Authorization", "Bearer " + generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600)))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated());
    }

    @Test
    void testOversizedBodyReturns413() throws Exception {
        mockMvc.perform(post("/api/v1/rides")
                .header("Authorization", "Bearer " + generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600)))
                .contentType(MediaType.APPLICATION_JSON).content(new byte[65537]))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        dispatchService.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void testBodyAtLimitIsForwarded() throws Exception {
        dispatchService.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v1/rides"))
            .willReturn(WireMock.aResponse().withStatus(201)));
        mockMvc.perform(post("/api/v1/rides")
                .header("Authorization", "Bearer " + generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600)))
                .contentType(MediaType.APPLICATION_JSON).content("a".repeat(65536)))
            .andExpect(status().isCreated());
        dispatchService.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/rides"))
            .withRequestBody(WireMock.equalTo("a".repeat(65536))));
    }

    @Test
    void testMethodQueryBodyAndRequestIdForwarded() throws Exception {
        userService.stubFor(WireMock.put(WireMock.urlEqualTo("/api/v1/users/me?value=a%2Fb&value=%2B"))
            .willReturn(WireMock.aResponse().withStatus(200).withBody("{}")));
        mockMvc.perform(put(java.net.URI.create("/api/v1/users/me?value=a%2Fb&value=%2B"))
                .header("Authorization", "Bearer " + generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600)))
                .header("X-Request-Id", "trace-123")
                .header("Accept", "application/json")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Test\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", "trace-123"));
        userService.verify(WireMock.putRequestedFor(WireMock.urlEqualTo("/api/v1/users/me?value=a%2Fb&value=%2B"))
            .withHeader("X-Request-Id", WireMock.equalTo("trace-123"))
            .withHeader("Accept", WireMock.equalTo("application/json"))
            .withRequestBody(WireMock.equalTo("{\"name\":\"Test\"}")));
    }

    @Test
    void testPublicAuthStripsIdentityAndInjectsInternalKey() throws Exception {
        userService.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v1/auth/register"))
            .willReturn(WireMock.aResponse().withStatus(201)));
        mockMvc.perform(post("/api/v1/auth/register")
                .header("X-User-Id", "9999").header("X-User-Role", "ADMIN")
                .header("X-Internal-Key", "fake").header("Authorization", "Bearer invalid"))
            .andExpect(status().isCreated())
            .andExpect(header().exists("X-Request-Id"));
        userService.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/auth/register"))
            .withoutHeader("X-User-Id").withoutHeader("X-User-Role").withoutHeader("Authorization")
            .withHeader("X-Internal-Key", WireMock.equalTo(INTERNAL_KEY)));
    }

    @Test
    void testOnlyPostAuthEndpointsArePublic() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/other"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void testCorsPreflightWithoutToken() throws Exception {
        mockMvc.perform(options("/api/v1/wallet/balance")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        paymentService.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void testUntrustedCorsOriginRejected() throws Exception {
        mockMvc.perform(options("/api/v1/wallet/balance")
                .header("Origin", "http://untrusted.example")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
    }

    @Test
    void testResponseErrorAndHopByHopHeaders() throws Exception {
        paymentService.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/wallet/balance"))
            .willReturn(WireMock.aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withHeader("Connection", "X-Upstream-Only")
                .withHeader("X-Upstream-Only", "secret")
                .withBody("{\"code\":\"CONFLICT\",\"message\":\"No balance\"}")));
        mockMvc.perform(get("/api/v1/wallet/balance")
                .header("Authorization", "Bearer " + generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600)))
                .header("Connection", "Idempotency-Key")
                .header("Idempotency-Key", "must-not-forward")
                .header("X-Arbitrary", "must-not-forward"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(header().doesNotExist("Connection"))
            .andExpect(header().doesNotExist("X-Upstream-Only"));
        paymentService.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/wallet/balance"))
            .withoutHeader("Idempotency-Key").withoutHeader("X-Arbitrary").withoutHeader("Authorization"));
    }

    @Test
    void testUserRateLimitIsIndependentOfIp() throws Exception {
        String token = generateToken(3001L, "CUSTOMER", Instant.now().plusSeconds(3600));
        paymentService.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/wallet/balance"))
            .willReturn(WireMock.aResponse().withStatus(200)));
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/v1/wallet/balance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/wallet/balance").header("Authorization", "Bearer " + token)
                .with(request -> { request.setRemoteAddr("192.0.2.22"); return request; }))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        mockMvc.perform(get("/api/v1/wallet/balance")
                .header("Authorization", "Bearer " + generateToken(3002L, "CUSTOMER", Instant.now().plusSeconds(3600))))
            .andExpect(status().isOk());
    }

    @Test
    void testErrorContractCopyMatchesSource() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.isDirectory(root.resolve("docs/contracts"))) {
            root = root.getParent();
        }
        for (String file : java.util.List.of("error-format.json", "error-format.md")) {
            String expected = java.nio.file.Files.readString(root.resolve("docs/contracts/http/" + file))
                .replace("\r\n", "\n");
            try (var resource = getClass().getResourceAsStream("/contracts/http/" + file)) {
                org.junit.jupiter.api.Assertions.assertNotNull(resource);
                org.junit.jupiter.api.Assertions.assertEquals(expected,
                    new String(resource.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
    }

    @Test
    void testAllPrefixesRouteToOwningService() throws Exception {
        java.util.Map<String, WireMockServer> routes = java.util.Map.of(
            "/api/v1/auth/profile", userService,
            "/api/v1/users/me", userService,
            "/api/v1/drivers/me", userService,
            "/api/v1/rides", dispatchService,
            "/api/v1/trips/current", dispatchService,
            "/api/v1/quote", pricingService,
            "/api/v1/wallet", paymentService
        );
        for (var entry : routes.entrySet()) {
            entry.getValue().stubFor(WireMock.get(WireMock.urlEqualTo(entry.getKey()))
                .willReturn(WireMock.aResponse().withBody(entry.getKey())));
            mockMvc.perform(get(entry.getKey())
                    .header("Authorization", "Bearer " + generateToken(4001L, "DRIVER", Instant.now().plusSeconds(3600))))
                .andExpect(status().isOk())
                .andExpect(content().string(entry.getKey()));
            entry.getValue().verify(WireMock.getRequestedFor(WireMock.urlEqualTo(entry.getKey())));
        }
    }

    @Test
    void testSlowResponseBodyTimesOut() throws Exception {
        paymentService.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/wallet/balance"))
            .willReturn(WireMock.aResponse().withBody("a".repeat(100))
                .withChunkedDribbleDelay(1, 1000)));
        mockMvc.perform(get("/api/v1/wallet/balance")
                .header("Authorization", "Bearer " + generateToken(1001L, "CUSTOMER", Instant.now().plusSeconds(3600))))
            .andExpect(status().isGatewayTimeout())
            .andExpect(jsonPath("$.code").value("GATEWAY_TIMEOUT"));
    }

    @Test
    void testMalformedIdentityClaimsRejected() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(java.util.Base64.getDecoder().decode(JWT_SECRET));
        for (String token : java.util.List.of(
                Jwts.builder().subject("1001").claim("role", "CUSTOMER").signWith(key).compact(),
                generateToken(1001L, "ADMIN", Instant.now().plusSeconds(3600)),
                generateToken(-1L, "CUSTOMER", Instant.now().plusSeconds(3600)))) {
            mockMvc.perform(get("/api/v1/wallet").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void testHealthProbeIsOnlyPublicInsideContainer() throws Exception {
        mockMvc.perform(get("/api/v1/health")
                .with(request -> { request.setRemoteAddr("192.0.2.50"); return request; }))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/health")
                .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    private String generateToken(long userId, String role, Instant expiration) {
        SecretKey key = Keys.hmacShaKeyFor(java.util.Base64.getDecoder().decode(JWT_SECRET));

        return Jwts.builder()
            .subject(Long.toString(userId))
            .claim("role", role)
            .issuedAt(new Date())
            .expiration(Date.from(expiration))
            .signWith(key)
            .compact();
    }
}
