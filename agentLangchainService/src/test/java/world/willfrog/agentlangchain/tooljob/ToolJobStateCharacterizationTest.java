package world.willfrog.agentlangchain.tooljob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

/**
 * D12 四态 resumeState 模型的特征测试（READY → LAUNCHING → ACCEPTED → CONSUMED）。
 *
 * 使用真实生产接缝：ToolJobAnchor.fromJson 归一化、isResultConsumed 从 resumeState 推导。
 */
@DisplayName("ToolJob State Characterization (D12 Step 3)")
class ToolJobStateCharacterizationTest {

    private ToolJobAnchor anchor;

    @BeforeEach
    void setUp() {
        anchor = new ToolJobAnchor();
    }

    // ------------------------------------------------------------------
    // isResultConsumed 从四态 resumeState 推导
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
    // 旧数据归一化 via fromJson
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Legacy normalization: fromJson upgrades old dual-track data")
    class LegacyNormalization {

        @Test
        @DisplayName("old LAUNCHING + resultConsumed=true → normalized to ACCEPTED")
        void legacyLaunchingWithResultConsumedTrue() {
            // 手动构造旧格式 JSON：旧代码写入 LAUNCHING 时独立设置
            // resultConsumed=true。新序列化从 resumeState 推导 resultConsumed，
            // 因此必须直接构造旧格式。
            String json = "{\"schemaVersion\":1,\"operationId\":\"run-1:tc-1:1\","
                    + "\"resumeState\":\"LAUNCHING\",\"resultConsumed\":true}";
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            // 归一化：resumeState 从 LAUNCHING 升级为 ACCEPTED
            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("old null resumeState + resultConsumed=true → normalized to ACCEPTED")
        void legacyNullResumeStateWithResultConsumedTrue() {
            // 极旧数据：有 resultConsumed=true 但无 resumeState 字段
            String json = "{\"schemaVersion\":1,\"operationId\":\"run-1:tc-1:1\","
                    + "\"resultConsumed\":true}";
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.isResultConsumed()).isTrue();
        }

        @Test
        @DisplayName("contradictory READY + resultConsumed=true → fail-closed")
        void contradictoryReadyAndConsumedFailsClosed() {
            // 矛盾状态：READY 不能有已消费的结果
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
            // resultConsumed 为 false（默认值）

            String json = anchor.toJson();
            ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

            // 不归一化：LAUNCHING + resultConsumed=false 是正常的未接受状态
            assertThat(restored.getResumeState()).isEqualTo("LAUNCHING");
            assertThat(restored.isResultConsumed()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Anchor 身份字段在 JSON 往返后保持不变
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
    @DisplayName("目标迁移接缝：ACCEPTED 状态在 JSON 往返后存活")
    class TargetMigrationSeam {

        @Test
        @DisplayName("ACCEPTED anchor JSON 往返后保持 resultConsumed=true")
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
        @DisplayName("ACCEPTED 的 JSON 输出中 resultConsumed=true 从状态推导")
        void acceptedJsonHasResultConsumedTrue() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("ACCEPTED");

            String json = anchor.toJson();

            assertThat(json).contains("\"resultConsumed\":true");
            assertThat(json).contains("\"resumeState\":\"ACCEPTED\"");
        }

        @Test
        @DisplayName("READY 的 JSON 输出中 resultConsumed=false 从状态推导")
        void readyJsonHasResultConsumedFalse() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("READY");

            String json = anchor.toJson();

            assertThat(json).contains("\"resultConsumed\":false");
        }

        @Test
        @DisplayName("markHandoffAccepted 转换：LAUNCHING→ACCEPTED 保持身份字段")
        void acceptedTransitionPreservesIdentity() {
            anchor.setOperationId("run-1:tc-1:1");
            anchor.setResumeState("LAUNCHING");
            anchor.setResumeToken("token-v1");
            anchor.setResumeLeaseVersion(5L);
            anchor.setResumeLauncherOwnerId("owner-a");
            anchor.setResultConsumed(true);

            // 模拟 markHandoffAccepted：推进到 ACCEPTED
            anchor.setResumeState("ACCEPTED");

            ToolJobAnchor restored = ToolJobAnchor.fromJson(anchor.toJson());
            assertThat(restored.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(restored.getResumeToken()).isEqualTo("token-v1");
            assertThat(restored.getResumeLeaseVersion()).isEqualTo(5L);
            assertThat(restored.isResultConsumed()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 生产 XML 接缝：AgentRunMapper.xml 中的 ACCEPTED
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("生产 XML 接缝：AgentRunMapper.xml 中的 ACCEPTED 谓词")
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
        @DisplayName("5 处 SQL 接受 ACCEPTED 状态")
        void acceptedInFivePredicates() {
            // Count IN ('LAUNCHING', 'ACCEPTED') occurrences in SQL predicates.
            // 恰好 5 处：takeoverExpiredResumeLauncher、heartbeatResumeLauncher、
            // updateResumedTerminal、clearAcceptedResumeHandoff、listResumeReadyAnchors。
            int count = countOccurrences(xml, "resumeState}' IN ('LAUNCHING', 'ACCEPTED')");
            assertThat(count).as("5 SQL predicates must accept ACCEPTED").isEqualTo(5);
        }

        @Test
        @DisplayName("acceptResumeHandoff 保持仅 LAUNCHING")
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
        @DisplayName("claimResumeLauncher 保持仅 READY")
        void claimResumeLauncherStaysReadyOnly() {
            String claimBlock = extractBetween(xml,
                    "<update id=\"claimResumeLauncher\">", "</update>");
            assertThat(claimBlock)
                    .as("claimResumeLauncher must check READY only")
                    .contains("resumeState}' = 'READY'");
        }

        // ---- 辅助方法 ----

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
