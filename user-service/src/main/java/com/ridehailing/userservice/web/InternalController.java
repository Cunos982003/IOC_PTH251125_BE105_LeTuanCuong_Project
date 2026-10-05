package com.ridehailing.userservice.web;

import com.ridehailing.userservice.common.ErrorResponse;
import com.ridehailing.userservice.model.User;
import com.ridehailing.userservice.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/users")
public class InternalController {

    private final UserRepository userRepository;

    public InternalController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUser(@PathVariable Long id) {
        var userOpt = userRepository.findById(id);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("NOT_FOUND", "User not found"));
        }

        User user = userOpt.get();
        return ResponseEntity.ok(new UserResponse(user.id(), user.role(), user.fullName()));
    }
}