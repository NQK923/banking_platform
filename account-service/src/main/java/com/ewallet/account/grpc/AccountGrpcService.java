package com.ewallet.account.grpc;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.service.AccountUseCases;
import com.ewallet.account.service.AccountUseCases.BalanceResponse;
import com.ewallet.common.DomainException;
import com.ewallet.contract.account.v1.AccountQueryServiceGrpc;
import com.ewallet.contract.account.v1.GetBalanceRequest;
import com.ewallet.contract.account.v1.GetBalanceResponse;
import com.ewallet.contract.account.v1.LookupAccountRequest;
import com.ewallet.contract.account.v1.LookupAccountResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AccountGrpcService extends AccountQueryServiceGrpc.AccountQueryServiceImplBase {
    private final AccountUseCases accountUseCases;

    public AccountGrpcService(AccountUseCases accountUseCases) {
        this.accountUseCases = accountUseCases;
    }

    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<GetBalanceResponse> responseObserver) {
        try {
            BalanceResponse balance = accountUseCases.balance(UUID.fromString(request.getAccountId()));
            responseObserver.onNext(GetBalanceResponse.newBuilder()
                .setAccountId(balance.accountId())
                .setBalance(balance.balance())
                .setCurrency(balance.currency())
                .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException ex) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid account id").asRuntimeException());
        } catch (DomainException ex) {
            responseObserver.onError(toStatus(ex).asRuntimeException());
        }
    }

    @Override
    public void lookupAccount(LookupAccountRequest request, StreamObserver<LookupAccountResponse> responseObserver) {
        try {
            AccountRecord account = switch (request.getKeyCase()) {
                case EMAIL -> accountUseCases.lookup(request.getEmail(), null);
                case PHONE -> accountUseCases.lookup(null, request.getPhone());
                case KEY_NOT_SET -> throw new DomainException("IDENTIFIER_REQUIRED", "Email or phone is required");
            };
            responseObserver.onNext(LookupAccountResponse.newBuilder()
                .setAccountId(account.id().toString())
                .setStatus(account.status().name())
                .setCurrency(account.currency())
                .build());
            responseObserver.onCompleted();
        } catch (DomainException ex) {
            responseObserver.onError(toStatus(ex).asRuntimeException());
        }
    }

    private Status toStatus(DomainException ex) {
        return switch (ex.code()) {
            case "RECIPIENT_NOT_FOUND", "ACCOUNT_NOT_FOUND" -> Status.NOT_FOUND.withDescription(ex.getMessage());
            case "RECIPIENT_SUSPENDED" -> Status.FAILED_PRECONDITION.withDescription(ex.getMessage());
            case "IDENTIFIER_REQUIRED" -> Status.INVALID_ARGUMENT.withDescription(ex.getMessage());
            default -> Status.INTERNAL.withDescription(ex.getMessage());
        };
    }
}
