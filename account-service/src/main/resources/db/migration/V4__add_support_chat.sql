CREATE TABLE support_chat_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('OPEN', 'HANDED_OFF', 'RESOLVED', 'CLOSED')),
    topic VARCHAR(50),
    related_transaction_id UUID REFERENCES transactions(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_support_chat_sessions_user
    ON support_chat_sessions(user_id);

CREATE INDEX idx_support_chat_sessions_tx
    ON support_chat_sessions(related_transaction_id);

CREATE INDEX idx_support_chat_sessions_status
    ON support_chat_sessions(status);

CREATE TABLE support_chat_messages (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES support_chat_sessions(id),
    sender_type VARCHAR(20) NOT NULL
        CHECK (sender_type IN ('USER', 'AI', 'ADMIN', 'SYSTEM')),
    message TEXT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_support_chat_messages_session
    ON support_chat_messages(session_id, created_at);

CREATE TABLE support_case_handoffs (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES support_chat_sessions(id),
    assigned_admin_id UUID REFERENCES users(id),
    reason VARCHAR(100) NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ
);

CREATE INDEX idx_support_case_handoffs_session
    ON support_case_handoffs(session_id);

CREATE INDEX idx_support_case_handoffs_status
    ON support_case_handoffs(status);

CREATE INDEX idx_support_case_handoffs_admin
    ON support_case_handoffs(assigned_admin_id);

CREATE TABLE support_ai_tool_calls (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES support_chat_sessions(id),
    message_id UUID REFERENCES support_chat_messages(id),
    tool_name VARCHAR(100) NOT NULL,
    request_metadata JSONB NOT NULL,
    response_metadata JSONB NOT NULL,
    success BOOLEAN NOT NULL,
    error_code VARCHAR(100),
    trace_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_support_ai_tool_calls_session
    ON support_ai_tool_calls(session_id);

CREATE INDEX idx_support_ai_tool_calls_trace
    ON support_ai_tool_calls(trace_id);
