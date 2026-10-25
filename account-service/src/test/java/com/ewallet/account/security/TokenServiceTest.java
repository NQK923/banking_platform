package com.ewallet.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ewallet.common.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class TokenServiceTest {
    private static final String SECRET = "test-secret-test-secret-test-secret";
    private final ObjectMapper mapper = new ObjectMapper();
    private final TokenService tokens = new TokenService(mapper, SECRET);

    @Test
    void issuesStandardHs256JwtWithFifteenMinuteExpiry() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        long before = Instant.now().getEpochSecond();
        String token = tokens.accessToken(userId, accountId, Set.of("ROLE_USER"));
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        var header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").asText()).isEqualTo("HS256");
        assertThat(header.get("typ").asText()).isEqualTo("JWT");
        var payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("exp").asLong()).isBetween(before + 900, Instant.now().getEpochSecond() + 900);
        assertThat(parts[2]).isEqualTo(signature(parts[0] + "." + parts[1]));
        assertThat(tokens.parseAccessToken(token)).isEqualTo(new AuthenticatedUser(userId, accountId, Set.of("ROLE_USER")));
    }

    @Test
    void rejectsLegacyTwoPartTokensAndTamperedPayload() {
        String token = tokens.accessToken(UUID.randomUUID(), null, Set.of("ROLE_ADMIN"));
        String[] parts = token.split("\\.");
        assertInvalid(parts[1] + "." + parts[2]);
        assertInvalid(parts[0] + "." + parts[1] + ".");
        assertInvalid(parts[0] + "." + encode("{\"roles\":\"ROLE_ADMIN\"}") + "." + parts[2]);
        assertInvalid(token + ".extra");
    }

    @Test
    void rejectsUnsupportedAlgorithmsEvenWithValidHmac() throws Exception {
        for (String algorithm : new String[] {"none", "HS512", "RS256"}) {
            assertInvalid(signed(Map.of("alg", algorithm, "typ", "JWT"), claims(Instant.now().getEpochSecond() + 900)));
        }
        assertInvalid(signed(Map.of("alg", "HS256", "typ", "JWT", "crit", new String[] {"unknown"}),
            claims(Instant.now().getEpochSecond() + 900)));
    }

    @Test
    void rejectsExpiredAndBoundaryExpiry() throws Exception {
        for (long expiry : new long[] {0, Instant.now().getEpochSecond()}) {
            String token = signed(Map.of("alg", "HS256", "typ", "JWT"), claims(expiry));
            assertThatThrownBy(() -> tokens.parseAccessToken(token)).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("AUTH_EXPIRED"));
        }
    }

    @Test
    void rejectsWrongSecretAndShortSigningKeys() {
        String token = tokens.accessToken(UUID.randomUUID(), null, Set.of("ROLE_USER"));
        assertThatThrownBy(() -> new TokenService(mapper, SECRET + "different").parseAccessToken(token))
            .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new TokenService(mapper, "short"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, Object> claims(long expiry) {
        return Map.of("sub", UUID.randomUUID().toString(), "typ", "access", "roles", "ROLE_USER", "exp", expiry);
    }

    private String signed(Map<String, Object> header, Map<String, Object> payload) throws Exception {
        String input = encode(mapper.writeValueAsString(header)) + "." + encode(mapper.writeValueAsString(payload));
        return input + "." + signature(input);
    }

    private String signature(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> tokens.parseAccessToken(token)).isInstanceOfSatisfying(DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("AUTH_INVALID"));
    }
}
