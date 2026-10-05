package com.ridehailing.pricingservice;

import com.ridehailing.pricingservice.config.PricingConfig;
import com.ridehailing.pricingservice.dto.QuoteRequest;
import com.ridehailing.pricingservice.dto.QuoteResponse;
import com.ridehailing.pricingservice.service.DemandService;
import com.ridehailing.pricingservice.service.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class PricingServiceTest {

    private PricingService pricingService;
    private PricingConfig config;

    // Stub DemandService - luôn trả surge = 1.0
    static class StubDemandService extends DemandService {
        public StubDemandService() {
            super(null, null, 5);
        }

        @Override
        public double calculateSurge(double lat, double lng) {
            return 1.0;
        }

        @Override
        public void recordDemand(String tripId, double lat, double lng) {
            // No-op for unit test
        }
    }

    @BeforeEach
    void setup() {
        config = new PricingConfig();
        config.setBaseFare(12000);
        config.setPerKm(10000);
        config.setPerMinute(400);
        config.setMinFare(20000);
        config.setDistanceMultiplier(1.3);
        config.setAvgSpeedKmh(25);

        pricingService = new PricingService(config, new StubDemandService());
    }

    @Test
    void testFareIsAtLeastMinFare() {
        // Chuyến đi rất ngắn
        QuoteRequest req = new QuoteRequest(
            new QuoteRequest.Location(10.762622, 106.660172),
            new QuoteRequest.Location(10.762722, 106.660272)
        );

        QuoteResponse response = pricingService.calculateQuote(req);

        assertTrue(response.fare() >= 20000, "Fare must be at least MIN_FARE");
        assertEquals(0, response.fare() % 1000, "Fare must be multiple of 1000");
    }

    @Test
    void testFareIsMultipleOf1000() {
        QuoteRequest req = new QuoteRequest(
            new QuoteRequest.Location(10.762622, 106.660172),
            new QuoteRequest.Location(10.772622, 106.670172)
        );

        QuoteResponse response = pricingService.calculateQuote(req);

        assertEquals(0, response.fare() % 1000, "Fare must be rounded up to multiple of 1000");
    }

    @ParameterizedTest
    @CsvSource({
        "10.762622, 106.660172, 10.772622, 106.670172",
        "21.028511, 105.804817, 21.038511, 105.814817",
        "16.047079, 108.206230, 16.057079, 108.216230"
    })
    void testSameInputProducesSameOutput(double lat1, double lng1, double lat2, double lng2) {
        QuoteRequest req = new QuoteRequest(
            new QuoteRequest.Location(lat1, lng1),
            new QuoteRequest.Location(lat2, lng2)
        );

        QuoteResponse first = pricingService.calculateQuote(req);
        QuoteResponse second = pricingService.calculateQuote(req);

        assertEquals(first.distanceM(), second.distanceM());
        assertEquals(first.durationS(), second.durationS());
        assertEquals(first.fare(), second.fare());
    }

    @Test
    void testSurgeWithinBounds() {
        QuoteRequest req = new QuoteRequest(
            new QuoteRequest.Location(10.762622, 106.660172),
            new QuoteRequest.Location(10.772622, 106.670172)
        );

        QuoteResponse response = pricingService.calculateQuote(req);

        assertTrue(response.surge() >= 1.0, "Surge must be at least 1.0");
        assertTrue(response.surge() <= 3.0, "Surge must be at most 3.0");
    }

    @Test
    void testFareIsLongNotDouble() {
        QuoteRequest req = new QuoteRequest(
            new QuoteRequest.Location(10.762622, 106.660172),
            new QuoteRequest.Location(10.782622, 106.680172)
        );

        QuoteResponse response = pricingService.calculateQuote(req);

        // Verify fare is represented as long (integer cents/dong)
        assertTrue(response.fare() > 0);
        assertEquals(response.fare(), (long) response.fare());
    }
}
