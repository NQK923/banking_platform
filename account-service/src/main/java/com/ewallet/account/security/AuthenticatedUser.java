package com.ewallet.account.security;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID accountId, Set<String> roles) {
}
