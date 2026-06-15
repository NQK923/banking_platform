package com.ewallet.account.web;

import com.ewallet.account.service.AuthService;
import com.ewallet.account.service.AuthService.AuthResponse;
import com.ewallet.account.service.AuthService.LoginRequest;
import com.ewallet.account.service.AuthService.PasswordResetOtpRequest;
import com.ewallet.account.service.AuthService.RefreshRequest;
import com.ewallet.account.service.AuthService.RegisterRequest;
import com.ewallet.account.service.AuthService.ResetPasswordRequest;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.common.DomainException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    AuthResponse register(@RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    AuthResponse login(@RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    AuthResponse refresh(@RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/pin/verify")
    void verifyPin(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody PinVerifyRequest request) {
        if (user == null) {
            throw new DomainException("FORBIDDEN", "User session is required");
        }
        authService.verifyPin(user.userId(), request.pin());
    }

    @PostMapping("/password/otp")
    void requestPasswordResetOtp(@RequestBody PasswordResetOtpRequest request) {
        authService.requestPasswordResetOtp(request);
    }

    @PostMapping("/password/reset")
    void resetPassword(@RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }

    public record PinVerifyRequest(String pin) {
    }
}
