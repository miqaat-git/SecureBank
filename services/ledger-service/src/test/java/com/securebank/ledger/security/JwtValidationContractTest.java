package com.securebank.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Documents the validation contract Ledger Service gets from Spring Security for a Keycloak-style issuer:
 * signature, issuer and expiry are enforced. Uses generated throw-away keys; no network, no real tokens.
 */
class JwtValidationContractTest {

    private static final String ISSUER = "http://localhost:8180/realms/securebank";

    private static RSAKey trusted;
    private static RSAKey attacker;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void setUp() throws Exception {
        trusted = new RSAKeyGenerator(2048).keyID("trusted").generate();
        attacker = new RSAKeyGenerator(2048).keyID("trusted").generate();
        decoder = NimbusJwtDecoder.withPublicKey(trusted.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
    }

    private static String token(RSAKey signingKey, String issuer, Instant expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user-1")
                .issueTime(Date.from(expiry.minusSeconds(600)))
                .expirationTime(Date.from(expiry))
                .claim("realm_access", Map.of("roles", List.of("ledger-writer")))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void acceptsValidTokenAndMapsRoles() throws Exception {
        Jwt jwt = decoder.decode(token(trusted, ISSUER, Instant.now().plusSeconds(300)));
        assertThat(jwt.getIssuer()).hasToString(ISSUER);
        assertThat(jwt.getClaimAsMap("realm_access")).containsKey("roles");
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        String token = token(trusted, "http://localhost:8180/realms/other", Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        String token = token(attacker, ISSUER, Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = token(trusted, ISSUER, Instant.now().minusSeconds(3600));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
