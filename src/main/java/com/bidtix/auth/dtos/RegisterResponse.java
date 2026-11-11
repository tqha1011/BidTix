package com.bidtix.auth.dtos;

import com.bidtix.user.domain.User;

import java.util.UUID;

public record RegisterResponse(UUID id, String email, String fullName, String role) {

    public static RegisterResponse from(User user) {
        return new RegisterResponse(user.getPublicId(), user.getEmail(), user.getFullName(), user.getRole().name());
    }
}
