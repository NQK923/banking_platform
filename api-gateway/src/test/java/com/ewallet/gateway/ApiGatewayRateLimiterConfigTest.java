package com.ewallet.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ApiGatewayRateLimiterConfigTest {
    @Test
    void gatewayConfiguresRedisBackedRequestRateLimiter() throws Exception {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));
        String buildFile = Files.readString(Path.of("build.gradle.kts"));

        assertThat(buildFile).contains("spring-boot-starter-data-redis-reactive");
        assertThat(applicationYaml)
            .contains("RequestRateLimiter")
            .contains("redis-rate-limiter.replenishRate")
            .contains("redis-rate-limiter.burstCapacity")
            .contains("key-resolver: \"#{@userOrIpKeyResolver}\"")
            .contains("redis:");
    }

    @Test
    void rateLimitKeyFallsBackToRemoteIp() {
        var request = MockServerHttpRequest.get("/")
            .remoteAddress(new InetSocketAddress("203.0.113.10", 49152))
            .build();
        var exchange = MockServerWebExchange.from(request);

        String key = new ApiGatewayApplication().userOrIpKeyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("203.0.113.10");
    }
}
