package com.bidtix.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtEncoder jwtEncoder;

    public String generateToken(UserPrincipal userPrincipal) {
        Instant now = Instant.now();

        // set payload claims
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userPrincipal.getPublicId().toString())
                .issuer("bidtix")
                .audience(List.of("bidtix-api"))
                .issuedAt(now)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES)) // Token valid for 5 minutes
                .claim("roles", List.of(userPrincipal.getRole().name()))
                .build();

        // set headers
        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }
}
