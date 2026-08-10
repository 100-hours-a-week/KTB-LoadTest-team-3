package com.ktb.chatapp.service;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.repository.RateLimitRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MongoTestContainer.class)
@TestPropertySource(properties = {
        "socketio.enabled=false"
})
@DisplayName("RateLimitService 통합 테스트")
class RateLimitServiceTest {

    @Autowired
    private RateLimitRepository rateLimitRepository;

    @Autowired
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitRepository.deleteAll();
    }

    @Test
    @DisplayName("최초 요청은 허용되고 TTL과 남은 횟수가 갱신된다")
    void checkRateLimit_AllowsFirstRequest() {
        int maxRequests = 5;
        Duration window = Duration.ofSeconds(60);
        String clientId = "ip:127.0.0.1";

        long beforeCall = Instant.now().getEpochSecond();
        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        long afterCall = Instant.now().getEpochSecond();

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(maxRequests);
        assertThat(result.remaining()).isEqualTo(maxRequests - 1);
        assertThat(result.windowSeconds()).isEqualTo(window.getSeconds());
        assertThat(result.retryAfterSeconds()).isPositive();
        assertThat(result.resetEpochSeconds())
                .isBetween(beforeCall + result.retryAfterSeconds(), afterCall + result.retryAfterSeconds());
    }

    @Test
    @DisplayName("요청 한도를 초과하면 차단된다")
    void checkRateLimit_DeniesWhenLimitExceeded() {
        int maxRequests = 5;
        Duration window = Duration.ofSeconds(60);
        String clientId = "ip:127.0.0.1";

        // 한도까지 요청을 수행
        for (int i = 0; i < maxRequests; i++) {
            RateLimitCheckResult result =
                    rateLimitService.checkRateLimit(clientId, maxRequests, window);
            assertThat(result.allowed()).isTrue();
        }

        // 한도 초과 요청
        long beforeCall = Instant.now().getEpochSecond();
        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        long afterCall = Instant.now().getEpochSecond();

        assertThat(result.allowed()).isFalse();
        assertThat(result.limit()).isEqualTo(maxRequests);
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isBetween(1L, window.getSeconds());
        assertThat(result.resetEpochSeconds())
                .isBetween(beforeCall + result.retryAfterSeconds(), afterCall + result.retryAfterSeconds());
    }

    @Test
    @DisplayName("연속 요청 시 카운트가 증가하고 남은 횟수가 감소한다")
    void checkRateLimit_DecreasesRemainingOnConsecutiveRequests() {
        int maxRequests = 3;
        Duration window = Duration.ofSeconds(60);
        String clientId = "ip:192.168.1.1";

        RateLimitCheckResult result1 =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        assertThat(result1.allowed()).isTrue();
        assertThat(result1.remaining()).isEqualTo(2);

        RateLimitCheckResult result2 =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        assertThat(result2.allowed()).isTrue();
        assertThat(result2.remaining()).isEqualTo(1);

        RateLimitCheckResult result3 =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        assertThat(result3.allowed()).isTrue();
        assertThat(result3.remaining()).isZero();
    }

    @Test
    @DisplayName("서로 다른 클라이언트는 독립적인 rate limit을 갖는다")
    void checkRateLimit_IndependentLimitsPerClient() {
        int maxRequests = 2;
        Duration window = Duration.ofSeconds(60);
        String clientId1 = "ip:10.0.0.1";
        String clientId2 = "ip:10.0.0.2";

        // 첫 번째 클라이언트가 한도까지 요청
        for (int i = 0; i < maxRequests; i++) {
            RateLimitCheckResult result =
                    rateLimitService.checkRateLimit(clientId1, maxRequests, window);
            assertThat(result.allowed()).isTrue();
        }

        // 첫 번째 클라이언트 한도 초과
        RateLimitCheckResult result1 =
                rateLimitService.checkRateLimit(clientId1, maxRequests, window);
        assertThat(result1.allowed()).isFalse();

        // 두 번째 클라이언트는 여전히 요청 가능
        RateLimitCheckResult result2 =
                rateLimitService.checkRateLimit(clientId2, maxRequests, window);
        assertThat(result2.allowed()).isTrue();
        assertThat(result2.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("한도 초과 요청은 저장된 count를 증가시키지 않는다")
    void checkRateLimit_DeniedRequest_DoesNotIncrementCount() {
        int maxRequests = 3;
        String clientId = "no-extra-increment";

        for (int i = 0; i < maxRequests + 2; i++) {
            rateLimitService.checkRateLimit(clientId, maxRequests, Duration.ofSeconds(60));
        }

        assertThat(rateLimitRepository.findAll()).singleElement()
                .extracting(rateLimit -> rateLimit.getCount())
                .isEqualTo(maxRequests);
    }

    @Test
    @DisplayName("만료된 window는 count 1과 새 expiresAt으로 원자 reset된다")
    void checkRateLimit_ExpiredWindow_ResetsCountAndExpiration() {
        String clientId = "expired-window";
        rateLimitService.checkRateLimit(clientId, 3, Duration.ofSeconds(60));
        com.ktb.chatapp.model.RateLimit stored = rateLimitRepository.findAll().getFirst();
        Instant oldExpiration = Instant.now().minusSeconds(1);
        stored.setCount(3);
        stored.setExpiresAt(oldExpiration);
        rateLimitRepository.save(stored);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(clientId, 3, Duration.ofSeconds(60));
        com.ktb.chatapp.model.RateLimit reset = rateLimitRepository.findById(stored.getId()).orElseThrow();

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(reset.getCount()).isEqualTo(1);
        assertThat(reset.getExpiresAt()).isAfter(oldExpiration);
        assertThat(result.resetEpochSeconds()).isEqualTo(reset.getExpiresAt().getEpochSecond());
    }

    @Test
    @DisplayName("동일 clientId 동시 요청은 한도까지만 허용되고 lost update가 없다")
    void checkRateLimit_ConcurrentRequests_AreAtomic() throws Exception {
        int maxRequests = 20;
        int attempts = 80;
        String clientId = "concurrent-client";
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Callable<RateLimitCheckResult>> tasks = java.util.stream.IntStream.range(0, attempts)
                    .mapToObj(ignored -> (Callable<RateLimitCheckResult>) () ->
                            rateLimitService.checkRateLimit(clientId, maxRequests, Duration.ofSeconds(60)))
                    .toList();

            long allowed = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(RateLimitCheckResult::allowed)
                    .count();
            com.ktb.chatapp.model.RateLimit stored = rateLimitRepository.findAll().getFirst();

            assertThat(allowed).isEqualTo(maxRequests);
            assertThat(stored.getCount()).isEqualTo(maxRequests);
        } finally {
            executor.shutdownNow();
        }
    }
}
