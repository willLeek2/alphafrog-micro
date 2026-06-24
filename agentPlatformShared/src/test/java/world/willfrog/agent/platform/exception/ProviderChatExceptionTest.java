package world.willfrog.agent.platform.exception;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderChatExceptionTest {

    @Test
    void of_shouldScrubAuthorizationAndApiKeyFromRawAndMessage() {
        String raw = "{\"headers\":{\"Authorization\":\"Bearer sk-abc123xyz789\"},\"body\":\"error\"}";

        ProviderChatException ex = ProviderChatException.of(
                502,
                "bad_gateway",
                List.of("fireworks"),
                "moonshotai/kimi-k2.5",
                "openrouter",
                raw,
                ProviderFailureCategory.TRANSIENT_NETWORK,
                null
        );

        assertFalse(ex.getRawProviderMessage().contains("sk-abc123xyz789"));
        assertFalse(ex.getMessage().contains("sk-abc123xyz789"));
        assertTrue(ex.getRawProviderMessage().contains("<redacted>"));
        assertTrue(ex.getMessage().contains("<redacted>"));
        assertFalse(ex.getMessage().contains("Bearer sk-"));
    }

    @Test
    void of_shouldBoundRawProviderMessage() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append('a');
        }
        String raw = sb.toString();

        ProviderChatException ex = ProviderChatException.of(
                500,
                "",
                List.of(),
                "model",
                "endpoint",
                raw,
                ProviderFailureCategory.UNKNOWN,
                null
        );

        assertTrue(ex.getRawProviderMessage().length() <= ProviderChatException.RAW_MESSAGE_MAX_LENGTH + 3);
        assertTrue(ex.getMessage().length() <= ProviderChatException.RAW_MESSAGE_MAX_LENGTH + 200);
        assertTrue(ex.getRawProviderMessage().endsWith("..."));
    }

    @Test
    void constructor_shouldScrubRawEvenWhenDetailProvidedDirectly() {
        String raw = "Authorization: Bearer sk-live-xxxxxxxxxx";
        ProviderChatException ex = new ProviderChatException(
                401,
                "unauthorized",
                List.of("openrouter"),
                "model",
                "endpoint",
                raw,
                ProviderFailureCategory.AUTH_REJECTED,
                "custom detail",
                null
        );

        assertFalse(ex.getRawProviderMessage().contains("sk-live"));
        assertEquals("custom detail", ex.getMessage());
    }
}
