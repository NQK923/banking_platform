package com.ewallet.account.web;

import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.account.service.SupportChatUseCases;
import com.ewallet.account.service.SupportChatUseCases.AdminReplyRequest;
import com.ewallet.account.service.SupportChatUseCases.AdminReplyResponse;
import com.ewallet.account.service.SupportChatUseCases.CloseCaseRequest;
import com.ewallet.account.service.SupportChatUseCases.CloseCaseResponse;
import com.ewallet.account.service.SupportChatUseCases.CreateSessionRequest;
import com.ewallet.account.service.SupportChatUseCases.CreateSessionResponse;
import com.ewallet.account.service.SupportChatUseCases.HandoffRequest;
import com.ewallet.account.service.SupportChatUseCases.HandoffResponse;
import com.ewallet.account.service.SupportChatUseCases.MessageResponse;
import com.ewallet.account.service.SupportChatUseCases.PageResponse;
import com.ewallet.account.service.SupportChatUseCases.SendMessageRequest;
import com.ewallet.account.service.SupportChatUseCases.SessionDetail;
import com.ewallet.account.service.SupportChatUseCases.SessionSummary;
import com.ewallet.account.service.SupportChatUseCases.SupportCaseDetail;
import com.ewallet.account.service.SupportChatUseCases.SupportCaseSummary;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupportChatController {
    private final SupportChatUseCases supportChatUseCases;

    public SupportChatController(SupportChatUseCases supportChatUseCases) {
        this.supportChatUseCases = supportChatUseCases;
    }

    @PostMapping("/api/support/chat/sessions")
    CreateSessionResponse createSession(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestBody CreateSessionRequest request
    ) {
        return supportChatUseCases.createSession(user, request);
    }

    @PostMapping("/api/support/chat/sessions/{sessionId}/messages")
    MessageResponse sendMessage(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable UUID sessionId,
        @RequestBody SendMessageRequest request
    ) {
        return supportChatUseCases.sendMessage(user, sessionId, request);
    }

    @GetMapping("/api/support/chat/sessions/{sessionId}")
    SessionDetail getSession(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable UUID sessionId
    ) {
        return supportChatUseCases.getSession(user, sessionId);
    }

    @GetMapping("/api/support/chat/sessions")
    PageResponse<SessionSummary> listSessions(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return supportChatUseCases.listSessions(user, page, size);
    }

    @PostMapping("/api/support/chat/sessions/{sessionId}/handoff")
    HandoffResponse requestHandoff(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable UUID sessionId,
        @RequestBody HandoffRequest request
    ) {
        return supportChatUseCases.requestHandoff(user, sessionId, request);
    }

    @GetMapping("/api/admin/support/cases")
    PageResponse<SupportCaseSummary> listCases(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String topic
    ) {
        return supportChatUseCases.listCases(user == null ? null : user.userId(), page, size, status, topic);
    }

    @GetMapping("/api/admin/support/cases/{caseId}")
    SupportCaseDetail getCase(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable UUID caseId
    ) {
        return supportChatUseCases.getCase(user == null ? null : user.userId(), caseId);
    }

    @PostMapping("/api/admin/support/cases/{caseId}/reply")
    AdminReplyResponse adminReply(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable UUID caseId,
        @RequestBody AdminReplyRequest request
    ) {
        return supportChatUseCases.adminReply(user == null ? null : user.userId(), caseId, request);
    }

    @PostMapping("/api/admin/support/cases/{caseId}/close")
    CloseCaseResponse closeCase(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable UUID caseId,
        @RequestBody CloseCaseRequest request
    ) {
        return supportChatUseCases.closeCase(user == null ? null : user.userId(), caseId, request);
    }
}
