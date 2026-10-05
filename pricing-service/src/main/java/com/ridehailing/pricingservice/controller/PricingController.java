package com.ridehailing.pricingservice.controller;

import com.ridehailing.pricingservice.dto.DemandRequest;
import com.ridehailing.pricingservice.dto.QuoteRequest;
import com.ridehailing.pricingservice.dto.QuoteResponse;
import com.ridehailing.pricingservice.service.DemandService;
import com.ridehailing.pricingservice.service.PricingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PricingController {

    private final PricingService pricingService;
    private final DemandService demandService;

    public PricingController(PricingService pricingService, DemandService demandService) {
        this.pricingService = pricingService;
        this.demandService = demandService;
    }

    @PostMapping("/internal/quote")
    public ResponseEntity<QuoteResponse> internalQuote(@RequestBody QuoteRequest request) {
        QuoteResponse response = pricingService.calculateQuote(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/internal/demand")
    public ResponseEntity<Void> recordDemand(@RequestBody DemandRequest request) {
        demandService.recordDemand(request.tripId(), request.lat(), request.lng());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/quote")
    public ResponseEntity<QuoteResponse> publicQuote(@RequestBody QuoteRequest request,
                                                      @RequestHeader("X-User-Id") String userId) {
        QuoteResponse response = pricingService.calculateQuote(request);
        return ResponseEntity.ok(response);
    }
}
