package com.ewallet.account.service;

import com.ewallet.common.DomainException;
import java.time.Instant;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
class SmtpPasswordResetEmailSender implements PasswordResetEmailSender {
    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetEmailSender.class);

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean startTls;
    private final String from;

    SmtpPasswordResetEmailSender(
        @Value("${banking.mail.enabled:false}") boolean enabled,
        @Value("${banking.mail.smtp-host:}") String host,
        @Value("${banking.mail.smtp-port:587}") int port,
        @Value("${banking.mail.smtp-username:}") String username,
        @Value("${banking.mail.smtp-password:}") String password,
        @Value("${banking.mail.smtp-starttls:true}") boolean startTls,
        @Value("${banking.mail.from:no-reply@ewallet.local}") String from
    ) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.startTls = startTls;
        this.from = from;
    }

    @Override
    public void sendPasswordResetOtp(String to, String maskedIdentifier, String otp, Instant expiresAt) {
        if (!enabled) {
            log.info(
                "password-reset-otp to={} maskedIdentifier={} otp={} expiresAt={}",
                to,
                maskedIdentifier,
                otp,
                expiresAt
            );
            return;
        }
        if (host == null || host.isBlank()) {
            throw new DomainException("EMAIL_NOT_CONFIGURED", "Password reset email is not configured");
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        if (username != null && !username.isBlank()) {
            sender.setUsername(username);
        }
        if (password != null && !password.isBlank()) {
            sender.setPassword(password);
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(username != null && !username.isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("E-Wallet password reset OTP");
        message.setText("""
            Your E-Wallet password reset OTP is %s.

            It expires at %s.
            If you did not request this, ignore this email and keep your PIN, password, and OTP private.
            """.formatted(otp, expiresAt));
        sender.send(message);
    }
}
