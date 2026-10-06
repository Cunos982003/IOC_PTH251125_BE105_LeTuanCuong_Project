package com.ridehailing.e2e.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ridehailing.e2e.model.*;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;

public class ApiGatewayClient {
    private final String baseUrl;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ApiGatewayClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public RegisterResponse register(RegisterRequest request) throws IOException {
        HttpPost post = new HttpPost(baseUrl + "/api/v1/auth/register");
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new StringEntity(objectMapper.writeValueAsString(request)));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String body = EntityUtils.toString(response.getEntity());
            if (response.getCode() >= 400) {
                throw new IOException("Register failed: " + response.getCode() + " " + body);
            }
            return objectMapper.readValue(body, RegisterResponse.class);
        } catch (ParseException e) {
            throw new IOException("Failed to parse response", e);
        }
    }

    public LoginResponse login(LoginRequest request) throws IOException {
        HttpPost post = new HttpPost(baseUrl + "/api/v1/auth/login");
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new StringEntity(objectMapper.writeValueAsString(request)));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String body = EntityUtils.toString(response.getEntity());
            if (response.getCode() >= 400) {
                throw new IOException("Login failed: " + response.getCode() + " " + body);
            }
            return objectMapper.readValue(body, LoginResponse.class);
        } catch (ParseException e) {
            throw new IOException("Failed to parse response", e);
        }
    }

    public WalletResponse getWallet(String token) throws IOException {
        HttpGet get = new HttpGet(baseUrl + "/api/v1/wallet");
        get.setHeader("Authorization", "Bearer " + token);

        try (CloseableHttpResponse response = httpClient.execute(get)) {
            String body = EntityUtils.toString(response.getEntity());
            if (response.getCode() >= 400) {
                throw new IOException("Get wallet failed: " + response.getCode() + " " + body);
            }
            return objectMapper.readValue(body, WalletResponse.class);
        } catch (ParseException e) {
            throw new IOException("Failed to parse response", e);
        }
    }

    public TripResponse requestTrip(String token, TripRequest request, String idempotencyKey) throws IOException {
        HttpPost post = new HttpPost(baseUrl + "/api/v1/trips");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Authorization", "Bearer " + token);
        post.setHeader("Idempotency-Key", idempotencyKey);
        post.setEntity(new StringEntity(objectMapper.writeValueAsString(request)));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String body = EntityUtils.toString(response.getEntity());
            if (response.getCode() >= 400) {
                ErrorResponse error = objectMapper.readValue(body, ErrorResponse.class);
                throw new IOException("Request trip failed: " + response.getCode() + " " + error.code() + " " + error.message());
            }
            return objectMapper.readValue(body, TripResponse.class);
        } catch (ParseException e) {
            throw new IOException("Failed to parse response", e);
        }
    }

    public HttpResponseInfo requestTripRaw(String token, TripRequest request, String idempotencyKey) throws IOException {
        HttpPost post = new HttpPost(baseUrl + "/api/v1/trips");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Authorization", "Bearer " + token);
        post.setHeader("Idempotency-Key", idempotencyKey);
        post.setEntity(new StringEntity(objectMapper.writeValueAsString(request)));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String body = EntityUtils.toString(response.getEntity());
            return new HttpResponseInfo(response.getCode(), body);
        } catch (ParseException e) {
            throw new IOException("Failed to parse response", e);
        }
    }

    public void cancelTrip(String token, Long tripId) throws IOException {
        HttpPut put = new HttpPut(baseUrl + "/api/v1/trips/" + tripId + "/cancel");
        put.setHeader("Authorization", "Bearer " + token);

        try (CloseableHttpResponse response = httpClient.execute(put)) {
            if (response.getCode() >= 400) {
                String body = EntityUtils.toString(response.getEntity());
                throw new IOException("Cancel trip failed: " + response.getCode() + " " + body);
            }
        } catch (ParseException e) {
            throw new IOException("Failed to parse response", e);
        }
    }

    public void close() throws IOException {
        httpClient.close();
    }

    public record HttpResponseInfo(int status, String body) {}
}
