package world.willfrog.agentlangchain.tooljob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
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
            // Manually craft legacy JSON: old code wrote LAUNCHING with independent
            // resultConsumed=true. The new serialization derives resultConsumed from
            // state, so we must construct the legacy shape directly.
            String json = "{\"schemaVersion\":1,\"operationId\":\"run-1:tc-1:1\","
                    + "\"resumeState\":\"LAUNCHING\",\"resultConsumed\":true}";
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            // Normalized: resumeState upgraded from LAUNCHING to ACCEPTED
            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("old null resumeState + resultConsumed=true → normalized to ACCEPTED")
        void legacyNullResumeStateWithResultConsumedTrue() {
            // Very old data: resultConsumed=true but no resumeState field at all
            String json = "{\"schemaVersion\":1,\"operationId\":\"run-1:tc-1:1\","
                    + "\"resultConsumed\":true}";
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("contradictory READY + resultConsumed=true → fail-closed")
        void contradictoryReadyAndConsumedFailsClosed() {
            // Contradictory: READY cannot have a consumed result
            String json = "{\"schemaVersion\":1,\"operationId\":\"run-1:tc-1:1\","
                    + "\"resumeState\":\"READY\",\"resultConsumed\":true}";

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
    // Target migration seam: ACCEPTED state characterization
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Target migration seam: ACCEPTED state survives roundtrips")
    class TargetMigrationSeam {

        @Test
        @DisplayName("ACCEPTED anchor survives JSON roundtrip with resultConsumed=true")
        void acceptedAnchorSurvivesRoundtrip() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("ACCEPTED");
            anchor.setResumeToken("token-v1");
            anchor.setResumeLeaseVersion(5L);

            ToolJobAnchor restored = ToolJobAnchor.fromJson(anchor.toJson());

            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("JSON output of ACCEPTED has resultConsumed=true derived from state")
        void acceptedJsonHasResultConsumedTrue() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("ACCEPTED");

            String json = anchor.toJson();

            assertThat(json).contains("\"resultConsumed\":true");
            assertThat(json).contains("\"resumeState\":\"ACCEPTED\"");
        }

        @Test
        @DisplayName("JSON output of READY has resultConsumed=false derived from state")
        void readyJsonHasResultConsumedFalse() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("READY");

            String json = anchor.toJson();

            assertThat(json).contains("\"resultConsumed\":false");
        }

        @Test
        @DisplayName("markHandoffAccepted transition: LAUNCHING → ACCEPTED preserves identity fields")
        void acceptedTransitionPreservesIdentity() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("LAUNCHING");
            anchor.setResumeToken("token-v1");
            anchor.setResumeLeaseVersion(5L);
            anchor.setResumeLauncherOwnerId("owner-a");
            anchor.setResultConsumed(true);

            // Simulate markHandoffAccepted: advance to ACCEPTED
            anchor.setResumeState("ACCEPTED");

            ToolJobAnchor restored = ToolJobAnchor.fromJson(anchor.toJson());
            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.getResumeToken()).isEqualTo("token-v1");
            assertThat(restored.getResumeLeaseVersion()).isEqualTo(5L);
            assertThat(restored.isResultConsumed()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // Production XML seam: ACCEPTED in AgentRunMapper.xml
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Production XML seam: AgentRunMapper.xml ACCEPTED predicates")
    class ProductionXmlSeam {

        private String xml;

        @BeforeEach
        void loadMapperXml() throws Exception {
            java.io.InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("mapper/AgentRunMapper.xml");
            assertThat(is).as("AgentRunMapper.xml must be on classpath").isNotNull();
            xml = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("5 SQL locations accept ACCEPTED state")
        void acceptedInFivePredicates() {
            // Count IN ('LAUNCHING', 'ACCEPTED') occurrences in SQL predicates.
            // Exactly 5: takeoverExpiredResumeLauncher, heartbeatResumeLauncher,
            // updateResumedTerminal, clearAcceptedResumeHandoff, listResumeReadyAnchors.
            int count = countOccurrences(xml, "resumeState}' IN ('LAUNCHING', 'ACCEPTED')");
            assertThat(count).as("5 SQL predicates must accept ACCEPTED").isEqualTo(5);
        }

        @Test
        @DisplayName("acceptResumeHandoff stays LAUNCHING-only")
        void acceptHandoffStaysLaunchingOnly() {
            // acceptResumeHandoff transitions LAUNCHING→ACCEPTED; it must NOT
            // already accept ACCEPTED as a precondition.
            String acceptBlock = extractBetween(xml,
                    "<update id=\"acceptResumeHandoff\">", "</update>");
            assertThat(acceptBlock)
                    .as("acceptResumeHandoff must check LAUNCHING only")
                    .contains("resumeState}' = 'LAUNCHING'")
                    .doesNotContain("IN ('LAUNCHING', 'ACCEPTED')");
        }

        @Test
        @DisplayName("claimResumeLauncher stays READY-only")
        void claimResumeLauncherStaysReadyOnly() {
            String claimBlock = extractBetween(xml,
                    "<update id=\"claimResumeLauncher\">", "</update>");
            assertThat(claimBlock)
                    .as("claimResumeLauncher must check READY only")
                    .contains("resumeState}' = 'READY'");
        }

        // ---- helpers ----

        private static int countOccurrences(String haystack, String needle) {
            int count = 0;
            int idx = 0;
            while ((idx = haystack.indexOf(needle, idx)) != -1) {
                count++;
                idx += needle.length();
            }
            return count;
        }

        private static String extractBetween(String source, String start, String end) {
            int s = source.indexOf(start);
            if (s == -1) return "";
            int e = source.indexOf(end, s + start.length());
            if (e == -1) return "";
            return source.substring(s, e + end.length());
        }
    }
}
