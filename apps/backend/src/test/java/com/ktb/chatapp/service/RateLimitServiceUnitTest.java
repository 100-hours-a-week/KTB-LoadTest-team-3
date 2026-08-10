package com.ktb.chatapp.service;

import com.ktb.chatapp.model.RateLimit;
import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import com.ktb.chatapp.service.ratelimit.RateLimitStore.ConsumeResult;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceUnitTest {

    private static final String HOST_NAME = "test-host";
    private static final String CLIENT_ID = "client-1";
    private static final String STORE_CLIENT_ID = HOST_NAME + ":" + CLIENT_ID;

    @Mock
    private RateLimitStore rateLimitStore;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(rateLimitStore);
        ReflectionTestUtils.setField(rateLimitService, "hostName", HOST_NAME);
    }

    @Test
    @DisplayName("최초 요청은 host-prefixed clientId로 원자 consume되고 남은 횟수를 반환한다")
    void checkRateLimit_FirstRequest_ConsumesHostPrefixedClientId() {
        RateLimit consumed = rateLimit(1, Instant.now().plusSeconds(30));
        when(rateLimitStore.consume(eq(STORE_CLIENT_ID), eq(3), any(), any()))
                .thenReturn(new ConsumeResult(true, consumed));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isBetween(1L, 30L);
        verify(rateLimitStore).consume(eq(STORE_CLIENT_ID), eq(3), any(), any());
    }

    @Test
    @DisplayName("원자 consume 결과의 count로 remaining을 계산한다")
    void checkRateLimit_ExistingBelowLimit_UsesIncrementedCount() {
        when(rateLimitStore.consume(eq(STORE_CLIENT_ID), eq(3), any(), any()))
                .thenReturn(new ConsumeResult(true, rateLimit(2, Instant.now().plusSeconds(20))));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("한도 도달 결과는 retry-after와 reset epoch를 반환한다")
    void checkRateLimit_LimitReached_ReturnsRetryAfter() {
        Instant expiresAt = Instant.now().plusSeconds(10);
        when(rateLimitStore.consume(eq(STORE_CLIENT_ID), eq(3), any(), any()))
                .thenReturn(new ConsumeResult(false, rateLimit(3, expiresAt)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isBetween(1L, 10L);
        assertThat(result.resetEpochSeconds()).isEqualTo(expiresAt.getEpochSecond());
    }

    @Test
    @DisplayName("0초 window는 최소 1초 window로 정규화된다")
    void checkRateLimit_ZeroWindow_NormalizesToOneSecond() {
        mockAllowedFirstRequest(STORE_CLIENT_ID, 1);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ZERO);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("null window는 최소 1초 window로 정규화된다")
    void checkRateLimit_NullWindow_NormalizesToOneSecond() {
        mockAllowedFirstRequest(STORE_CLIENT_ID, 1);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("만료 window consume 결과는 count 1과 새 expiration을 사용한다")
    void checkRateLimit_ExpiredStoredRateLimit_StartsNewWindow() {
        Instant resetExpiration = Instant.now().plusSeconds(30);
        when(rateLimitStore.consume(eq(STORE_CLIENT_ID), eq(3), any(), any()))
                .thenReturn(new ConsumeResult(true, rateLimit(1, resetExpiration)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.retryAfterSeconds()).isBetween(1L, 30L);
        assertThat(result.resetEpochSeconds()).isEqualTo(resetExpiration.getEpochSecond());
    }

    @Test
    @DisplayName("저장소 실패 시 요청은 허용하고 전체 한도를 남긴다")
    void checkRateLimit_StoreFailure_FailsOpenDeterministically() {
        when(rateLimitStore.consume(eq(STORE_CLIENT_ID), eq(3), any(), any()))
                .thenThrow(new IllegalStateException("store down"));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("null clientId도 host prefix가 적용된 저장소 key로 처리된다")
    void checkRateLimit_NullClientId_UsesHostPrefixedNullKey() {
        String storeClientId = HOST_NAME + ":null";
        mockAllowedFirstRequest(storeClientId, 30);
        ArgumentCaptor<String> clientIdCaptor = ArgumentCaptor.forClass(String.class);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(null, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        verify(rateLimitStore).consume(clientIdCaptor.capture(), eq(3), any(), any());
        assertThat(clientIdCaptor.getValue()).isEqualTo(storeClientId);
    }

    private void mockAllowedFirstRequest(String clientId, long windowSeconds) {
        when(rateLimitStore.consume(eq(clientId), eq(3), any(), any()))
                .thenReturn(new ConsumeResult(true, rateLimit(1, Instant.now().plusSeconds(windowSeconds))));
    }

    private RateLimit rateLimit(int count, Instant expiresAt) {
        return RateLimit.builder()
                .clientId(STORE_CLIENT_ID)
                .count(count)
                .expiresAt(expiresAt)
                .build();
    }
}
