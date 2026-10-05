package com.ridehailing.userservice.web;

import com.ridehailing.userservice.common.ErrorResponse;
import com.ridehailing.userservice.model.User;
import com.ridehailing.userservice.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(jakarta.servlet.http.HttpServletRequest request) {
        String userIdHeader = (String) request.getAttribute("X-User-Id");
        String userRoleHeader = (String) request.getAttribute("X-User-Role");

        if (userIdHeader == null || userRoleHeader == null) {
            return ResponseEntity.status(401)
                    .body(new ErrorResponse("UNAUTHORIZED", "Missing user identity"));
        }

        Long userId = Long.parseLong(userIdHeader);
        var userOpt = userRepository.findById(userId);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("NOT_FOUND", "User not found"));
        }

        User user = userOpt.get();
        return ResponseEntity.ok(new UserResponse(user.id(), user.role(), user.fullName()));
    }
}