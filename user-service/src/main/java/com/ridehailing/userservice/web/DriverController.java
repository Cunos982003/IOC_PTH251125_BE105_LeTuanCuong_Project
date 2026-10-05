package com.ridehailing.userservice.web;

import com.ridehailing.userservice.common.ErrorResponse;
import com.ridehailing.userservice.model.Driver;
import com.ridehailing.userservice.model.Vehicle;
import com.ridehailing.userservice.repository.DriverRepository;
import com.ridehailing.userservice.repository.VehicleRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drivers")
public class DriverController {

    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;

    public DriverController(DriverRepository driverRepository, VehicleRepository vehicleRepository) {
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
    }

    @PutMapping("/me/status")
    public ResponseEntity<?> updateStatus(@Valid @RequestBody DriverStatusRequest request,
                                           jakarta.servlet.http.HttpServletRequest httpRequest) {
        String userIdHeader = (String) httpRequest.getAttribute("X-User-Id");
        String userRoleHeader = (String) httpRequest.getAttribute("X-User-Role");

        if (userIdHeader == null || userRoleHeader == null) {
            return ResponseEntity.status(401)
                    .body(new ErrorResponse("UNAUTHORIZED", "Missing user identity"));
        }

        if (!"DRIVER".equals(userRoleHeader)) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse("FORBIDDEN", "Only drivers can update status"));
        }

        Long userId = Long.parseLong(userIdHeader);
        var driverOpt = driverRepository.findByUserId(userId);

        if (driverOpt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("NOT_FOUND", "Driver not found"));
        }

        int updated = driverRepository.updateStatus(userId, request.status());
        if (updated == 0) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("NOT_FOUND", "Driver not found"));
        }

        return ResponseEntity.ok().build();
    }

    @PutMapping("/me/vehicle")
    public ResponseEntity<?> updateVehicle(@Valid @RequestBody VehicleRequest request,
                                            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String userIdHeader = (String) httpRequest.getAttribute("X-User-Id");
        String userRoleHeader = (String) httpRequest.getAttribute("X-User-Role");

        if (userIdHeader == null || userRoleHeader == null) {
            return ResponseEntity.status(401)
                    .body(new ErrorResponse("UNAUTHORIZED", "Missing user identity"));
        }

        if (!"DRIVER".equals(userRoleHeader)) {
            return ResponseEntity.status(403)
                    .body(new ErrorResponse("FORBIDDEN", "Only drivers can update vehicle"));
        }

        Long userId = Long.parseLong(userIdHeader);
        var driverOpt = driverRepository.findByUserId(userId);

        if (driverOpt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("NOT_FOUND", "Driver not found"));
        }

        var vehicleOpt = vehicleRepository.findByDriverId(userId);

        if (vehicleOpt.isPresent()) {
            Vehicle existing = vehicleOpt.get();
            if (!existing.plate().equals(request.plate())) {
                // Race condition fix: try update, catch unique constraint violation
                try {
                    int updated = vehicleRepository.update(userId, request.plate(), request.type(), request.model());
                    if (updated == 0) {
                        return ResponseEntity.status(404)
                                .body(new ErrorResponse("NOT_FOUND", "Vehicle not found"));
                    }
                } catch (DataIntegrityViolationException e) {
                    return ResponseEntity.status(409)
                            .body(new ErrorResponse("CONFLICT", "Plate already exists"));
                }
            } else {
                vehicleRepository.update(userId, request.plate(), request.type(), request.model());
            }
        } else {
            // Race condition fix: try insert, catch unique constraint violation
            try {
                vehicleRepository.insert(userId, request.plate(), request.type(), request.model());
            } catch (DataIntegrityViolationException e) {
                return ResponseEntity.status(409)
                        .body(new ErrorResponse("CONFLICT", "Plate already exists"));
            }
        }

        return ResponseEntity.ok().build();
    }
}