package world.willfrog.agentlangchain.acceptance;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import world.willfrog.agentlangchain.control.LangchainRunRejectedException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 创建路径上那道控制门：带了编号就只能是「按这一行的放行策略跑」或「当场报错」。
 */
class AcceptanceControlGateTest {

    private static final String LANE = "lane-beta";
    private static final String GENERATION = "gen-1";

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final Environment environment = mock(Environment.class);







    private AcceptanceControlGate gate(boolean enabled) {
        when(environment.getProperty(AcceptanceControlGate.ENV_FLAG, Boolean.class))
                .thenReturn(enabled);
        return new AcceptanceControlGate(store, environment);
    }

    private static String context(String controlId, String executionMode) {
        return "{\"acceptanceControlId\":\"" + controlId + "\","
                + "\"execution_mode\":\"" + executionMode + "\"}";
    }

    private static OffsetDateTime future() {
        return OffsetDateTime.now().plusHours(1);
    }

    private static AcceptanceFixtureRow row(boolean enabled, OffsetDateTime expiresAt, String planJson) {
        return new AcceptanceFixtureRow("ctrl-1", "scenario-a", enabled, OffsetDateTime.now().minusMinutes(5),
                expiresAt, planJson, "", "{\"rules\":[]}");
    }

    private static String reason(Throwable error) {
        return ((LangchainRunRejectedException) error).getReason();
    }
}
