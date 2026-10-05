package com.ridehailing.paymentservice.controller;

import com.ridehailing.paymentservice.dto.ErrorResponse;
import com.ridehailing.paymentservice.dto.TopupRequest;
import com.ridehailing.paymentservice.dto.WalletResponse;
import com.ridehailing.paymentservice.service.WalletService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    public ResponseEntity<?> getWallet(@RequestHeader("X-User-Id") Long userId) {
        try {
            long balance = walletService.getBalance(userId);
            return ResponseEntity.ok(new WalletResponse(userId, balance));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("WALLET_NOT_FOUND", e.getMessage()));
        }
    }

    @PostMapping("/topup")
    public ResponseEntity<?> topup(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody TopupRequest request) {
        try {
            walletService.topup(userId, request.amount());
            long balance = walletService.getBalance(userId);
            return ResponseEntity.ok(new WalletResponse(userId, balance));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("INVALID_REQUEST", e.getMessage()));
        }
    }
}
