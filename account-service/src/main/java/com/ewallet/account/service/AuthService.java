package com.ewallet.account.service;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.PasswordResetOtpRecord;
import com.ewallet.account.model.UserRecord;
import com.ewallet.account.security.TokenService;
import com.ewallet.common.DomainException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private static final SecureRandom OTP_RANDOM = new SecureRandom();
    private static final int PASSWORD_RESET_MAX_ATTEMPTS = 5;

    private final WalletStore store;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetEmailSender passwordResetEmailSender;
    private final Duration passwordResetOtpTtl;

    public AuthService(
        WalletStore store,
        TokenService tokenService,
        PasswordEncoder passwordEncoder,
        PasswordResetEmailSender passwordResetEmailSender,
        @Value("${banking.password-reset.otp-ttl-minutes:10}") long passwordResetOtpTtlMinutes
    ) {
        this.store = store;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetEmailSender = passwordResetEmailSender;
        this.passwordResetOtpTtl = Duration.ofMinutes(passwordResetOtpTtlMinutes);
    }

    public AuthResponse register(RegisterRequest request) {
        UserRecord user = store.createUser(request.email(), request.phone(), request.password(), request.pin(), request.currency());
        AccountRecord account = store.userAccount(user.id(), request.currency() == null ? "VND" : request.currency());
        return issueTokens(user, account);
    }

    public AuthResponse login(LoginRequest request) {
        UserRecord user = lookupUser(request.identifier());
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new DomainException("AUTH_INVALID", "Invalid credentials");
        }
        AccountRecord account = store.optionalUserAccount(user.id(), "VND").orElse(null);
        return issueTokens(user, account);
    }

    public AuthResponse refresh(RefreshRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()
            || request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new DomainException("AUTH_INVALID", "Invalid refresh token");
        }
        String hash = tokenService.hashRefreshToken(request.refreshToken());
        UUID userId;
        try {
            userId = UUID.fromString(request.userId());
        } catch (IllegalArgumentException ex) {
            throw new DomainException("AUTH_INVALID", "Invalid refresh token");
        }
        UserRecord user = store.findUser(userId).orElseThrow(() -> new DomainException("AUTH_INVALID", "Invalid refresh token"));
        if (!Objects.equals(user.refreshTokenHash(), hash)) {
            throw new DomainException("AUTH_INVALID", "Invalid refresh token");
        }
        AccountRecord account = store.userAccount(user.id(), "VND");
        return issueTokens(user, account);
    }

    @Transactional
    public void requestPasswordResetOtp(PasswordResetOtpRequest request) {
        if (request.identifier() == null || request.identifier().isBlank()) {
            throw new DomainException("AUTH_INVALID", "Invalid credentials");
        }
        String identifier = store.normalizeIdentifier(request.identifier());
        Optional<UserRecord> found = findUser(identifier);
        if (found.isEmpty() || found.get().email() == null || found.get().email().isBlank()) {
            return;
        }

        UserRecord user = found.get();
        String otp = String.format("%06d", OTP_RANDOM.nextInt(1_000_000));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(passwordResetOtpTtl);
        PasswordResetOtpRecord record = new PasswordResetOtpRecord(
            UUID.randomUUID(),
            user.id(),
            identifier,
            passwordEncoder.encode(otp),
            0,
            expiresAt,
            null,
            now
        );
        store.createPasswordResetOtp(record);
        store.audit(
            "USER",
            user.id(),
            "PasswordResetOtpRequested",
            "USER",
            user.id(),
            Map.of("identifier", safeIdentifier(identifier), "email", safeIdentifier(user.email())),
            null
        );
        passwordResetEmailSender.sendPasswordResetOtp(user.email(), safeIdentifier(identifier), otp, expiresAt);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public void resetPassword(ResetPasswordRequest request) {
        UserRecord user = lookupUser(request.identifier());
        PasswordResetOtpRecord otpRecord = store.findLatestPasswordResetOtp(request.identifier())
            .orElseThrow(() -> new DomainException("OTP_REQUIRED", "Request a password reset OTP first"));
        if (!otpRecord.userId().equals(user.id()) || otpRecord.consumedAt() != null) {
            throw new DomainException("OTP_INVALID", "Invalid password reset OTP");
        }
        if (otpRecord.expiresAt().isBefore(Instant.now())) {
            throw new DomainException("OTP_EXPIRED", "Password reset OTP expired");
        }
        if (otpRecord.attempts() >= PASSWORD_RESET_MAX_ATTEMPTS) {
            throw new DomainException("OTP_LOCKED", "Password reset OTP has too many failed attempts");
        }
        if (request.otp() == null || !passwordEncoder.matches(request.otp(), otpRecord.otpHash())) {
            store.updatePasswordResetOtpAttempts(otpRecord.id(), otpRecord.attempts() + 1);
            throw new DomainException("OTP_INVALID", "Invalid password reset OTP");
        }
        if (request.newPassword() == null || request.newPassword().length() < 6) {
            throw new DomainException("INVALID_PASSWORD_FORMAT", "Password must be at least 6 characters");
        }
        Instant now = Instant.now();
        store.consumePasswordResetOtp(otpRecord.id(), now);
        store.saveUser(new UserRecord(
            user.id(), user.email(), user.phone(), passwordEncoder.encode(request.newPassword()), user.pinHash(),
            null, user.roles(), user.status(), user.createdAt(), 0, null
        ));
        store.audit("USER", user.id(), "PasswordReset", "USER", user.id(), Map.of("identifier", safeIdentifier(request.identifier())), null);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public void verifyPin(UUID userId, String pin) {
        UserRecord user = store.findUser(userId)
            .orElseThrow(() -> new DomainException("AUTH_INVALID", "Unknown user"));

        if (user.pinLockedUntil() != null && user.pinLockedUntil().isAfter(Instant.now())) {
            throw new DomainException("PIN_LOCKED", "Transaction PIN is locked due to too many failed attempts. Try again later.");
        }

        if (!passwordEncoder.matches(pin, user.pinHash())) {
            int newAttempts = user.failedPinAttempts() + 1;
            Instant lockUntil = null;
            if (newAttempts >= 5) {
                lockUntil = Instant.now().plus(Duration.ofMinutes(15));
            }
            store.saveUser(new UserRecord(
                user.id(), user.email(), user.phone(), user.passwordHash(), user.pinHash(),
                user.refreshTokenHash(), user.roles(), user.status(), user.createdAt(),
                newAttempts, lockUntil
            ));
            throw new DomainException("PIN_INVALID", "Invalid transaction PIN");
        }

        if (user.failedPinAttempts() > 0) {
            store.saveUser(new UserRecord(
                user.id(), user.email(), user.phone(), user.passwordHash(), user.pinHash(),
                user.refreshTokenHash(), user.roles(), user.status(), user.createdAt(),
                0, null
            ));
        }
    }

    @Transactional(noRollbackFor = DomainException.class)
    public void changePin(UUID userId, String currentPin, String newPin) {
        UserRecord user = store.findUser(userId)
            .orElseThrow(() -> new DomainException("AUTH_INVALID", "Unknown user"));

        if (user.pinLockedUntil() != null && user.pinLockedUntil().isAfter(Instant.now())) {
            throw new DomainException("PIN_LOCKED", "Transaction PIN is locked due to too many failed attempts. Try again later.");
        }

        if (!passwordEncoder.matches(currentPin, user.pinHash())) {
            int newAttempts = user.failedPinAttempts() + 1;
            Instant lockUntil = null;
            if (newAttempts >= 5) {
                lockUntil = Instant.now().plus(Duration.ofMinutes(15));
            }
            store.saveUser(new UserRecord(
                user.id(), user.email(), user.phone(), user.passwordHash(), user.pinHash(),
                user.refreshTokenHash(), user.roles(), user.status(), user.createdAt(),
                newAttempts, lockUntil
            ));
            throw new DomainException("PIN_INVALID", "Invalid current transaction PIN");
        }

        if (newPin == null || !newPin.matches("^\\d{6}$")) {
            throw new DomainException("INVALID_PIN_FORMAT", "New transaction PIN must be exactly 6 digits");
        }

        store.saveUser(new UserRecord(
            user.id(), user.email(), user.phone(), user.passwordHash(), passwordEncoder.encode(newPin),
            user.refreshTokenHash(), user.roles(), user.status(), user.createdAt(),
            0, null
        ));
    }

    private UserRecord lookupUser(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new DomainException("AUTH_INVALID", "Invalid credentials");
        }
        if (identifier.contains("@")) {
            return store.findUserByEmail(identifier)
                .orElseThrow(() -> new DomainException("AUTH_INVALID", "Invalid credentials"));
        }
        return store.findUserByPhone(identifier)
            .orElseThrow(() -> new DomainException("AUTH_INVALID", "Invalid credentials"));
    }

    private Optional<UserRecord> findUser(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        if (identifier.contains("@")) {
            return store.findUserByEmail(identifier);
        }
        return store.findUserByPhone(identifier);
    }

    private String safeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        String value = identifier.trim();
        if (value.contains("@")) {
            int at = value.indexOf("@");
            String local = value.substring(0, at);
            String domain = value.substring(at);
            return (local.length() <= 2 ? "***" : local.substring(0, 2) + "***") + domain;
        }
        return value.length() <= 4 ? "***" : "***" + value.substring(value.length() - 4);
    }

    private AuthResponse issueTokens(UserRecord user, AccountRecord account) {
        String refresh = tokenService.refreshToken();
        store.saveUser(user.withRefreshTokenHash(tokenService.hashRefreshToken(refresh)));
        return new AuthResponse(
            tokenService.accessToken(user.id(), account == null ? null : account.id(), user.roles()),
            refresh,
            user.id().toString(),
            account == null ? null : account.id().toString(),
            user.roles()
        );
    }

    public record RegisterRequest(String email, String phone, String password, String pin, String currency) {
    }

    public record LoginRequest(String identifier, String password) {
    }

    public record RefreshRequest(String userId, String refreshToken) {
    }

    public record PasswordResetOtpRequest(String identifier) {
    }

    public record ResetPasswordRequest(String identifier, String otp, String newPassword) {
    }

    public record AuthResponse(String accessToken, String refreshToken, String userId, String accountId, java.util.Set<String> roles) {
    }
}
