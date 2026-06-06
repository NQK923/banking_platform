package com.ewallet.account.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.service.AccountUseCases;
import com.ewallet.account.service.AccountUseCases.BalanceResponse;
import com.ewallet.common.AccountKind;
import com.ewallet.common.AccountStatus;
import com.ewallet.common.DomainException;
import com.ewallet.contract.account.v1.GetBalanceRequest;
import com.ewallet.contract.account.v1.GetBalanceResponse;
import com.ewallet.contract.account.v1.LookupAccountRequest;
import com.ewallet.contract.account.v1.LookupAccountResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.testing.StreamRecorder;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AccountGrpcServiceTest {
    private final AccountUseCases accountUseCases = Mockito.mock(AccountUseCases.class);
    private final AccountGrpcService service = new AccountGrpcService(accountUseCases);

    @Test
    void getBalanceReturnsDecimalString() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountUseCases.balance(accountId))
            .thenReturn(new BalanceResponse(accountId.toString(), "1000", "VND"));

        StreamRecorder<GetBalanceResponse> recorder = StreamRecorder.create();
        service.getBalance(GetBalanceRequest.newBuilder().setAccountId(accountId.toString()).build(), recorder);

        assertThat(recorder.firstValue().get()).isEqualTo(GetBalanceResponse.newBuilder()
            .setAccountId(accountId.toString())
            .setBalance("1000")
            .setCurrency("VND")
            .build());
        assertThat(recorder.getError()).isNull();
    }

    @Test
    void lookupAccountReturnsStatusAndCurrency() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(accountId, UUID.randomUUID(), null, "VND", AccountKind.USER, AccountStatus.ACTIVE, 0, Instant.now());
        when(accountUseCases.lookup("receiver@example.test", null)).thenReturn(account);

        StreamRecorder<LookupAccountResponse> recorder = StreamRecorder.create();
        service.lookupAccount(
            LookupAccountRequest.newBuilder().setEmail("receiver@example.test").build(),
            recorder
        );

        assertThat(recorder.firstValue().get()).isEqualTo(LookupAccountResponse.newBuilder()
            .setAccountId(accountId.toString())
            .setStatus("ACTIVE")
            .setCurrency("VND")
            .build());
        assertThat(recorder.getError()).isNull();
    }

    @Test
    void lookupNotFoundMapsToGrpcNotFound() {
        when(accountUseCases.lookup("missing@example.test", null))
            .thenThrow(new DomainException("RECIPIENT_NOT_FOUND", "Recipient account was not found"));

        StreamRecorder<LookupAccountResponse> recorder = StreamRecorder.create();
        service.lookupAccount(
            LookupAccountRequest.newBuilder().setEmail("missing@example.test").build(),
            recorder
        );

        assertThat(recorder.getError()).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException error = (StatusRuntimeException) recorder.getError();
        assertThat(error.getStatus().getCode()).isEqualTo(Status.NOT_FOUND.getCode());
    }
}
