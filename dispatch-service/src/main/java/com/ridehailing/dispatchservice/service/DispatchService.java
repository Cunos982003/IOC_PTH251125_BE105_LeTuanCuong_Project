package com.ridehailing.dispatchservice.service;

import com.ridehailing.dispatchservice.client.LocationClient;
import com.ridehailing.dispatchservice.client.PaymentClient;
import com.ridehailing.dispatchservice.client.PricingClient;
import com.ridehailing.dispatchservice.client.WsGatewayClient;
import com.ridehailing.dispatchservice.domain.Trip;
import com.ridehailing.dispatchservice.domain.TripEvent;
import com.ridehailing.dispatchservice.domain.TripStatus;
import com.ridehailing.dispatchservice.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class DispatchService {

    private final TripRepository tripRepository;
    private final PricingClient pricingClient;
    private final PaymentClient paymentClient;
    private final LocationClient locationClient;
    private final WsGatewayClient wsGatewayClient;
    private final MatchingService matchingService;

    public DispatchService(TripRepository tripRepository,
                           PricingClient pricingClient,
                           PaymentClient paymentClient,
                           LocationClient locationClient,
                           WsGatewayClient wsGatewayClient,
                           MatchingService matchingService) {
        this.tripRepository = tripRepository;
        this.pricingClient = pricingClient;
        this.paymentClient = paymentClient;
        this.locationClient = locationClient;
        this.wsGatewayClient = wsGatewayClient;
        this.matchingService = matchingService;
    }

    @Transactional
    public Trip createTrip(long customerId, double pickupLat, double pickupLng,
                           double dropoffLat, double dropoffLng, String idempotencyKey) {

        // Check idempotency
        Optional<Trip> existing = tripRepository.findByCustomerAndIdempotencyKey(customerId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Get quote from pricing service
        PricingClient.QuoteResponse quote = pricingClient.getQuote(
            pickupLat, pickupLng, dropoffLat, dropoffLng
        );

        // Check customer balance
        PaymentClient.BalanceResponse balance = paymentClient.getBalance(customerId);
        if (balance.balance() < quote.fare()) {
            throw new InsufficientBalanceException(
                "Insufficient balance: " + balance.balance() + " < " + quote.fare()
            );
        }

        // Create trip
        UUID tripId = UUID.randomUUID();
        Instant now = Instant.now();
        Trip trip = new Trip(
            tripId,
            customerId,
            null,
            TripStatus.CREATED,
            pickupLat,
            pickupLng,
            dropoffLat,
            dropoffLng,
            quote.distanceM(),
            quote.fare(),
            quote.surge(),
            idempotencyKey,
            0,
            now,
            now
        );

        Trip created = tripRepository.create(trip);

        // Publish TripCreated event
        TripEvent.TripCreated event = new TripEvent.TripCreated(
            UUID.randomUUID(),
            tripId,
            customerId,
            pickupLat,
            pickupLng,
            dropoffLat,
            dropoffLng,
            quote.distanceM(),
            quote.fare(),
            now
        );

        // Transition to MATCHING
        Trip matching = tripRepository.transition(created, TripStatus.MATCHING,
            new TripEvent.TripMatching(UUID.randomUUID(), tripId, Instant.now())
        );

        // Notify customer via WebSocket
        try {
            wsGatewayClient.notifyTripCreated(customerId, tripId);
        } catch (Exception e) {
            // Log but don't fail - notification is best-effort
            System.err.println("Failed to notify customer: " + e.getMessage());
        }

        // Matching must not read the trip before its transaction commits.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    matchingService.startMatching(tripId);
                }
            }
        );

        return matching;
    }

    public Optional<Trip> getTrip(UUID tripId, long customerId) {
        Optional<Trip> trip = tripRepository.findById(tripId);

        // Check ownership
        if (trip.isPresent() && trip.get().customerId() != customerId) {
            return Optional.empty();
        }

        return trip;
    }

    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String message) {
            super(message);
        }
    }
}
