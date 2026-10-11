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

    @Test
    void requestWithoutControlIdNeverTouchesTheFixtureStore() {
        AcceptanceControlGate gate = gate(true);

        Optional<AcceptanceFixtureRow> admitted =
                gate.admitRequestContext("{\"execution_mode\":\"DAG\"}", LANE, GENERATION);

        assertThat(admitted).isEmpty();
        verifyNoInteractions(store);
        verifyNoInteractions(environment);
    }

    @Test
    void controlRequestIsRejectedWhenControlSurfaceIsClosed() {
        AcceptanceControlGate gate = gate(false);

        assertThatThrownBy(() -> gate.admitRequestContext(context("ctrl-1", "DAG"), LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_control_disabled"));
        verifyNoInteractions(store);
    }

    @Test
    void matchingRequestIsAdmittedAndRegistersOneUse() {
        AcceptanceControlGate gate = gate(true);
        AcceptanceFixtureRow row = row(true, future(), null);
        when(store.find(LANE, GENERATION, "ctrl-1")).thenReturn(Optional.of(row));
        when(store.recordUse(LANE, GENERATION, "ctrl-1")).thenReturn(true);

        Optional<AcceptanceFixtureRow> admitted =
                gate.admitRequestContext(context("ctrl-1", "DAG"), LANE, GENERATION);

        assertThat(admitted).contains(row);
        verify(store).recordUse(eq(LANE), eq(GENERATION), eq("ctrl-1"));
    }

    @Test
    void childMemberControlIsValidatedButNotAppliedToParent() {
        AcceptanceControlGate gate = gate(true);
        AcceptanceFixtureRow row = row(true, future(), "{\"executionMode\":\"LINEAR\"}");
        when(store.find(LANE, GENERATION, "ctrl-1")).thenReturn(Optional.of(row));
        when(store.recordUse(LANE, GENERATION, "ctrl-1")).thenReturn(true);

        Optional<AcceptanceFixtureRow> parentControl = gate.admitRequestContext("""
                {"execution_mode":"DAG","childAcceptanceControls":[
                  {"for":{"planGeneration":1,"nodeId":"node-a","nodeAttempt":0,
                          "segmentSequence":0,"modelTurn":0,"memberSeq":0,"toolCallId":"call-a"},
                   "acceptanceControlId":"ctrl-1"}]}
                """, LANE, GENERATION);

        assertThat(parentControl).isEmpty();
        verify(store).recordUse(LANE, GENERATION, "ctrl-1");
    }

    @Test
    void missingChildControlRejectsParentRequest() {
        AcceptanceControlGate gate = gate(true);
        when(store.find(LANE, GENERATION, "ctrl-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gate.admitRequestContext("""
                {"childAcceptanceControls":[
                  {"for":{"planGeneration":1,"nodeId":"node-a","nodeAttempt":0,
                          "segmentSequence":0,"modelTurn":0,"memberSeq":0,"toolCallId":"call-a"},
                   "acceptanceControlId":"ctrl-1"}]}
                """, LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_control_not_found"));
    }

    @Test
    void parentAndChildCannotShareOneResultControl() {
        AcceptanceControlGate gate = gate(true);

        assertThatThrownBy(() -> gate.admitRequestContext("""
                {"acceptanceControlId":"ctrl-1","childAcceptanceControls":[
                  {"for":{"planGeneration":1,"nodeId":"node-a","nodeAttempt":0,
                          "segmentSequence":0,"modelTurn":0,"memberSeq":0,"toolCallId":"call-a"},
                   "acceptanceControlId":"ctrl-1"}]}
                """, LANE, GENERATION))
                .isInstanceOf(LangchainRunRejectedException.class)
                .satisfies(e -> assertThat(reason(e)).isEqualTo("acceptance_control_invalid"));
        verifyNoInteractions(store);
    }

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
