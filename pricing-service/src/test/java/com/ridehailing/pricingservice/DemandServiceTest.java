package com.ridehailing.pricingservice;

import com.ridehailing.pricingservice.service.DemandService;
import com.ridehailing.pricingservice.service.LocationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DemandServiceTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        .withCommand("redis-server", "--requirepass", "test123");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @TestConfiguration
    static class TestConfig {
        // Mock LocationClient - luôn trả về supply cố định để test dễ dàng
        @Bean
        @Primary
        public LocationClient locationClient() {
            return new LocationClient("http://localhost:9999", 500, "test-key") {
                @Override
                public int getDriverCount(double lat, double lng, double radiusKm) {
                    return 2; // Giả định luôn có 2 tài xế
                }
            };
        }
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DemandService demandService;

    @BeforeEach
    void setup() {
        // Clear Redis
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void testDemandRecordingAndSurgeCalculation() {
        // Ghi 10 yêu cầu quanh một điểm
        double baseLat = 10.762622;
        double baseLng = 106.660172;

        for (int i = 0; i < 10; i++) {
            demandService.recordDemand("trip-" + i, baseLat + i * 0.0001, baseLng + i * 0.0001);
        }

        // Tính surge
        double surge = demandService.calculateSurge(baseLat, baseLng);

        // demand = 10, supply = 2 (mock), ratio = 10/2 = 5
        // surge = 1.0 + 0.5 * (5 - 1) = 1.0 + 2.0 = 3.0
        assertEquals(3.0, surge, 0.01);
    }

    @Test
    void testDemandExpiryAndCleanup() {
        double lat = 10.762622;
        double lng = 106.660172;

        // Ghi demand
        demandService.recordDemand("trip-1", lat, lng);

        // Verify demand được ghi vào Redis
        String cellKey = "price:demand:" + (long) Math.floor(lat / 0.01) + "," +
                         (long) Math.floor(lng / 0.01);
        Long count = redisTemplate.opsForZSet().zCard(cellKey);
        assertEquals(1, count);

        // Check TTL được set
        Long ttl = redisTemplate.getExpire(cellKey);
        assertNotNull(ttl);
        assertTrue(ttl > 0);
    }

    @Test
    void testSurgeCache() {
        double lat = 10.762622;
        double lng = 106.660172;

        // Lần gọi đầu tiên
        double surge1 = demandService.calculateSurge(lat, lng);

        // Lần gọi thứ hai (nên dùng cache)
        double surge2 = demandService.calculateSurge(lat, lng);

        assertEquals(surge1, surge2);
    }

    @Test
    void testSurgeBounds() {
        double lat = 10.762622;
        double lng = 106.660172;

        // Ghi nhiều yêu cầu để tạo surge cao
        for (int i = 0; i < 100; i++) {
            demandService.recordDemand("trip-" + i, lat + i * 0.00001, lng + i * 0.00001);
        }

        double surge = demandService.calculateSurge(lat, lng);

        // Surge phải trong khoảng [1.0, 3.0]
        assertTrue(surge >= 1.0 && surge <= 3.0);
    }
}
