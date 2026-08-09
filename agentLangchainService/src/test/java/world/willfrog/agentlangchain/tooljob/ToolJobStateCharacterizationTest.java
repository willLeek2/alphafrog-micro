package world.willfrog.agentlangchain.tooljob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

/**
 * Characterization tests for the D12 4-state resumeState model
 * (READY → LAUNCHING → ACCEPTED → CONSUMED).
 *
 * Uses real production seams: ToolJobAnchor.fromJson normalization,
 * isResultConsumed derivation from resumeState.
 */
@DisplayName("ToolJob State Characterization (D12 Step 3)")
class ToolJobStateCharacterizationTest {

    private ToolJobAnchor anchor;

    @BeforeEach
    void setUp() {
        anchor = new ToolJobAnchor();
    }

    // ------------------------------------------------------------------
    // isResultConsumed derivation from 4-state resumeState
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("isResultConsumed derives from resumeState (single authority)")
    class ResultConsumedDerivation {

        @Test
        @DisplayName("READY → isResultConsumed = false")
        void readyIsNotConsumed() {
            anchor.setResumeState("READY");
            assertThat(anchor.isResultConsumed()).isFalse();
        }

        @Test
        @DisplayName("LAUNCHING (not accepted) → isResultConsumed = false")
        void launchingNotAcceptedIsNotConsumed() {
            anchor.setResumeState("LAUNCHING");
            assertThat(anchor.isResultConsumed()).isFalse();
        }

        @Test
        @DisplayName("ACCEPTED → isResultConsumed = true")
        void acceptedIsConsumed() {
            anchor.setResumeState("ACCEPTED");
            assertThat(anchor.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("CONSUMED → isResultConsumed = true")
        void consumedIsConsumed() {
            anchor.setResumeState("CONSUMED");
            assertThat(anchor.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("null resumeState → isResultConsumed = false")
        void nullResumeStateIsNotConsumed() {
            assertThat(anchor.isResultConsumed()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Legacy normalization via fromJson
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Legacy normalization: fromJson upgrades old dual-track data")
    class LegacyNormalization {

        @Test
        @DisplayName("old LAUNCHING + resultConsumed=true → normalized to ACCEPTED")
        void legacyLaunchingWithResultConsumedTrue() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("LAUNCHING");
            anchor.setResultConsumed(true);

            String json = anchor.toJson();
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            // Normalized: resumeState upgraded from LAUNCHING to ACCEPTED
            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("old null resumeState + resultConsumed=true → normalized to ACCEPTED")
        void legacyNullResumeStateWithResultConsumedTrue() {
            anchor.setOperationId("run-1:tc-1:1");
            // resumeState is null (very old data)
            anchor.setResultConsumed(true);

            String json = anchor.toJson();
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("contradictory READY + resultConsumed=true → fail-closed")
        void contradictoryReadyAndConsumedFailsClosed() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("READY");
            anchor.setResultConsumed(true);

            String json = anchor.toJson();

            assertThatThrownBy(() -> ToolJobAnchor.fromJson(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contradictory");
        }

        @Test
        @DisplayName("clean CONSUMED + resultConsumed=true → no change (already consistent)")
        void cleanConsumedWithResultConsumedTrue() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("CONSUMED");
            anchor.setResultConsumed(true);

            String json = anchor.toJson();
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            assertThat(restored.getResumeState()).isEqualTo("CONSUMED");
            assertThat(restored.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("clean ACCEPTED + resultConsumed=true → no change (already consistent)")
        void cleanAcceptedWithResultConsumedTrue() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("ACCEPTED");
            anchor.setResultConsumed(true);

            String json = anchor.toJson();
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("clean LAUNCHING without resultConsumed → stays LAUNCHING (not accepted)")
        void cleanLaunchingWithoutResultConsumed() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("LAUNCHING");
            // resultConsumed is false (default)

            String json = anchor.toJson();
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            // Not normalized: LAUNCHING + resultConsumed=false is the normal non-accepted state
            assertThat(restored.getResumeState()).isEqualTo("LAUNCHING");
            assertThat(restored.isResultConsumed()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Anchor identity fields preserved through roundtrip
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Anchor identity fields survive JSON roundtrip")
    class AnchorIdentityRoundtrip {

        @Test
        @DisplayName("full identity fields preserved through fromJson/toJson")
        void fullIdentityPreserved() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setTaskId("task-123");
            anchor.setToolCallId("tc-1");
            anchor.setAttempt(1);
            anchor.setResumeState("LAUNCHING");
            anchor.setResumeToken("token-v1");
            anchor.setResumeLeaseVersion(5L);
            anchor.setResumeLauncherOwnerId("owner-a");

            ToolJobAnchor restored = ToolJobAnchor.fromJson(anchor.toJson());

            assertThat(restored.getOperationId()).isEqualTo("run-1:tc-1:1");
            assertThat(restored.getTaskId()).isEqualTo("task-123");
            assertThat(restored.getResumeState()).isEqualTo("LAUNCHING");
            assertThat(restored.getResumeToken()).isEqualTo("token-v1");
            assertThat(restored.getResumeLeaseVersion()).isEqualTo(5L);
            assertThat(restored.getResumeLauncherOwnerId()).isEqualTo("owner-a");
            assertThat(restored.isResultConsumed()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Target migration seam (Step 3: activate after production changes)
    // ------------------------------------------------------------------

    @Disabled("D12 Step 3: activate after ACCEPTED state passes full service-level tests")
    @Nested
    @DisplayName("Target migration seam: ACCEPTED state in production services")
    class TargetMigrationSeam {

        @Test
        @DisplayName("[contract] ACCEPTED takeover keeps ACCEPTED state, not rolled back to LAUNCHING")
        void acceptedTakeoverKeepsAcceptedState() {
            // Contract: tryResume for expired ACCEPTED must keep resumeState=ACCEPTED
            // after takeover, so ToolJobResumeContext.resultConsumed derives to true.
            // Tested via ToolJobResumeServiceTest after ACCEPTED is wired.
        }

        @Test
        @DisplayName("[contract] LAUNCHING without handoff is not consumed")
        void launchingWithoutHandoffNotConsumed() {
            anchor.setResumeState("LAUNCHING");
            assertThat(anchor.isResultConsumed()).isFalse();
        }

        @Test
        @DisplayName("[contract] ACCEPTED is consumed (handoff was accepted)")
        void acceptedIsConsumedContract() {
            anchor.setResumeState("ACCEPTED");
            assertThat(anchor.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("[contract] markHandoffAccepted CAS advances LAUNCHING → ACCEPTED")
        void markHandoffAcceptedAdvancesToAccepted() {
            // Contract: markHandoffAccepted must set resumeState=ACCEPTED
            // in the same CAS, not just set resultConsumed=true.
        }
    }
}
