package com.ridehailing.userservice.web;

import com.ridehailing.userservice.common.ErrorResponse;
import com.ridehailing.userservice.event.UserRegisteredEvent;
import com.ridehailing.userservice.model.User;
import com.ridehailing.userservice.repository.UserRepository;
import com.ridehailing.userservice.repository.DriverRepository;
import com.ridehailing.userservice.repository.OutboxRepository;
import com.ridehailing.userservice.security.JwtSigner;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final DriverRepository driverRepository;
    private final OutboxRepository outboxRepository;
    private final JwtSigner jwtSigner;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository,
                          DriverRepository driverRepository,
                          OutboxRepository outboxRepository,
                          JwtSigner jwtSigner,
                          BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.driverRepository = driverRepository;
        this.outboxRepository = outboxRepository;
        this.jwtSigner = jwtSigner;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        // Validate role
        if (!"CUSTOMER".equals(request.role()) && !"DRIVER".equals(request.role())) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("VALIDATION_ERROR", "Role must be CUSTOMER or DRIVER"));
        }

        // Check if email exists
        if (userRepository.findByEmail(request.email()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("CONFLICT", "Email already exists"));
        }

        // Hash password
        String passwordHash = passwordEncoder.encode(request.password());

        // Create user
        User user = new User(null, request.email(), passwordHash, request.role(), request.fullName(), null, null);
        Long userId = userRepository.insert(user, passwordHash);

        // If driver, create driver record
        if ("DRIVER".equals(request.role())) {
            driverRepository.insert(userId);
        }

        // Write outbox event
        UserRegisteredEvent event = new UserRegisteredEvent(
                UUID.randomUUID(),
                userId,
                request.role(),
                request.fullName(),
                Instant.now()
        );
        outboxRepository.insert("users.registered", event);

        // Generate token
        String token = jwtSigner.sign(userId, request.role());
        return ResponseEntity.ok(new AuthResponse(token));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        var userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty() || !passwordEncoder.matches(request.password(), userOpt.get().passwordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("UNAUTHORIZED", "Invalid credentials"));
        }

        User user = userOpt.get();
        String token = jwtSigner.sign(user.id(), user.role());
        return ResponseEntity.ok(new AuthResponse(token));
    }
}