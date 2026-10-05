package com.ridehailing.paymentservice.controller;

import com.ridehailing.paymentservice.dto.ErrorResponse;
import com.ridehailing.paymentservice.dto.WalletResponse;
import com.ridehailing.paymentservice.service.WalletService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/wallets")
public class InternalWalletController {

    private final WalletService walletService;

    public InternalWalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/{userId}/balance")
    public ResponseEntity<?> getBalance(@PathVariable Long userId) {
        try {
            long balance = walletService.getBalance(userId);
            return ResponseEntity.ok(new WalletResponse(userId, balance));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("WALLET_NOT_FOUND", e.getMessage()));
        }
    }
}
