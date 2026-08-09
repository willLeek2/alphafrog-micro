package world.willfrog.agentlangchain.tooljob;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

/**
 * Characterization tests that lock current ToolJob resume state behavior
 * through real production seams (JSON roundtrip, launcher identity).
 *
 * Target: D12 resumeState single authority migration seam.
 */
@DisplayName("ToolJob State Characterization (D12 Step 1)")
class ToolJobStateCharacterizationTest {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private ToolJobAnchor anchor;

    @BeforeEach
    void setUp() {
        anchor = new ToolJobAnchor();
    }

    // ------------------------------------------------------------------
    // Dual-track: JSON roundtrip proves independent fields
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("JSON roundtrip preserves dual-track independence")
    class JsonRoundtrip {

        @Test
        @DisplayName("roundtrip preserves resumeState=LAUNCHING + resultConsumed=true independently")
        void roundtripPreservesBothFieldsIndependently() throws Exception {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setTaskId("task-1");
            anchor.setResumeState("LAUNCHING");
            anchor.setResultConsumed(true);

            String json = objectMapper.writeValueAsString(anchor);
            ToolJobAnchor restored = objectMapper.readValue(json, ToolJobAnchor.class);

            // Both fields survive JSON roundtrip independently
            assertThat(restored.getResumeState()).isEqualTo("LAUNCHING");
            assertThat(restored.isResultConsumed()).isTrue();
            // Proves: resultConsumed is a serialized field, not derived from resumeState
        }

        @Test
        @DisplayName("roundtrip preserves resumeState=CONSUMED + resultConsumed=false (write-order gap)")
        void roundtripPreservesWriteOrderGap() throws Exception {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("CONSUMED");
            // resultConsumed NOT set — the dual-track gap

            String json = objectMapper.writeValueAsString(anchor);
            ToolJobAnchor restored = objectMapper.readValue(json, ToolJobAnchor.class);

            assertThat(restored.getResumeState()).isEqualTo("CONSUMED");
            assertThat(restored.isResultConsumed()).isFalse();
            // Proves: the gap survives serialization — JSON has both fields at different values
        }

        @Test
        @DisplayName("roundtrip preserves full anchor identity (all CAS-relevant fields)")
        void roundtripPreservesAnchorIdentity() throws Exception {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setTaskId("task-123");
            anchor.setToolCallId("tc-1");
            anchor.setAttempt(1);
            anchor.setResumeState("READY");
            anchor.setResumeToken("token-v1");
            anchor.setResumeLeaseVersion(5L);
            anchor.setResumeLauncherOwnerId("owner-a");

            String json = objectMapper.writeValueAsString(anchor);
            ToolJobAnchor restored = objectMapper.readValue(json, ToolJobAnchor.class);

            assertThat(restored.getOperationId()).isEqualTo("run-1:tc-1:1");
            assertThat(restored.getTaskId()).isEqualTo("task-123");
            assertThat(restored.getResumeState()).isEqualTo("READY");
            assertThat(restored.getResumeToken()).isEqualTo("token-v1");
            assertThat(restored.getResumeLeaseVersion()).isEqualTo(5L);
            assertThat(restored.getResumeLauncherOwnerId()).isEqualTo("owner-a");
        }

        @Test
        @DisplayName("default anchor has null resumeState and false resultConsumed after roundtrip")
        void defaultAnchorHasNullResumeStateAndFalseResultConsumed() throws Exception {
            anchor.setOperationId("run-1:tc-1:1");

            String json = objectMapper.writeValueAsString(anchor);
            ToolJobAnchor restored = objectMapper.readValue(json, ToolJobAnchor.class);

            assertThat(restored.getResumeState()).isNull();
            assertThat(restored.isResultConsumed()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Target: resultConsumed derived from resumeState (migration seam)
    // ------------------------------------------------------------------

    @Disabled("D12 Step 3 production migration contract — activate when resumeState getter is authoritative")
    @Nested
    @DisplayName("Target migration seam: resultConsumed derivation")
    class TargetMigrationSeam {

        /**
         * Target derivation — resumeState is the single authority.
         * In production, ToolJobAnchor.isResultConsumed() will use this logic.
         */
        private static boolean targetResultConsumed(ToolJobAnchor a) {
            return "CONSUMED".equals(a.getResumeState());
        }

        @Test
        @DisplayName("target: resumeState=CONSUMED → resultConsumed=true (single truth)")
        void consumedDerivesTrue() {
            anchor.setResumeState("CONSUMED");

            assertThat(targetResultConsumed(anchor)).isTrue();
            // After D12: no independent setResultConsumed(true) needed
        }

        @Test
        @DisplayName("target: resumeState=READY → resultConsumed=false")
        void readyDerivesFalse() {
            anchor.setResumeState("READY");

            assertThat(targetResultConsumed(anchor)).isFalse();
        }

        @Test
        @DisplayName("target: resumeState=LAUNCHING → resultConsumed=false")
        void launchingDerivesFalse() {
            anchor.setResumeState("LAUNCHING");

            assertThat(targetResultConsumed(anchor)).isFalse();
        }

        @Test
        @DisplayName("target legacy compat: old resultConsumed=true field still readable during migration")
        void legacyFieldStillReadableDuringMigration() throws Exception {
            // Simulate old DB row: LAUNCHING + resultConsumed=true
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("LAUNCHING");
            anchor.setResultConsumed(true);

            String json = objectMapper.writeValueAsString(anchor);
            ToolJobAnchor restored = objectMapper.readValue(json, ToolJobAnchor.class);

            // Migration-period read: recognize old combination
            boolean migrationResult = "CONSUMED".equals(restored.getResumeState())
                    || restored.isResultConsumed();
            assertThat(migrationResult).isTrue();
            // After W7: remove the || restored.isResultConsumed() clause
        }
    }

}
