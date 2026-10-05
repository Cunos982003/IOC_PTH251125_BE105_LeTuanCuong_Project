package com.ridehailing.apigateway.service;

import com.ridehailing.apigateway.config.GatewayConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class ProxyService {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "trailers", "transfer-encoding", "upgrade"
    );
    private static final Set<String> FORWARDED_HEADERS = Set.of(
        "content-type", "accept", "idempotency-key"
    );

    private final GatewayConfig gatewayConfig;
    private final RestClient client;
    private final RestClient createRideClient;

    public ProxyService(RestClient.Builder builder, GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(gatewayConfig.getProxy().getConnectTimeoutMs()))
            .build();
        client = buildClient(builder, httpClient, gatewayConfig.getProxy().getReadTimeoutMs());
        createRideClient = buildClient(builder, httpClient, gatewayConfig.getProxy().getReadTimeoutCreateRideMs());
    }

    private RestClient buildClient(RestClient.Builder builder, HttpClient httpClient, int readTimeoutMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.clone().requestFactory(factory).build();
    }

    public ResponseEntity<byte[]> proxyRequest(HttpServletRequest request, String targetServiceUrl) throws IOException {
        int maxBody = gatewayConfig.getProxy().getMaxBodySizeBytes();
        if (request.getContentLengthLong() > maxBody) {
            return error(413, "PAYLOAD_TOO_LARGE", "Request body too large");
        }
        byte[] body = request.getInputStream().readNBytes(maxBody + 1);
        if (body.length > maxBody) {
            return error(413, "PAYLOAD_TOO_LARGE", "Request body too large");
        }

        String path = request.getRequestURI();
        String query = request.getQueryString();
        URI target = URI.create(targetServiceUrl + path + (query == null ? "" : "?" + query));
        HttpHeaders headers = new HttpHeaders();
        Set<String> excluded = excludedHeaders(Collections.list(request.getHeaders(HttpHeaders.CONNECTION)));
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (FORWARDED_HEADERS.contains(lower) && !excluded.contains(lower)) {
                headers.addAll(name, Collections.list(request.getHeaders(name)));
            }
        }
        Object userId = request.getAttribute("userId");
        if (userId != null) {
            headers.set("X-User-Id", userId.toString());
            headers.set("X-User-Role", (String) request.getAttribute("userRole"));
        }
        headers.set("X-Internal-Key", gatewayConfig.getInternalKey());
        headers.set("X-Request-Id", (String) request.getAttribute("requestId"));

        RestClient selected = "POST".equals(request.getMethod()) && "/api/v1/rides".equals(path)
            ? createRideClient : client;
        try {
            return selected.method(HttpMethod.valueOf(request.getMethod()))
                .uri(target)
                .headers(h -> h.addAll(headers))
                .body(body)
                .exchange((outgoing, incoming) -> {
                    HttpHeaders responseHeaders = new HttpHeaders();
                    Set<String> responseExcluded = excludedHeaders(incoming.getHeaders().get(HttpHeaders.CONNECTION));
                    incoming.getHeaders().forEach((name, values) -> {
                        if (!responseExcluded.contains(name.toLowerCase(Locale.ROOT))
                                && !"x-internal-key".equalsIgnoreCase(name)
                                && !"x-request-id".equalsIgnoreCase(name)) {
                            responseHeaders.addAll(name, values);
                        }
                    });
                    responseHeaders.set("X-Request-Id", (String) request.getAttribute("requestId"));
                    return ResponseEntity.status(incoming.getStatusCode())
                        .headers(responseHeaders).body(incoming.getBody().readAllBytes());
                });
        } catch (ResourceAccessException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                        || cause instanceof java.util.concurrent.TimeoutException) {
                    return error(504, "GATEWAY_TIMEOUT", "Upstream service timeout");
                }
            }
            return error(502, "BAD_GATEWAY", "Upstream service unavailable");
        }
    }

    private Set<String> excludedHeaders(java.util.List<String> connections) {
        Set<String> excluded = new HashSet<>(HOP_BY_HOP_HEADERS);
        if (connections != null) {
            for (String connection : connections) {
                for (String name : connection.split(",")) {
                    excluded.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return excluded;
    }

    private ResponseEntity<byte[]> error(int status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
