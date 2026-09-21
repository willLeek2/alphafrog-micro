package world.willfrog.agentlangchain.acceptance;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import world.willfrog.agentlangchain.control.LangchainRunRejectedException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 创建路径上那道夹具门：带了编号就只能是「按夹具跑」或「当场报错」，没有第三条路。
 */
class AcceptanceFixtureGateTest {

    private static final String LANE = "lane-beta";
    private static final String GENERATION = "gen-1";

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final Environment environment = mock(Environment.class);

    @Test
    void requestWithoutFixtureIdNeverTouchesTheFixtureStore() {
        AcceptanceFixtureGate gate = gate(true);

        Optional<AcceptanceFixtureRow> admitted =
                gate.admitRequestContext("{\"execution_mode\":\"DAG\"}", LANE, GENERATION);

        assertThat(admitted).isEmpty();
        // 不带编号的普通请求：连开关都不读、夹具表一次都不查，行为与从前完全一致。
        verifyNoInteractions(store);
        verifyNoInteractions(environment);
    }

    @Test
    void blankContextIsTreatedAsOrdinaryRequest() {
        AcceptanceFixtureGate gate = gate(true);

        assertThat(gate.admitRequestContext(null, LANE, GENERATION)).isEmpty();
        assertThat(gate.admitRequestContext("  ", LANE, GENERATION)).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void fixtureRequestIsRejectedWhenControlSurfaceIsClosed() {
        AcceptanceFixtureGate gate = gate(false);

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "DAG"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_disabled"));
        // 控制面没开就不查库：夹具存不存在都无关紧要，这次请求一律不创建。
        verifyNoInteractions(store);
    }

    @Test
    void fixtureFromAnotherScopeIsRejected() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "DAG"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_not_found"));
        verify(store, never()).recordUse(anyString(), anyString(), anyString());
    }

    @Test
    void fixtureThatIsNotEnabledIsRejected() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(false, future(), "{\"executionMode\":\"DAG\"}")));

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "DAG"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_not_enabled"));
    }

    @Test
    void fixturePastItsAuthorisationIsRejected() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, OffsetDateTime.now().minusMinutes(1),
                        "{\"executionMode\":\"DAG\"}")));

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "DAG"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_expired"));
    }

    @Test
    void fixtureDisablementRacingTheAdmissionIsRejected() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), "{\"executionMode\":\"DAG\"}")));
        // 读回来到登记之间被停用或过期：这条路必须按不可用处理。
        when(store.recordUse(LANE, GENERATION, "fx-1")).thenReturn(false);

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "DAG"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_expired"));
    }

    @Test
    void explicitRequestModeMustMatchTheFrozenPlan() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), "{\"executionMode\":\"DAG\"}")));

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "LINEAR"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_scenario_mismatch"));
    }

    @Test
    void explicitRequestModeWithoutAFrozenPlanIsRejected() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), null)));

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "LINEAR"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_scenario_unproven"));
    }

    @Test
    void frozenPlanWithoutAModeIsRejectedAsBrokenFixture() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), "{\"analysis\":\"随便写点什么\"}")));

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "DAG"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_invalid"));
    }

    @Test
    void unreadableFrozenPlanIsRejected() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), "{\"executionMode\": \"DAG\"")));

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "DAG"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_invalid"));
    }

    @Test
    void unknownFrozenModeIsRejected() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), "{\"executionMode\":\"SIDEWAYS\"}")));

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "DAG"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_invalid"));
    }

    @Test
    void unreadableContextWithAFixtureTokenIsRejectedInsteadOfFallingBack() {
        AcceptanceFixtureGate gate = gate(true);

        assertThatThrownBy(() -> gate.admitRequestContext("{\"acceptanceFixtureId\": \"fx-1\"", LANE,
                GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_invalid"));
        verifyNoInteractions(store);
    }

    @Test
    void blankFixtureIdIsRejected() {
        AcceptanceFixtureGate gate = gate(true);

        assertThatThrownBy(() -> gate.admitRequestContext("{\"acceptanceFixtureId\": \"   \"}", LANE,
                GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_invalid"));
        verifyNoInteractions(store);
    }

    @Test
    void unknownRequestedModeIsRejected() {
        AcceptanceFixtureGate gate = gate(true);
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), "{\"executionMode\":\"DAG\"}")));

        assertThatThrownBy(() -> gate.admitRequestContext(context("fx-1", "SIDEWAYS"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_fixture_invalid"));
    }

    @Test
    void matchingRequestIsAdmittedAndRegistersOneUse() {
        AcceptanceFixtureGate gate = gate(true);
        AcceptanceFixtureRow fixture = row(true, future(), "{\"executionMode\":\"dag\"}");
        when(store.find(LANE, GENERATION, "fx-1")).thenReturn(Optional.of(fixture));
        when(store.recordUse(LANE, GENERATION, "fx-1")).thenReturn(true);

        Optional<AcceptanceFixtureRow> admitted =
                gate.admitRequestContext(context("fx-1", "DAG"), LANE, GENERATION);

        assertThat(admitted).contains(fixture);
        verify(store).recordUse(LANE, GENERATION, "fx-1");
    }

    @Test
    void requestWithoutAModeDefersToTheFrozenPlan() {
        AcceptanceFixtureGate gate = gate(true);
        AcceptanceFixtureRow fixture = row(true, future(), "{\"executionMode\":\"DAG\"}");
        when(store.find(LANE, GENERATION, "fx-1")).thenReturn(Optional.of(fixture));
        when(store.recordUse(LANE, GENERATION, "fx-1")).thenReturn(true);

        Optional<AcceptanceFixtureRow> admitted =
                gate.admitRequestContext("{\"acceptanceFixtureId\":\"fx-1\"}", LANE, GENERATION);

        assertThat(admitted).contains(fixture);
        verify(store).recordUse(eq(LANE), eq(GENERATION), eq("fx-1"));
    }

    private AcceptanceFixtureGate gate(boolean enabled) {
        when(environment.getProperty(AcceptanceFixtureGate.ENABLED_PROPERTY, Boolean.class, false))
                .thenReturn(enabled);
        return new AcceptanceFixtureGate(store, environment);
    }

    private static String context(String fixtureId, String executionMode) {
        return "{\"acceptanceFixtureId\":\"" + fixtureId + "\","
                + "\"execution_mode\":\"" + executionMode + "\"}";
    }

    private static OffsetDateTime future() {
        return OffsetDateTime.now().plusHours(1);
    }

    private static AcceptanceFixtureRow row(boolean enabled, OffsetDateTime expiresAt, String planJson) {
        return new AcceptanceFixtureRow("fx-1", "scenario-a", enabled, OffsetDateTime.now().minusMinutes(5),
                expiresAt, planJson, "[]", null);
    }

    private static String reason(Throwable error) {
        return ((LangchainRunRejectedException) error).getReason();
    }
}
