package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRequestRetryPolicyTest {

    @Test
    void fixedConstructorShouldPreserveExistingRetryDelayContract() throws Exception {
        List<Long> sleeps = new ArrayList<>();
        LlmRequestRetryPolicy policy = new LlmRequestRetryPolicy(1, 123L, sleeps::add);

        assertEquals(2, policy.maxAttempts());
        assertTrue(policy.shouldRetryStatus(429, 1));
        assertFalse(policy.shouldRetryStatus(429, 2));

        policy.sleepBeforeRetry(1);
        assertEquals(List.of(123L), sleeps);
    }

    @Test
    void withConfigShouldUseExponentialBackoffAndCapDelay() throws Exception {
        AgentLlmProperties.Retry retry = new AgentLlmProperties.Retry();
        retry.setBackoffType("exponential");
        retry.setBaseDelayMs(100L);
        retry.setMaxDelayMs(250L);
        retry.setJitterMs(0L);
        List<Long> sleeps = new ArrayList<>();
        LlmRequestRetryPolicy policy = new LlmRequestRetryPolicy(3, 100L, 250L, 0L, "exponential", sleeps::add);

        assertEquals(100L, policy.delayMs(1));
        assertEquals(200L, policy.delayMs(2));
        assertEquals(250L, policy.delayMs(3));
        policy.sleepBeforeRetry(2);
        assertEquals(List.of(200L), sleeps);
    }

    @Test
    void shouldRetryOnlyExplicitStatusesAndIoExceptions() {
        LlmRequestRetryPolicy policy = LlmRequestRetryPolicy.withMaxRetries(2);

        assertTrue(policy.isRetryableStatus(408));
        assertTrue(policy.isRetryableStatus(429));
        assertTrue(policy.isRetryableStatus(500));
        assertFalse(policy.isRetryableStatus(400));
        assertTrue(policy.isRetryableException(new IOException("timeout")));
        assertFalse(policy.isRetryableException(new IllegalStateException("bad request")));
    }

    @Test
    void checkAttemptBudgetShouldRejectExhaustedAttempt() {
        LlmRequestRetryPolicy policy = LlmRequestRetryPolicy.withMaxRetries(0);

        policy.checkAttemptBudget(1);
        assertThrows(IllegalStateException.class, () -> policy.checkAttemptBudget(2));
    }
}
