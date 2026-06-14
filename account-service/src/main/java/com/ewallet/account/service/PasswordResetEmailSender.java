package com.ewallet.account.service;

import java.time.Instant;

public interface PasswordResetEmailSender {
    void sendPasswordResetOtp(String to, String maskedIdentifier, String otp, Instant expiresAt);
}
