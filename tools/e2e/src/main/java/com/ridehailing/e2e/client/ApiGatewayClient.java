package com.ridehailing.e2e.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.e2e.model.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public class ApiGatewayClient implements AutoCloseable {
    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiGatewayClient(String baseUrl) { this.baseUrl = baseUrl.replaceAll("/$", ""); }

    private HttpResponseInfo call(String method, String path, String token, Object body) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1" + path))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResponseInfo(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }

    public <T> T decode(HttpResponseInfo response, Class<T> type) throws IOException {
        if (response.status() < 200 || response.status() >= 300)
            throw new HttpFailure(response.status(), response.body());
        return mapper.readValue(response.body(), type);
    }

    public RegisterResponse register(RegisterRequest request) throws IOException {
        return decode(call("POST", "/auth/register", null, request), RegisterResponse.class);
    }
    public LoginResponse login(LoginRequest request) throws IOException {
        return decode(call("POST", "/auth/login", null, request), LoginResponse.class);
    }
    public String registerAccount(String role) throws IOException {
        String token = register(new RegisterRequest("e2e-" + UUID.randomUUID() + "@test.com",
            "password123", "E2E " + role, "+849" + String.format("%08d", new Random().nextInt(100_000_000)), role)).accessToken();
        if (token == null || token.isBlank()) throw new IOException("Registration omitted accessToken");
        return token;
    }
    // Decode only the issued payload to identify test accounts; never modify or re-sign a token.
    public long userId(String token) throws IOException {
        try {
            return Long.parseLong(mapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1])).path("sub").asText());
        } catch (RuntimeException e) { throw new IOException("Invalid JWT subject", e); }
    }
    public WalletResponse getWallet(String token) throws IOException {
        return decode(call("GET", "/wallet", token, null), WalletResponse.class);
    }
    public WalletResponse awaitBalance(String token, long expected, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        Long actual = null;
        do {
            try {
                WalletResponse wallet = getWallet(token);
                actual = wallet.balance();
                if (actual != null && actual == expected) return wallet;
            } catch (HttpFailure e) {
                if (e.status != 404 || !"WALLET_NOT_FOUND".equals(errorCode(e.body))) throw e;
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new IOException("Wallet balance timeout: expected " + expected + ", got " + actual);
    }
    public String errorCode(String body) throws IOException { return mapper.readTree(body).path("code").asText(); }
    public TripResponse requestTrip(String token, TripRequest request, String key) throws IOException {
        return decode(requestTripRaw(token, request, key), TripResponse.class);
    }
    public HttpResponseInfo requestTripRaw(String token, TripRequest request, String key) throws IOException {
        return call("POST", "/rides", token, new TripRequest(request.pickupLat(), request.pickupLng(),
            request.dropoffLat(), request.dropoffLng(), key));
    }
    public TripResponse getTrip(String token, UUID id) throws IOException {
        return decode(call("GET", "/rides/" + id, token, null), TripResponse.class);
    }
    public TripResponse awaitStatus(String token, UUID id, String status, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        String actual;
        do {
            TripResponse trip = getTrip(token, id);
            actual = trip.status();
            if (status.equals(actual)) return trip;
            if (Set.of("COMPLETED", "CANCELLED", "NO_DRIVER_FOUND").contains(actual))
                throw new IOException("Expected " + status + " but trip became " + actual);
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new IOException("Trip status timeout: expected " + status + ", got " + actual);
    }
    public void action(String token, UUID id, String action) throws IOException {
        requireSuccess(call("POST", "/trips/" + id + "/" + action, token, null));
    }
    public void cancelTrip(String token, UUID id) throws IOException {
        requireSuccess(call("POST", "/rides/" + id + "/cancel", token, null));
    }
    private void requireSuccess(HttpResponseInfo response) throws IOException {
        if (response.status() < 200 || response.status() >= 300) throw new HttpFailure(response.status(), response.body());
    }
    public void close() { client.close(); }
    public record HttpResponseInfo(int status, String body) {}
    public static class HttpFailure extends IOException {
        public final int status;
        public final String body;
        public HttpFailure(int status, String body) { super("HTTP " + status + " " + body); this.status = status; this.body = body; }
    }
}
