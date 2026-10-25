package com.ewallet.account.security;

import com.ewallet.common.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public TokenService(ObjectMapper objectMapper, @Value("${banking.jwt-secret}") String secret) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("JWT HS256 signing secret must contain at least 32 bytes");
        }
    }

    public String accessToken(UUID userId, UUID accountId, Set<String> roles) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("typ", "access");
        payload.put("sub", userId.toString());
        payload.put("accountId", accountId == null ? "" : accountId.toString());
        payload.put("roles", String.join(",", roles));
        payload.put("exp", Instant.now().plusSeconds(900).getEpochSecond());
        return sign(payload);
    }

    public String refreshToken() {
        return UUID.randomUUID() + "." + UUID.randomUUID();
    }

    public String hashRefreshToken(String refreshToken) {
        return sha256(refreshToken);
    }

    public AuthenticatedUser parseAccessToken(String token) {
        try {
            Map<String, String> payload = parse(token);
            if (!"access".equals(payload.get("typ"))) {
                throw new DomainException("AUTH_INVALID", "Invalid token type");
            }
            long exp = Long.parseLong(payload.get("exp"));
            if (Instant.now().getEpochSecond() >= exp) {
                throw new DomainException("AUTH_EXPIRED", "Access token expired");
            }
            String accountValue = payload.get("accountId");
            UUID accountId = accountValue == null || accountValue.isBlank() ? null : UUID.fromString(accountValue);
            Set<String> roles = Set.of(payload.getOrDefault("roles", "").split(","));
            return new AuthenticatedUser(UUID.fromString(payload.get("sub")), accountId, roles);
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException("AUTH_INVALID", "Invalid bearer token");
        }
    }

    private String sign(Map<String, Object> payload) {
        try {
            String header = base64Url(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String body = base64Url(objectMapper.writeValueAsBytes(payload));
            String signingInput = header + "." + body;
            String signature = base64Url(hmac(signingInput));
            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create token", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parse(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                throw new DomainException("AUTH_INVALID", "Invalid bearer token");
            }
            Map<String, Object> header = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ")) || header.containsKey("crit")) {
                throw new DomainException("AUTH_INVALID", "Invalid bearer token");
            }
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) {
                throw new DomainException("AUTH_INVALID", "Invalid bearer token");
            }
            Map<String, Object> raw = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            Map<String, String> payload = new LinkedHashMap<>();
            raw.forEach((key, value) -> payload.put(key, String.valueOf(value)));
            return payload;
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException("AUTH_INVALID", "Invalid bearer token");
        }
    }

    private byte[] hmac(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            return base64Url(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash refresh token", ex);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
