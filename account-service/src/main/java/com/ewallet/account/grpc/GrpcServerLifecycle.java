package com.ewallet.account.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {
    private final AccountGrpcService accountGrpcService;
    private final int port;
    private Server server;
    private boolean running;

    public GrpcServerLifecycle(AccountGrpcService accountGrpcService, @Value("${banking.grpc.port}") int port) {
        this.accountGrpcService = accountGrpcService;
        this.port = port;
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(port)
                .addService(accountGrpcService)
                .addService(ProtoReflectionService.newInstance())
                .build()
                .start();
            running = true;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to start gRPC server on port " + port, ex);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdownNow();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
