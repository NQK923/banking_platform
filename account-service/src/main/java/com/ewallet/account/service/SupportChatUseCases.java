package com.ewallet.account.service;

import com.ewallet.account.model.SupportCaseHandoff;
import com.ewallet.account.model.SupportChatMessage;
import com.ewallet.account.model.SupportChatSession;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.common.DomainException;
import com.ewallet.common.TransactionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SupportChatUseCases {
    private static final Pattern SECRET_PATTERN = Pattern.compile(
        "(?i)\\b(pin|password|otp|access token|refresh token|token)\\b\\s*[:=]?\\s*[^\\s,.;]+"
    );

    private final WalletStore store;
    private final MeterRegistry meterRegistry;

    public SupportChatUseCases(WalletStore store, MeterRegistry meterRegistry) {
        this.store = store;
        this.meterRegistry = meterRegistry;
    }

    public CreateSessionResponse createSession(AuthenticatedUser user, CreateSessionRequest request) {
        AuthenticatedUser actor = requireUser(user);
        UUID transactionId = request.context() == null ? null : request.context().transactionId();
        WalletTransaction tx = transactionId == null ? null : accessibleTransactionOrNull(transactionId, actor);
        boolean lookupMissing = transactionId != null && tx == null;
        String initialMessage = sanitize(request.initialMessage());
        String topic = classify(initialMessage, tx);
        Instant now = Instant.now();
        SupportChatSession session = store.createSupportSession(new SupportChatSession(
            UUID.randomUUID(),
            actor.userId(),
            "OPEN",
            topic,
            tx == null ? null : transactionId,
            now,
            now
        ));
        SupportChatMessage userMessage = store.saveSupportMessage(
            new SupportChatMessage(UUID.randomUUID(), session.id(), "USER", initialMessage, Map.of(), now),
            "USER",
            actor.userId()
        );
        AssistantAnswer answer = answer(initialMessage, tx, topic, lookupMissing);
        validateAnswer(answer, tx);
        SupportChatMessage aiMessage = store.saveSupportMessage(
            new SupportChatMessage(UUID.randomUUID(), session.id(), "AI", answer.text(), answer.metadata(), Instant.now()),
            "SYSTEM",
            null
        );
        recordToolCall(session.id(), userMessage.id(), tx, transactionId);
        increment("support_chat_sessions_total");
        increment("support_chat_messages_total", 2);
        increment("support_chat_ai_responses_total");
        return new CreateSessionResponse(
            session.id(),
            session.status(),
            aiMessage.message(),
            answer.suggestedActions(),
            session.id()
        );
    }

    public MessageResponse sendMessage(AuthenticatedUser user, UUID sessionId, SendMessageRequest request) {
        AuthenticatedUser actor = requireUser(user);
        SupportChatSession session = ownedSession(sessionId, actor.userId());
        UUID transactionId = request.context() == null || request.context().transactionId() == null
            ? session.relatedTransactionId()
            : request.context().transactionId();
        WalletTransaction tx = transactionId == null ? null : accessibleTransactionOrNull(transactionId, actor);
        boolean lookupMissing = transactionId != null && tx == null;
        String message = sanitize(request.message());
        String topic = classify(message, tx);
        SupportChatSession updatedSession = store.updateSupportSession(new SupportChatSession(
            session.id(),
            session.userId(),
            session.status(),
            topic,
            tx == null ? null : transactionId,
            session.createdAt(),
            Instant.now()
        ));
        SupportChatMessage userMessage = store.saveSupportMessage(
            new SupportChatMessage(UUID.randomUUID(), updatedSession.id(), "USER", message, Map.of(), Instant.now()),
            "USER",
            actor.userId()
        );
        AssistantAnswer answer = answer(message, tx, topic, lookupMissing);
        validateAnswer(answer, tx);
        SupportChatMessage aiMessage = store.saveSupportMessage(
            new SupportChatMessage(UUID.randomUUID(), updatedSession.id(), "AI", answer.text(), answer.metadata(), Instant.now()),
            "SYSTEM",
            null
        );
        recordToolCall(updatedSession.id(), userMessage.id(), tx, transactionId);
        if (answer.requiresHandoff()) {
            store.audit(
                "SUPPORT_CHAT_SESSION",
                updatedSession.id(),
                "SupportChatSafetyEscalated",
                "SYSTEM",
                null,
                Map.of("topic", topic),
                updatedSession.id()
            );
            increment("support_chat_safety_blocks_total");
        }
        increment("support_chat_messages_total", 2);
        increment("support_chat_ai_responses_total");
        return new MessageResponse(
            aiMessage.id(),
            aiMessage.message(),
            citations(tx),
            answer.suggestedActions(),
            updatedSession.id()
        );
    }

    public SessionDetail getSession(AuthenticatedUser user, UUID sessionId) {
        AuthenticatedUser actor = requireUser(user);
        SupportChatSession session = ownedSession(sessionId, actor.userId());
        return toSessionDetail(session);
    }

    public PageResponse<SessionSummary> listSessions(AuthenticatedUser user, int page, int size) {
        AuthenticatedUser actor = requireUser(user);
        List<SessionSummary> summaries = store.supportSessionsForUser(actor.userId()).stream()
            .map(session -> new SessionSummary(
                session.id(),
                session.status(),
                session.topic(),
                session.relatedTransactionId(),
                session.updatedAt()
            ))
            .toList();
        return page(summaries, page, size);
    }

    public HandoffResponse requestHandoff(AuthenticatedUser user, UUID sessionId, HandoffRequest request) {
        AuthenticatedUser actor = requireUser(user);
        SupportChatSession session = ownedSession(sessionId, actor.userId());
        SupportCaseHandoff existing = store.findSupportCaseBySession(sessionId).orElse(null);
        if (existing != null && !"CLOSED".equals(existing.status())) {
            return new HandoffResponse(existing.id(), session.id(), existing.status(), "Your case is already with support.");
        }
        String summary = summarize(session, store.supportMessages(session.id()), session.relatedTransactionId());
        SupportCaseHandoff handoff = store.createSupportCase(
            new SupportCaseHandoff(
                UUID.randomUUID(),
                session.id(),
                null,
                request.reason() == null || request.reason().isBlank() ? "USER_REQUESTED_HUMAN" : request.reason(),
                summary,
                "OPEN",
                Instant.now(),
                Instant.now(),
                null
            ),
            actor.userId(),
            session.relatedTransactionId()
        );
        store.saveSupportMessage(
            new SupportChatMessage(UUID.randomUUID(), session.id(), "SYSTEM", "Your case has been sent to support.", Map.of("caseId", handoff.id().toString()), Instant.now()),
            "SYSTEM",
            null
        );
        increment("support_chat_handoffs_total");
        return new HandoffResponse(handoff.id(), session.id(), handoff.status(), "Your case has been sent to support.");
    }

    public PageResponse<SupportCaseSummary> listCases(UUID actorId, int page, int size, String status, String topic, String q) {
        requireAdminActor(actorId);
        String statusFilter = normalize(status);
        String topicFilter = normalize(topic);
        String query = q == null ? "" : q.trim().toLowerCase();
        List<SupportCaseSummary> values = store.supportCases().stream()
            .map(this::toCaseSummary)
            .filter(item -> statusFilter.isBlank() || item.status().equals(statusFilter))
            .filter(item -> topicFilter.isBlank() || topicFilter.equals(item.topic()))
            .filter(item -> query.isBlank() || matchesSupportCaseQuery(item, query))
            .toList();
        return page(values, page, size);
    }

    private boolean matchesSupportCaseQuery(SupportCaseSummary item, String query) {
        return item.caseId().toString().contains(query)
            || item.sessionId().toString().contains(query)
            || item.userId().toString().contains(query)
            || containsUuid(item.relatedTransactionId(), query)
            || containsUuid(item.assignedAdminId(), query)
            || item.status().toLowerCase().contains(query)
            || item.topic().toLowerCase().contains(query);
    }

    private boolean containsUuid(UUID value, String query) {
        return value != null && value.toString().contains(query);
    }

    public SupportCaseDetail getCase(UUID actorId, UUID caseId) {
        requireAdminActor(actorId);
        return toCaseDetail(store.supportCase(caseId));
    }

    public AdminReplyResponse adminReply(UUID actorId, UUID caseId, AdminReplyRequest request) {
        UUID admin = requireAdminActor(actorId);
        SupportCaseHandoff handoff = store.markSupportCaseInProgress(caseId, admin);
        SupportChatMessage message = store.saveSupportMessage(
            new SupportChatMessage(UUID.randomUUID(), handoff.sessionId(), "ADMIN", sanitize(request.message()), Map.of("caseId", caseId.toString()), Instant.now()),
            "ADMIN",
            admin
        );
        return new AdminReplyResponse(message.id(), caseId, handoff.status());
    }

    public CloseCaseResponse closeCase(UUID actorId, UUID caseId, CloseCaseRequest request) {
        UUID admin = requireAdminActor(actorId);
        SupportCaseHandoff closed = store.closeSupportCase(caseId, request.resolution(), admin);
        return new CloseCaseResponse(closed.id(), closed.status(), closed.resolvedAt());
    }

    private AssistantAnswer answer(String message, WalletTransaction tx, String topic, boolean lookupMissing) {
        boolean vietnamese = isVietnamese(message);
        if (containsAny(message, "human", "operator", "support agent", "fraud", "scam", "account takeover",
            "nhan vien", "nhân viên", "nguoi that", "người thật", "ho tro vien", "hỗ trợ viên", "lua dao", "lừa đảo")) {
            return new AssistantAnswer(
                vietnamese
                    ? "Tôi có thể chuyển cuộc trò chuyện này cho nhân viên hỗ trợ. Không chia sẻ PIN, mật khẩu, OTP, access token hoặc refresh token trong chat."
                    : "I can hand this conversation to a support operator. Do not share your PIN, password, OTP, access token, or refresh token.",
                Map.of("requiresHandoff", "true"),
                List.of(new SuggestedAction("CONTACT_HUMAN_SUPPORT", vietnamese ? "Liên hệ nhân viên hỗ trợ" : "Contact human support", null)),
                true
            );
        }
        if (containsAny(message, "pin", "password", "otp", "token", "mat khau", "mật khẩu")) {
            return new AssistantAnswer(
                vietnamese
                    ? "Để bảo vệ tài khoản, không chia sẻ PIN, mật khẩu, OTP, access token hoặc refresh token trong chat. Bạn có thể đổi PIN giao dịch trong phần cài đặt bảo mật."
                    : "For account safety, do not share PIN, password, OTP, access tokens, or refresh tokens in chat. You can change your transaction PIN from security settings.",
                Map.of("safety", "secret_redirection"),
                List.of(new SuggestedAction("OPEN_PIN_SETTINGS", vietnamese ? "Mở cài đặt PIN" : "Open PIN settings", null)),
                true
            );
        }
        if (lookupMissing) {
            increment("support_chat_tool_call_failures_total");
            return new AssistantAnswer(
                vietnamese
                    ? "Tôi không thể xác nhận trạng thái giao dịch đó từ dữ liệu hiện có. Vui lòng kiểm tra lại mã giao dịch hoặc liên hệ nhân viên hỗ trợ kèm traceId nếu có. Không chia sẻ PIN, mật khẩu, OTP, access token hoặc refresh token."
                    : "I cannot confirm the status for that transaction from the current data. Please check the transaction ID or contact human support with your traceId if available. Do not share PIN, password, OTP, access token, or refresh token.",
                Map.of("requiresHandoff", "true", "toolError", "TRANSACTION_NOT_FOUND"),
                List.of(new SuggestedAction("CONTACT_HUMAN_SUPPORT", vietnamese ? "Liên hệ nhân viên hỗ trợ" : "Contact human support", null)),
                true
            );
        }
        if (tx == null) {
            increment("support_chat_unknown_intent_total");
            return generalAnswer(topic, vietnamese);
        }
        List<SuggestedAction> txActions = new ArrayList<>();
        txActions.add(new SuggestedAction("OPEN_TRANSACTION_DETAIL", vietnamese ? "Mở chi tiết giao dịch" : "Open transaction detail", tx.id()));
        txActions.add(new SuggestedAction("CONTACT_HUMAN_SUPPORT", vietnamese ? "Liên hệ nhân viên hỗ trợ" : "Contact human support", null));
        String traceId = tx.correlationId() == null ? tx.id().toString() : tx.correlationId().toString();
        String suffix = vietnamese
            ? " Khi liên hệ hỗ trợ, hãy cung cấp mã giao dịch, traceId, thời gian giao dịch, số tiền, tiền tệ và trạng thái hiện tại. Không chia sẻ PIN, mật khẩu, OTP, access token hoặc refresh token."
            : " When contacting support, include transaction ID, traceId, transaction time, amount, currency, and current status. Do not share PIN, password, OTP, access token, or refresh token.";
        String text = switch (tx.status()) {
            case PENDING -> vietnamese
                ? "Giao dịch chuyển tiền của bạn đang chờ xử lý. Hệ thống đã nhận yêu cầu và vẫn đang xử lý. Không gửi lại cùng một giao dịch khi giao dịch còn đang pending."
                : "Your transfer is currently pending. The system has received your request and is still processing it. Do not submit the same transfer again while it is pending.";
            case COMPLETED -> vietnamese
                ? "Giao dịch chuyển tiền đã hoàn tất thành công. Người nhận sẽ thấy số tiền được cộng sau khi màn hình số dư được làm mới."
                : "Your transfer was completed successfully. The recipient should now see the credited amount, subject to normal display refresh timing.";
            case COMPENSATING -> vietnamese
                ? "Giao dịch đang được bồi hoàn. Bước trừ tiền đã thành công nhưng bước cộng tiền cho người nhận thất bại, nên hệ thống đang đảo ngược khoản trừ. Vui lòng kiểm tra chi tiết giao dịch để xem kết quả cuối cùng."
                : "The transfer is being compensated. The debit succeeded, but the credit step failed, so the system is reversing the debit. Please check the transaction detail screen for the final result.";
            case CANCELLED -> vietnamese
                ? "Giao dịch này đã bị hủy khi vẫn còn chờ xử lý và trước khi tiền bị trừ."
                : "This transfer was cancelled while it was still pending and before any money was debited.";
            case FAILED -> failedAnswer(tx, vietnamese);
        };
        return new AssistantAnswer(text + " TraceId: " + traceId + "." + suffix, transactionMetadata(tx), txActions, false);
    }

    private AssistantAnswer generalAnswer(String topic, boolean vietnamese) {
        String text = switch (topic) {
            case "PIN_HELP" -> vietnamese
                ? "Bạn có thể đổi PIN giao dịch trong phần cài đặt bảo mật. Không chia sẻ PIN, mật khẩu, OTP, access token hoặc refresh token với bất kỳ ai."
                : "You can change your transaction PIN from security settings. Never share your PIN, password, OTP, access token, or refresh token with anyone.";
            case "BALANCE_DISPLAY" -> vietnamese
                ? "Số dư hiển thị có thể chậm trong lúc ứng dụng làm mới. Số dư chính xác được tính ở backend từ ledger và bảng account balances, không dựa vào cache của ứng dụng."
                : "Displayed balance can lag briefly while the app refreshes. The authoritative balance is calculated on the backend from ledger-derived account balances, not from the app cache.";
            case "RECIPIENT_LOOKUP" -> vietnamese
                ? "Tra cứu người nhận có thể thất bại nếu email hoặc số điện thoại không đúng, người nhận không hoạt động, hoặc tài khoản không đủ điều kiện nhận tiền."
                : "Recipient lookup can fail if the email or phone number is incorrect, the recipient is inactive, or the account is not eligible to receive transfers.";
            case "APP_TECHNICAL_ISSUE" -> vietnamese
                ? "Chuyển tiền cần kết nối tới backend để xác thực yêu cầu, kiểm tra PIN, kiểm tra số dư và tạo giao dịch phía server."
                : "Transfers require a live connection so the backend can authenticate the request, verify PIN, check balance, and create a server-side transaction.";
            default -> vietnamese
                ? "Tôi có thể giải thích trạng thái chuyển tiền, giao dịch thất bại, hoàn tiền, traceId, an toàn PIN, tra cứu người nhận và hiển thị số dư. Nếu câu hỏi liên quan một giao dịch cụ thể, hãy mở chi tiết giao dịch rồi hỏi tại đó."
                : "I can help explain transfer status, failed transfers, refunds, traceId, PIN safety, recipient lookup, and balance display. If your question is about a specific transaction, open the transaction detail and ask about it.";
        };
        return new AssistantAnswer(
            text,
            Map.of("topic", topic),
            List.of(new SuggestedAction("CONTACT_HUMAN_SUPPORT", vietnamese ? "Liên hệ nhân viên hỗ trợ" : "Contact human support", null)),
            false
        );
    }

    private String failedAnswer(WalletTransaction tx, boolean vietnamese) {
        String reason = tx.failureReason() == null ? "UNKNOWN" : tx.failureReason();
        String normalized = reason.split(":", 2)[0].trim().toUpperCase(Locale.ROOT);
        if (tx.isCompensated()) {
            return vietnamese
                ? "Giao dịch thất bại sau bước trừ tiền ban đầu, nhưng dữ liệu giao dịch cho thấy compensated = true, nên khoản trừ đã được hoàn lại vào ví của bạn. Lý do: " + explainFailure(normalized, true)
                : "This transfer failed after the initial debit step, but the transaction shows compensated = true, so the debit was reversed back to your wallet. Reason: " + explainFailure(normalized, false);
        }
        if ("INSUFFICIENT_FUNDS".equals(normalized)) {
            return vietnamese
                ? "Giao dịch thất bại vì số dư khả dụng không đủ tại bước kiểm tra số dư phía server. Không cần hoàn tiền vì tiền chưa bị trừ thành công."
                : "This transfer failed because the available balance was not sufficient at the server-side balance check. No refund is required because the money was not successfully debited.";
        }
        return vietnamese
            ? "Giao dịch này thất bại. Tôi không thể xác nhận đã hoàn tiền vì giao dịch không có compensated = true. Lý do: " + explainFailure(normalized, true)
            : "This transfer failed. I cannot confirm a refund because the transaction does not show compensated = true. Reason: " + explainFailure(normalized, false);
    }

    private String explainFailure(String reason, boolean vietnamese) {
        return switch (reason) {
            case "INSUFFICIENT_FUNDS" -> vietnamese ? "người gửi không có đủ số dư khả dụng." : "the sender did not have enough available balance.";
            case "RECIPIENT_INACTIVE", "RECIPIENT_SUSPENDED" -> vietnamese ? "tài khoản người nhận hiện không đủ điều kiện nhận tiền." : "the recipient account is not currently eligible to receive funds.";
            case "DEBIT_FAILED" -> vietnamese ? "hệ thống không thể trừ tiền từ tài khoản người gửi." : "the system could not debit the sender account.";
            case "CREDIT_FAILED_COMPENSATED" -> vietnamese ? "không thể cộng tiền cho người nhận và cần kiểm tra bồi hoàn trong chi tiết giao dịch." : "the receiver could not be credited, and compensation should be checked on the transaction detail.";
            case "PIN_INVALID" -> vietnamese ? "PIN giao dịch không đúng." : "the transaction PIN was incorrect.";
            case "TIMEOUT" -> vietnamese ? "giao dịch không hoàn tất trong thời gian dự kiến." : "the transaction did not complete in the expected time window.";
            default -> vietnamese ? "backend trả về " + reason + "." : "the backend reported " + reason + ".";
        };
    }

    private String classify(String message, WalletTransaction tx) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAny(text, "human", "operator", "support agent", "nhan vien", "nhân viên", "nguoi that", "người thật", "ho tro vien", "hỗ trợ viên")) return "HUMAN_SUPPORT";
        if (containsAny(text, "fraud", "scam", "takeover", "lua dao", "lừa đảo", "chiem doat", "chiếm đoạt")) return "FRAUD_OR_SCAM";
        if (containsAny(text, "refund", "compensat", "money back", "hoan tien", "hoàn tiền", "hoan lai", "hoàn lại", "boi hoan", "bồi hoàn", "tien ve", "tiền về")) return "TRANSFER_REFUND";
        if (containsAny(text, "fail", "failed", "reason", "inactive", "that bai", "thất bại", "khong thanh cong", "không thành công", "ly do", "lý do")) return "TRANSFER_FAILED";
        if (containsAny(text, "pin", "password", "otp", "token", "mat khau", "mật khẩu")) return "PIN_HELP";
        if (containsAny(text, "recipient", "lookup", "email", "phone", "nguoi nhan", "người nhận", "tra cuu", "tra cứu", "so dien thoai", "số điện thoại", "sdt")) return "RECIPIENT_LOOKUP";
        if (containsAny(text, "balance", "display", "cache", "updated", "so du", "số dư", "hien thi", "hiển thị", "cap nhat", "cập nhật")) return "BALANCE_DISPLAY";
        if (containsAny(text, "offline", "connection", "network", "mat mang", "mất mạng", "ket noi", "kết nối")) return "APP_TECHNICAL_ISSUE";
        if (tx != null) return "TRANSFER_STATUS";
        return "GENERAL_FAQ";
    }

    private void validateAnswer(AssistantAnswer answer, WalletTransaction tx) {
        String text = answer.text().toLowerCase(Locale.ROOT);
        if (text.contains("refunded") || text.contains("reversed back") || text.contains("đã được hoàn lại") || text.contains("đã hoàn lại") || text.contains("đã được đảo ngược")) {
            if (tx == null || !tx.isCompensated()) {
                throw new DomainException("SUPPORT_RESPONSE_POLICY_VIOLATION", "Refund response is not grounded by compensated transaction data");
            }
        }
        if (text.contains("completed successfully") && (tx == null || tx.status() != TransactionStatus.COMPLETED)) {
            throw new DomainException("SUPPORT_RESPONSE_POLICY_VIOLATION", "Completion response is not grounded by completed transaction data");
        }
    }

    private WalletTransaction accessibleTransactionOrNull(UUID transactionId, AuthenticatedUser actor) {
        WalletTransaction tx = store.findTransaction(transactionId).orElse(null);
        if (tx == null) {
            return null;
        }
        if (!tx.senderId().equals(actor.accountId()) && !tx.receiverId().equals(actor.accountId())) {
            throw new DomainException("FORBIDDEN", "Cannot access another user's transaction");
        }
        return tx;
    }

    private SupportChatSession ownedSession(UUID sessionId, UUID userId) {
        SupportChatSession session = store.supportSession(sessionId);
        if (!session.userId().equals(userId)) {
            throw new DomainException("FORBIDDEN", "Cannot access another user's support session");
        }
        return session;
    }

    private void recordToolCall(UUID sessionId, UUID messageId, WalletTransaction tx, UUID requestedTransactionId) {
        if (requestedTransactionId == null) {
            return;
        }
        store.recordSupportToolCall(
            sessionId,
            messageId,
            "getTransaction",
            Map.of("transactionId", requestedTransactionId.toString()),
            tx == null ? Map.of("found", "false") : transactionMetadata(tx),
            tx != null,
            tx == null ? "TRANSACTION_NOT_FOUND" : null,
            tx == null ? sessionId : tx.correlationId()
        );
    }

    private Map<String, String> transactionMetadata(WalletTransaction tx) {
        return Map.of(
            "status", tx.status().name(),
            "failureReason", tx.failureReason() == null ? "" : tx.failureReason(),
            "compensated", String.valueOf(tx.isCompensated()),
            "traceId", tx.correlationId() == null ? tx.id().toString() : tx.correlationId().toString()
        );
    }

    private List<Citation> citations(WalletTransaction tx) {
        if (tx == null) {
            return List.of();
        }
        return List.of(new Citation(
            "TRANSACTION",
            tx.id(),
            List.of("status", "failureReason", "compensated", "correlationId")
        ));
    }

    private SupportCaseSummary toCaseSummary(SupportCaseHandoff handoff) {
        SupportChatSession session = store.supportSession(handoff.sessionId());
        return new SupportCaseSummary(
            handoff.id(),
            session.id(),
            session.userId(),
            handoff.status(),
            session.topic(),
            session.relatedTransactionId(),
            handoff.assignedAdminId(),
            handoff.createdAt(),
            handoff.updatedAt()
        );
    }

    private SupportCaseDetail toCaseDetail(SupportCaseHandoff handoff) {
        SupportChatSession session = store.supportSession(handoff.sessionId());
        WalletTransaction tx = session.relatedTransactionId() == null
            ? null
            : store.findTransaction(session.relatedTransactionId()).orElse(null);
        return new SupportCaseDetail(
            handoff.id(),
            session.id(),
            handoff.status(),
            session.topic(),
            session.relatedTransactionId(),
            handoff.summary(),
            store.supportMessages(session.id()),
            tx == null ? null : new TransactionSnapshot(tx.id(), tx.status().name(), tx.failureReason(), tx.isCompensated(), tx.correlationId())
        );
    }

    private SessionDetail toSessionDetail(SupportChatSession session) {
        return new SessionDetail(
            session.id(),
            session.status(),
            session.topic(),
            session.relatedTransactionId(),
            store.supportMessages(session.id())
        );
    }

    private String summarize(SupportChatSession session, List<SupportChatMessage> messages, UUID relatedTransactionId) {
        String question = messages.stream()
            .filter(message -> "USER".equals(message.senderType()))
            .max(Comparator.comparing(SupportChatMessage::createdAt))
            .map(SupportChatMessage::message)
            .orElse("");
        return "User asked: " + question + ". Topic: " + session.topic()
            + ". Related transaction: " + (relatedTransactionId == null ? "none" : relatedTransactionId) + ".";
    }

    private String sanitize(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            throw new DomainException("SUPPORT_MESSAGE_REQUIRED", "Support message is required");
        }
        return SECRET_PATTERN.matcher(text).replaceAll("$1 [REDACTED]");
    }

    private boolean containsAny(String value, String... needles) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVietnamese(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return containsAny(text,
            "à", "á", "ả", "ã", "ạ", "ă", "ằ", "ắ", "ẳ", "ẵ", "ặ", "â", "ầ", "ấ", "ẩ", "ẫ", "ậ",
            "đ", "è", "é", "ẻ", "ẽ", "ẹ", "ê", "ề", "ế", "ể", "ễ", "ệ",
            "ì", "í", "ỉ", "ĩ", "ị", "ò", "ó", "ỏ", "õ", "ọ", "ô", "ồ", "ố", "ổ", "ỗ", "ộ",
            "ơ", "ờ", "ớ", "ở", "ỡ", "ợ", "ù", "ú", "ủ", "ũ", "ụ", "ư", "ừ", "ứ", "ử", "ữ", "ự",
            "ỳ", "ý", "ỷ", "ỹ", "ỵ", "hoan tien", "hoan lai", "boi hoan", "so du", "nguoi nhan",
            "mat khau", "giao dich", "chuyen tien", "ket noi", "ho tro"
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private AuthenticatedUser requireUser(AuthenticatedUser user) {
        if (user == null || user.userId() == null || user.accountId() == null) {
            throw new DomainException("FORBIDDEN", "User session is required");
        }
        return user;
    }

    private UUID requireAdminActor(UUID actorId) {
        if (actorId == null) {
            throw new DomainException("FORBIDDEN", "Admin actor is required");
        }
        return actorId;
    }

    private void increment(String name) {
        increment(name, 1);
    }

    private void increment(String name, int amount) {
        Counter counter = meterRegistry.counter(name);
        counter.increment(amount);
    }

    private <T> PageResponse<T> page(List<T> values, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        int totalElements = values.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        return new PageResponse<>(new ArrayList<>(values.subList(fromIndex, toIndex)), page, size, totalElements, totalPages);
    }

    private record AssistantAnswer(String text, Map<String, String> metadata, List<SuggestedAction> suggestedActions, boolean requiresHandoff) {
    }

    public record SupportContext(UUID transactionId) {
    }

    public record CreateSessionRequest(String initialMessage, SupportContext context) {
    }

    public record CreateSessionResponse(UUID sessionId, String status, String answer, List<SuggestedAction> suggestedActions, UUID traceId) {
    }

    public record SendMessageRequest(String message, SupportContext context) {
    }

    public record MessageResponse(UUID messageId, String answer, List<Citation> citations, List<SuggestedAction> suggestedActions, UUID traceId) {
    }

    public record SessionDetail(UUID sessionId, String status, String topic, UUID relatedTransactionId, List<SupportChatMessage> messages) {
    }

    public record SessionSummary(UUID sessionId, String status, String topic, UUID relatedTransactionId, Instant updatedAt) {
    }

    public record HandoffRequest(String reason) {
    }

    public record HandoffResponse(UUID caseId, UUID sessionId, String status, String message) {
    }

    public record SuggestedAction(String type, String label, UUID targetId) {
    }

    public record Citation(String type, UUID id, List<String> fields) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    }

    public record SupportCaseSummary(
        UUID caseId,
        UUID sessionId,
        UUID userId,
        String status,
        String topic,
        UUID relatedTransactionId,
        UUID assignedAdminId,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record SupportCaseDetail(
        UUID caseId,
        UUID sessionId,
        String status,
        String topic,
        UUID relatedTransactionId,
        String summary,
        List<SupportChatMessage> messages,
        TransactionSnapshot transactionSnapshot
    ) {
    }

    public record TransactionSnapshot(UUID id, String status, String failureReason, boolean compensated, UUID traceId) {
    }

    public record AdminReplyRequest(String message) {
    }

    public record AdminReplyResponse(UUID messageId, UUID caseId, String status) {
    }

    public record CloseCaseRequest(String resolution) {
    }

    public record CloseCaseResponse(UUID caseId, String status, Instant closedAt) {
    }
}
