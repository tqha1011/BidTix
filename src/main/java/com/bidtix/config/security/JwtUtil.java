package com.bidtix.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JwtUtil {

    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(5);

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;

    public String generateAccessToken(UserPrincipal userPrincipal) {
        Instant now = Instant.now();

        // set payload claims
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userPrincipal.getPublicId().toString())
                .issuer("bidtix")
                .audience(List.of("bidtix-api"))
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TOKEN_TTL))
                .claim("roles", List.of(userPrincipal.getRole().name()))
                .build();

        // set headers
        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    public String extractSubject(String token) {
        return jwtDecoder.decode(token).getSubject();
    }
}
