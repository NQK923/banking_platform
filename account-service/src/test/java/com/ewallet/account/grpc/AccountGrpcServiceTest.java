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
import io.grpc.stub.StreamObserver;
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

        RecordingObserver<GetBalanceResponse> recorder = new RecordingObserver<>();
        service.getBalance(GetBalanceRequest.newBuilder().setAccountId(accountId.toString()).build(), recorder);

        assertThat(recorder.value()).isEqualTo(GetBalanceResponse.newBuilder()
            .setAccountId(accountId.toString())
            .setBalance("1000")
            .setCurrency("VND")
            .build());
        assertThat(recorder.error()).isNull();
    }

    @Test
    void lookupAccountReturnsStatusAndCurrency() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(accountId, UUID.randomUUID(), null, "VND", AccountKind.USER, AccountStatus.ACTIVE, 0, Instant.now());
        when(accountUseCases.lookup("receiver@example.test", null)).thenReturn(account);

        RecordingObserver<LookupAccountResponse> recorder = new RecordingObserver<>();
        service.lookupAccount(
            LookupAccountRequest.newBuilder().setEmail("receiver@example.test").build(),
            recorder
        );

        assertThat(recorder.value()).isEqualTo(LookupAccountResponse.newBuilder()
            .setAccountId(accountId.toString())
            .setStatus("ACTIVE")
            .setCurrency("VND")
            .build());
        assertThat(recorder.error()).isNull();
    }

    @Test
    void lookupNotFoundMapsToGrpcNotFound() {
        when(accountUseCases.lookup("missing@example.test", null))
            .thenThrow(new DomainException("RECIPIENT_NOT_FOUND", "Recipient account was not found"));

        RecordingObserver<LookupAccountResponse> recorder = new RecordingObserver<>();
        service.lookupAccount(
            LookupAccountRequest.newBuilder().setEmail("missing@example.test").build(),
            recorder
        );

        assertThat(recorder.error()).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException error = (StatusRuntimeException) recorder.error();
        assertThat(error.getStatus().getCode()).isEqualTo(Status.NOT_FOUND.getCode());
    }

    private static class RecordingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
        }

        T value() {
            return value;
        }

        Throwable error() {
            return error;
        }
    }
}
