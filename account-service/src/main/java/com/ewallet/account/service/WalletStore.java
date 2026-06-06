package com.ewallet.account.service;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.model.LedgerEntryRecord;
import com.ewallet.account.model.OutboxRecord;
import com.ewallet.account.model.UserRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.common.AccountKind;
import com.ewallet.common.AccountStatus;
import com.ewallet.common.DomainException;
import com.ewallet.common.EntryType;
import com.ewallet.common.Money;
import com.ewallet.common.TransactionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WalletStore {
    private static final UUID CASH_CLEARING_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SYSTEM_SUSPENSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final BalanceCache balanceCache;
    private final FaultInjection faultInjection;
    private final String seedAdminPassword;
    private final String seedAdminPin;

    public WalletStore(
        JdbcTemplate jdbc,
        PasswordEncoder passwordEncoder,
        ObjectMapper objectMapper,
        BalanceCache balanceCache,
        FaultInjection faultInjection,
        @Value("${banking.seed-admin-password}") String seedAdminPassword,
        @Value("${banking.seed-admin-pin}") String seedAdminPin
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.balanceCache = balanceCache;
        this.faultInjection = faultInjection;
        this.seedAdminPassword = seedAdminPassword;
        this.seedAdminPin = seedAdminPin;
    }

    @PostConstruct
    void seedAdmin() {
        if (findUserByEmail("admin@local.test").isPresent()) {
            warmBalanceCache();
            return;
        }
        UUID userId = UUID.randomUUID();
        jdbc.update(
            """
                INSERT INTO users (id, email, phone, password_hash, pin_hash, roles, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            userId,
            "admin@local.test",
            null,
            passwordEncoder.encode(seedAdminPassword),
            passwordEncoder.encode(seedAdminPin),
            "ROLE_ADMIN",
            AccountStatus.ACTIVE.name(),
            Timestamp.from(Instant.now())
        );
        warmBalanceCache();
    }

    private void warmBalanceCache() {
        for (Map.Entry<UUID, BigDecimal> entry : balancesSnapshot().entrySet()) {
            balanceCache.put(entry.getKey(), entry.getValue());
        }
    }

    @Transactional
    public synchronized UserRecord createUser(String email, String phone, String password, String pin, String currency) {
        String normalizedEmail = email == null ? null : email.trim().toLowerCase();
        String normalizedPhone = phone == null ? null : phone.trim();
        if (normalizedEmail == null && normalizedPhone == null) {
            throw new DomainException("IDENTIFIER_REQUIRED", "Email or phone is required");
        }
        if (normalizedEmail == null) {
            throw new DomainException("EMAIL_REQUIRED", "Email is required for v1 registration");
        }
        if (findUserByEmail(normalizedEmail).isPresent()) {
            throw new DomainException("EMAIL_EXISTS", "Email already registered");
        }
        if (normalizedPhone != null && findUserByPhone(normalizedPhone).isPresent()) {
            throw new DomainException("PHONE_EXISTS", "Phone already registered");
        }

        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        UserRecord user = new UserRecord(
            userId,
            normalizedEmail,
            normalizedPhone,
            passwordEncoder.encode(password),
            passwordEncoder.encode(pin),
            null,
            Set.of("ROLE_USER"),
            AccountStatus.ACTIVE,
            now
        );
        jdbc.update(
            """
                INSERT INTO users (id, email, phone, password_hash, pin_hash, roles, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            user.id(),
            user.email(),
            user.phone(),
            user.passwordHash(),
            user.pinHash(),
            rolesToString(user.roles()),
            user.status().name(),
            Timestamp.from(user.createdAt())
        );
        createAccount(userId, currency == null ? "VND" : currency);
        audit("USER", userId, "AccountCreated", "USER", userId, Map.of("email", safe(normalizedEmail)), null);
        return user;
    }

    @Transactional
    public synchronized AccountRecord createAccount(UUID userId, String currency) {
        Optional<AccountRecord> existing = optionalUserAccount(userId, currency);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        AccountRecord account = new AccountRecord(
            id, userId, null, currency.toUpperCase(), AccountKind.USER, AccountStatus.ACTIVE, 0, now
        );
        jdbc.update(
            """
                INSERT INTO accounts (id, user_id, code, currency, account_kind, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            account.id(),
            account.userId(),
            account.code(),
            account.currency(),
            account.kind().name(),
            account.status().name(),
            account.version(),
            Timestamp.from(account.createdAt())
        );
        jdbc.update(
            """
                INSERT INTO account_balances (account_id, balance, currency, account_kind, last_event_version, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            account.id(),
            BigDecimal.ZERO,
            account.currency(),
            account.kind().name(),
            account.version(),
            Timestamp.from(now)
        );
        balanceCache.put(account.id(), BigDecimal.ZERO);
        return account;
    }

    public Optional<UserRecord> findUserByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        return queryOne(
            "SELECT * FROM users WHERE lower(email) = ?",
            userMapper(),
            email.trim().toLowerCase()
        );
    }

    public Optional<UserRecord> findUserByPhone(String phone) {
        if (phone == null) {
            return Optional.empty();
        }
        return queryOne("SELECT * FROM users WHERE phone = ?", userMapper(), phone.trim());
    }

    public Optional<UserRecord> findUser(UUID id) {
        return queryOne("SELECT * FROM users WHERE id = ?", userMapper(), id);
    }

    public synchronized void saveUser(UserRecord user) {
        jdbc.update(
            """
                UPDATE users
                SET email = ?, phone = ?, password_hash = ?, pin_hash = ?, refresh_token_hash = ?,
                    roles = ?, status = ?
                WHERE id = ?
                """,
            user.email(),
            user.phone(),
            user.passwordHash(),
            user.pinHash(),
            user.refreshTokenHash(),
            rolesToString(user.roles()),
            user.status().name(),
            user.id()
        );
    }

    public AccountRecord account(UUID id) {
        return queryOne("SELECT * FROM accounts WHERE id = ?", accountMapper(), id)
            .orElseThrow(() -> new DomainException("ACCOUNT_NOT_FOUND", "Account not found"));
    }

    public AccountRecord userAccount(UUID userId, String currency) {
        return optionalUserAccount(userId, currency)
            .orElseThrow(() -> new DomainException("ACCOUNT_NOT_FOUND", "Account not found"));
    }

    public Optional<AccountRecord> optionalUserAccount(UUID userId, String currency) {
        return queryOne(
            "SELECT * FROM accounts WHERE user_id = ? AND currency = ?",
            accountMapper(),
            userId,
            currency.toUpperCase()
        );
    }

    public Optional<AccountRecord> lookupAccount(String email, String phone) {
        Optional<UserRecord> user = email != null ? findUserByEmail(email) : findUserByPhone(phone);
        return user.flatMap(value -> optionalUserAccount(value.id(), "VND"));
    }

    public BigDecimal balance(UUID accountId) {
        BigDecimal value = jdbc.queryForObject(
            "SELECT balance FROM account_balances WHERE account_id = ?",
            BigDecimal.class,
            accountId
        );
        BigDecimal balance = value == null ? BigDecimal.ZERO : value;
        balanceCache.put(accountId, balance);
        return balance;
    }

    @Transactional
    public synchronized void applyBalancedJournal(UUID journalId, Money debit, UUID debitAccountId, Money credit, UUID creditAccountId, String description) {
        debit.assertSameCurrency(credit);
        if (!debit.amount().abs().equals(credit.amount().abs())) {
            throw new DomainException("UNBALANCED_JOURNAL", "Debit and credit amounts must match");
        }
        AccountRecord debitAccount = account(debitAccountId);
        AccountRecord creditAccount = account(creditAccountId);
        if (!debitAccount.currency().equals(debit.currency()) || !creditAccount.currency().equals(credit.currency())) {
            throw new DomainException("CURRENCY_MISMATCH", "Account currency mismatch");
        }

        lockBalances(debitAccountId, creditAccountId);
        appendEntry(journalId, debitAccount, debit.amount().abs().negate(), debit.currency(), EntryType.DEBIT, description);
        appendEntry(journalId, creditAccount, credit.amount().abs(), credit.currency(), EntryType.CREDIT, description);
    }

    @Transactional
    public synchronized void applyBalancedJournalAndAudit(
        UUID journalId,
        Money debit,
        UUID debitAccountId,
        Money credit,
        UUID creditAccountId,
        String description,
        String entityType,
        UUID entityId,
        String eventType,
        String actorType,
        UUID actorId,
        Map<String, String> payload,
        UUID correlationId
    ) {
        applyBalancedJournal(journalId, debit, debitAccountId, credit, creditAccountId, description);
        audit(entityType, entityId, eventType, actorType, actorId, payload, correlationId);
    }

    @Transactional
    public synchronized void applyBalancedJournalSaveTransactionAndAudit(
        UUID journalId,
        Money debit,
        UUID debitAccountId,
        Money credit,
        UUID creditAccountId,
        String description,
        WalletTransaction transaction,
        String entityType,
        UUID entityId,
        String eventType,
        String actorType,
        UUID actorId,
        Map<String, String> payload,
        UUID correlationId
    ) {
        applyBalancedJournal(journalId, debit, debitAccountId, credit, creditAccountId, description);
        saveTransaction(transaction);
        audit(entityType, entityId, eventType, actorType, actorId, payload, correlationId);
    }

    private void lockBalances(UUID first, UUID second) {
        List<UUID> ids = List.of(first, second).stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        for (UUID id : ids) {
            jdbc.queryForObject("SELECT account_id FROM account_balances WHERE account_id = ? FOR UPDATE", UUID.class, id);
        }
    }

    private void appendEntry(UUID journalId, AccountRecord account, BigDecimal amount, String currency, EntryType type, String description) {
        AccountRecord lockedAccount = account(account.id());
        BigDecimal nextBalance = balance(lockedAccount.id()).add(amount);
        if (lockedAccount.kind() == AccountKind.USER && nextBalance.signum() < 0) {
            throw new DomainException("INSUFFICIENT_FUNDS", "Insufficient funds");
        }
        LedgerEntryRecord entry = new LedgerEntryRecord(
            UUID.randomUUID(), journalId, lockedAccount.id(), amount, currency, type, description, Instant.now()
        );
        jdbc.update(
            """
                INSERT INTO ledger_entries (id, journal_id, account_id, amount, currency, entry_type, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            entry.id(),
            entry.journalId(),
            entry.accountId(),
            entry.amount(),
            entry.currency(),
            entry.entryType().name(),
            entry.description(),
            Timestamp.from(entry.createdAt())
        );
        long nextVersion = lockedAccount.version() + 1;
        int versionUpdated = jdbc.update(
            "UPDATE accounts SET version = ? WHERE id = ? AND version = ?",
            nextVersion,
            lockedAccount.id(),
            lockedAccount.version()
        );
        if (versionUpdated != 1) {
            throw new DomainException("CONCURRENT_MODIFICATION", "Account version changed during ledger write");
        }
        jdbc.update(
            """
                UPDATE account_balances
                SET balance = ?, last_event_version = ?, updated_at = ?
                WHERE account_id = ?
                """,
            nextBalance,
            nextVersion,
            Timestamp.from(Instant.now()),
            lockedAccount.id()
        );
        balanceCache.put(lockedAccount.id(), nextBalance);
        appendAccountEvent(lockedAccount.id(), eventName(description, type), entry, nextVersion);
    }

    private void appendAccountEvent(UUID accountId, String eventType, LedgerEntryRecord entry, long version) {
        Map<String, String> payload = Map.of(
            "journalId", entry.journalId().toString(),
            "amount", entry.amount().toPlainString(),
            "currency", entry.currency(),
            "entryType", entry.entryType().name()
        );
        jdbc.update(
            """
                INSERT INTO account_events (id, account_id, event_type, event_data, schema_version, version, correlation_id, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            accountId,
            eventType,
            toJson(payload),
            1,
            version,
            entry.journalId(),
            Timestamp.from(entry.createdAt())
        );
    }

    private String eventName(String description, EntryType type) {
        if (description.contains("deposit")) {
            return type == EntryType.CREDIT ? "MoneyDeposited" : "SystemContraDebited";
        }
        if (description.contains("withdraw")) {
            return type == EntryType.DEBIT ? "MoneyWithdrawn" : "SystemContraCredited";
        }
        if (description.contains("compensation")) {
            return type == EntryType.CREDIT ? "MoneyDebitReversed" : "SystemSuspenseDebited";
        }
        if (description.contains("credit")) {
            return type == EntryType.CREDIT ? "MoneyCredited" : "SystemSuspenseDebited";
        }
        if (description.contains("debit")) {
            return type == EntryType.DEBIT ? "MoneyDebited" : "SystemSuspenseCredited";
        }
        return "LedgerEntryAppended";
    }

    public UUID cashClearingAccountId() {
        return CASH_CLEARING_ID;
    }

    public UUID systemSuspenseAccountId() {
        return SYSTEM_SUSPENSE_ID;
    }

    @Transactional
    public synchronized WalletTransaction saveTransaction(WalletTransaction transaction) {
        int updated = jdbc.update(
            """
                UPDATE transactions
                SET status = ?, debit_applied = ?, updated_at = ?
                WHERE id = ?
                """,
            transaction.status().name(),
            transaction.debitApplied(),
            Timestamp.from(transaction.updatedAt()),
            transaction.id()
        );
        if (updated == 0) {
            jdbc.update(
                """
                    INSERT INTO transactions
                        (id, sender_id, receiver_id, amount, currency, status, idempotency_key, correlation_id, debit_applied, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                transaction.id(),
                transaction.senderId(),
                transaction.receiverId(),
                transaction.amount(),
                transaction.currency(),
                transaction.status().name(),
                transaction.idempotencyKey(),
                transaction.correlationId(),
                transaction.debitApplied(),
                Timestamp.from(transaction.createdAt()),
                Timestamp.from(transaction.updatedAt())
            );
        }
        return transaction;
    }

    @Transactional
    public synchronized WalletTransaction saveTransactionAndAudit(
        WalletTransaction transaction,
        String entityType,
        UUID entityId,
        String eventType,
        String actorType,
        UUID actorId,
        Map<String, String> payload,
        UUID correlationId
    ) {
        WalletTransaction saved = saveTransaction(transaction);
        audit(entityType, entityId, eventType, actorType, actorId, payload, correlationId);
        return saved;
    }

    public Optional<WalletTransaction> findTransaction(UUID id) {
        return queryOne("SELECT * FROM transactions WHERE id = ?", transactionMapper(), id);
    }

    public Optional<WalletTransaction> findTransactionByIdempotency(UUID senderId, String key) {
        return queryOne(
            "SELECT * FROM transactions WHERE sender_id = ? AND idempotency_key = ?",
            transactionMapper(),
            senderId,
            key
        );
    }

    public List<WalletTransaction> transactions() {
        return jdbc.query("SELECT * FROM transactions ORDER BY created_at DESC", transactionMapper());
    }

    public List<WalletTransaction> accountTransactions(UUID accountId) {
        return jdbc.query(
            "SELECT * FROM transactions WHERE sender_id = ? OR receiver_id = ? ORDER BY created_at DESC",
            transactionMapper(),
            accountId,
            accountId
        );
    }

    public List<LedgerEntryRecord> ledger(UUID accountId) {
        return jdbc.query(
            "SELECT * FROM ledger_entries WHERE account_id = ? ORDER BY created_at",
            ledgerMapper(),
            accountId
        );
    }

    public List<AccountRecord> accounts() {
        return jdbc.query("SELECT * FROM accounts ORDER BY created_at", accountMapper());
    }

    @Transactional
    public synchronized AccountRecord suspendAccount(UUID id, UUID actorId) {
        AccountRecord account = account(id).withStatus(AccountStatus.SUSPENDED);
        jdbc.update("UPDATE accounts SET status = ? WHERE id = ?", account.status().name(), id);
        if (account.userId() != null) {
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", AccountStatus.SUSPENDED.name(), account.userId());
        }
        audit("ACCOUNT", id, "AccountSuspended", "ADMIN", actorId, Map.of("status", "SUSPENDED"), null);
        return account;
    }

    @Transactional
    public synchronized void freezeAccountsForReconciliation(Set<UUID> accountIds, UUID correlationId) {
        for (UUID accountId : accountIds) {
            AccountRecord account = account(accountId);
            if (account.kind() != AccountKind.USER || account.status() == AccountStatus.SUSPENDED) {
                continue;
            }
            jdbc.update("UPDATE accounts SET status = ? WHERE id = ?", AccountStatus.SUSPENDED.name(), accountId);
            if (account.userId() != null) {
                jdbc.update("UPDATE users SET status = ? WHERE id = ?", AccountStatus.SUSPENDED.name(), account.userId());
            }
            audit(
                "ACCOUNT",
                accountId,
                "ReconciliationDriftAccountFrozen",
                "SYSTEM",
                null,
                Map.of("status", "SUSPENDED"),
                correlationId
            );
        }
    }

    @Transactional
    public synchronized void persistReconciliationFindings(
        Instant checkedAt,
        int driftCount,
        boolean zeroDrift,
        List<String> findings,
        Map<String, UUID> affectedAccounts
    ) {
        for (String finding : findings) {
            jdbc.update(
                """
                    INSERT INTO reconciliation_findings
                        (id, checked_at, drift_count, zero_drift, account_id, finding, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                UUID.randomUUID(),
                Timestamp.from(checkedAt),
                driftCount,
                zeroDrift,
                affectedAccounts.get(finding),
                finding,
                Timestamp.from(Instant.now())
            );
        }
    }

    @Transactional
    public synchronized AuditLogRecord audit(
        String entityType,
        UUID entityId,
        String eventType,
        String actorType,
        UUID actorId,
        Map<String, String> payload,
        UUID correlationId
    ) {
        AuditLogRecord log = new AuditLogRecord(
            UUID.randomUUID(), entityType, entityId, eventType, actorType, actorId,
            new LinkedHashMap<>(payload), correlationId, Instant.now()
        );
        jdbc.update(
            """
                INSERT INTO audit_logs (id, entity_type, entity_id, event_type, actor_type, actor_id, payload, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
            log.id(),
            log.entityType(),
            log.entityId(),
            log.eventType(),
            log.actorType(),
            log.actorId(),
            toJson(log.payload()),
            log.correlationId(),
            Timestamp.from(log.createdAt())
        );
        faultInjection.maybeFail(FaultInjection.BEFORE_OUTBOX);
        appendOutbox(entityId, eventType, payload, correlationId);
        faultInjection.maybeFail(FaultInjection.AFTER_OUTBOX);
        return log;
    }

    private void appendOutbox(UUID aggregateId, String eventType, Map<String, String> payload, UUID correlationId) {
        UUID eventId = UUID.randomUUID();
        Map<String, String> outboxPayload = new LinkedHashMap<>(payload);
        outboxPayload.put("eventId", eventId.toString());
        jdbc.update(
            """
                INSERT INTO transaction_outbox (id, event_id, aggregate_id, event_type, payload, correlation_id, published, attempts, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, FALSE, 0, ?)
                """,
            UUID.randomUUID(),
            eventId,
            aggregateId,
            eventType,
            toJson(outboxPayload),
            correlationId,
            Timestamp.from(Instant.now())
        );
    }

    public synchronized List<AuditLogRecord> auditLogs() {
        return jdbc.query("SELECT * FROM audit_logs ORDER BY created_at DESC", auditMapper());
    }

    public List<OutboxRecord> unpublishedOutbox(int limit) {
        return jdbc.query(
            """
                SELECT *
                FROM transaction_outbox
                WHERE published = FALSE
                ORDER BY created_at
                LIMIT ?
                """,
            outboxMapper(),
            limit
        );
    }

    public void markOutboxPublished(UUID id) {
        jdbc.update("UPDATE transaction_outbox SET published = TRUE WHERE id = ?", id);
    }

    public void markOutboxAttempt(UUID id) {
        jdbc.update("UPDATE transaction_outbox SET attempts = attempts + 1 WHERE id = ?", id);
    }

    public Map<UUID, BigDecimal> balancesSnapshot() {
        return jdbc.query(
            "SELECT account_id, balance FROM account_balances",
            rs -> {
                Map<UUID, BigDecimal> values = new LinkedHashMap<>();
                while (rs.next()) {
                    values.put(uuid(rs, "account_id"), rs.getBigDecimal("balance"));
                }
                return values;
            }
        );
    }

    public Map<UUID, BigDecimal> cacheBalancesSnapshot() {
        Map<UUID, BigDecimal> projected = balancesSnapshot();
        Map<UUID, BigDecimal> cached = new LinkedHashMap<>();
        for (Map.Entry<UUID, BigDecimal> entry : projected.entrySet()) {
            cached.put(entry.getKey(), balanceCache.get(entry.getKey()).orElse(null));
        }
        return cached;
    }

    public List<LedgerEntryRecord> ledgerEntriesSnapshot() {
        return jdbc.query("SELECT * FROM ledger_entries ORDER BY created_at", ledgerMapper());
    }

    public int reconciliationFindingCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM reconciliation_findings", Integer.class);
        return count == null ? 0 : count;
    }

    @Transactional
    public synchronized int writeAccountSnapshots() {
        int count = 0;
        for (AccountRecord account : accounts()) {
            BigDecimal currentBalance = balance(account.id());
            Map<String, String> state = Map.of(
                "balance", currentBalance.toPlainString(),
                "currency", account.currency(),
                "accountKind", account.kind().name()
            );
            jdbc.update(
                """
                    INSERT INTO account_snapshots (account_id, version, state, created_at)
                    VALUES (?, ?, ?::jsonb, ?)
                    ON CONFLICT (account_id, version) DO UPDATE
                        SET state = EXCLUDED.state, created_at = EXCLUDED.created_at
                    """,
                account.id(),
                account.version(),
                toJson(state),
                Timestamp.from(Instant.now())
            );
            count++;
        }
        return count;
    }

    @Transactional
    public synchronized int rebuildBalancesFromSnapshotsAndEvents() {
        int rebuilt = 0;
        for (AccountRecord account : accounts()) {
            SnapshotState snapshot = latestSnapshot(account.id()).orElse(new SnapshotState(0, BigDecimal.ZERO));
            BigDecimal rebuiltBalance = snapshot.balance();
            long lastVersion = snapshot.version();
            List<AccountEventAmount> events = accountEventAmountsAfter(account.id(), snapshot.version());
            for (AccountEventAmount event : events) {
                rebuiltBalance = rebuiltBalance.add(event.amount());
                lastVersion = event.version();
            }
            jdbc.update(
                """
                    INSERT INTO account_balances (account_id, balance, currency, account_kind, last_event_version, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (account_id) DO UPDATE
                        SET balance = EXCLUDED.balance,
                            currency = EXCLUDED.currency,
                            account_kind = EXCLUDED.account_kind,
                            last_event_version = EXCLUDED.last_event_version,
                            updated_at = EXCLUDED.updated_at
                    """,
                account.id(),
                rebuiltBalance,
                account.currency(),
                account.kind().name(),
                lastVersion,
                Timestamp.from(Instant.now())
            );
            balanceCache.put(account.id(), rebuiltBalance);
            rebuilt++;
        }
        return rebuilt;
    }

    private Optional<SnapshotState> latestSnapshot(UUID accountId) {
        return queryOne(
            """
                SELECT version, state
                FROM account_snapshots
                WHERE account_id = ?
                ORDER BY version DESC
                LIMIT 1
                """,
            (rs, rowNum) -> {
                JsonNode state = readJson(rs.getString("state"));
                return new SnapshotState(rs.getLong("version"), new BigDecimal(state.get("balance").asText()));
            },
            accountId
        );
    }

    private List<AccountEventAmount> accountEventAmountsAfter(UUID accountId, long version) {
        return jdbc.query(
            """
                SELECT version, event_data
                FROM account_events
                WHERE account_id = ? AND version > ?
                ORDER BY version
                """,
            (rs, rowNum) -> {
                JsonNode eventData = readJson(rs.getString("event_data"));
                return new AccountEventAmount(rs.getLong("version"), new BigDecimal(eventData.get("amount").asText()));
            },
            accountId,
            version
        );
    }

    private record SnapshotState(long version, BigDecimal balance) {
    }

    private record AccountEventAmount(long version, BigDecimal amount) {
    }

    private RowMapper<UserRecord> userMapper() {
        return (rs, rowNum) -> new UserRecord(
            uuid(rs, "id"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("password_hash"),
            rs.getString("pin_hash"),
            rs.getString("refresh_token_hash"),
            stringToRoles(rs.getString("roles")),
            AccountStatus.valueOf(rs.getString("status")),
            instant(rs, "created_at")
        );
    }

    private RowMapper<AccountRecord> accountMapper() {
        return (rs, rowNum) -> new AccountRecord(
            uuid(rs, "id"),
            nullableUuid(rs, "user_id"),
            rs.getString("code"),
            rs.getString("currency"),
            AccountKind.valueOf(rs.getString("account_kind")),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            instant(rs, "created_at")
        );
    }

    private RowMapper<LedgerEntryRecord> ledgerMapper() {
        return (rs, rowNum) -> new LedgerEntryRecord(
            uuid(rs, "id"),
            uuid(rs, "journal_id"),
            uuid(rs, "account_id"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            EntryType.valueOf(rs.getString("entry_type")),
            rs.getString("description"),
            instant(rs, "created_at")
        );
    }

    private RowMapper<WalletTransaction> transactionMapper() {
        return (rs, rowNum) -> new WalletTransaction(
            uuid(rs, "id"),
            uuid(rs, "sender_id"),
            uuid(rs, "receiver_id"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            TransactionStatus.valueOf(rs.getString("status")),
            rs.getString("idempotency_key"),
            nullableUuid(rs, "correlation_id"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            rs.getBoolean("debit_applied")
        );
    }

    private RowMapper<AuditLogRecord> auditMapper() {
        return (rs, rowNum) -> new AuditLogRecord(
            uuid(rs, "id"),
            rs.getString("entity_type"),
            uuid(rs, "entity_id"),
            rs.getString("event_type"),
            rs.getString("actor_type"),
            nullableUuid(rs, "actor_id"),
            Map.of("raw", rs.getString("payload")),
            nullableUuid(rs, "correlation_id"),
            instant(rs, "created_at")
        );
    }

    private RowMapper<OutboxRecord> outboxMapper() {
        return (rs, rowNum) -> new OutboxRecord(
            uuid(rs, "id"),
            uuid(rs, "event_id"),
            uuid(rs, "aggregate_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            nullableUuid(rs, "correlation_id"),
            rs.getInt("attempts"),
            instant(rs, "created_at")
        );
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        return values.stream().findFirst();
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private String rolesToString(Set<String> roles) {
        return String.join(",", roles);
    }

    private Set<String> stringToRoles(String roles) {
        return Arrays.stream(roles.split(","))
            .filter(role -> !role.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize JSON payload", ex);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to parse JSON payload", ex);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
