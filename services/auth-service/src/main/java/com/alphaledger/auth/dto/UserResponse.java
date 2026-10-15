package com.alphaledger.auth.dto;

import java.time.Instant;

import com.alphaledger.auth.domain.Role;
import com.alphaledger.auth.domain.UserAccount;
import com.alphaledger.auth.domain.UserStatus;

public record UserResponse(Long id, String email, String fullName, Role role, UserStatus status, Instant createdAt) {

    public static UserResponse from(UserAccount user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getStatus(), user.getCreatedAt());
    }
}
