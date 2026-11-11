package com.bidtix.auth.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        // No @Email here on purpose: a login form should never explain *why* credentials are wrong.
        @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(max = 72) String password) {
}
