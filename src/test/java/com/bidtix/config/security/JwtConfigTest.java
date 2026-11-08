package com.bidtix.config.security;

import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real RSA keys generated in the test: signs with the encoder, verifies with the decoder. */
class JwtConfigTest {

    private JwtEncoder encoder;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();

        JwtConfig config = new JwtConfig();
        ReflectionTestUtils.setField(config, "publicKey", (RSAPublicKey) keys.getPublic());
        ReflectionTestUtils.setField(config, "privateKey", (RSAPrivateKey) keys.getPrivate());
        encoder = config.jwtEncoder();
        decoder = config.jwtDecoder();
    }

    @Test
    void accessTokenIssuedByJwtUtil_isAcceptedByTheDecoder() {
        User user = new User("alice@example.com", "hash", "Alice", UserRole.CUSTOMER);
        UserPrincipal principal = UserPrincipal.from(user);

        Jwt jwt = decoder.decode(new JwtUtil(encoder, decoder).generateAccessToken(principal));

        assertThat(jwt.getSubject()).isEqualTo(user.getPublicId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
    }

    @Test
    void validCustomToken_isAccepted() {
        assertThat(decoder.decode(sign(claims -> { }))).isNotNull();
    }

    @Test
    void tokenWithoutAudience_isRejected_notAServerError() {
        // a missing claim must be a clean validation failure (HTTP 401), never an unexpected error (HTTP 500)
        String token = sign(claims -> claims.claims(map -> map.remove("aud")));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void tokenForAnotherAudience_isRejected() {
        String token = sign(claims -> claims.audience(List.of("some-other-service")));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void tokenFromAnotherIssuer_isRejected() {
        String token = sign(claims -> claims.issuer("evil"));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void expiredToken_isRejected() {
        String token = sign(claims -> claims
                .issuedAt(Instant.now().minusSeconds(600))
                .expiresAt(Instant.now().minusSeconds(300)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    /** A valid token, with a chance to break one thing before it is signed. */
    private String sign(Consumer<JwtClaimsSet.Builder> customize) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("bidtix")
                .subject("someone")
                .audience(List.of("bidtix-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        customize.accept(claims);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
