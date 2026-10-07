package world.willfrog.agent.platform.wait;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 派发证明的取值规则：内容不全的证明不算证明，任务编号可以暂时为空。
 */
class WaitMemberDispatchProofTest {

    private static final String OPERATION = "run-1:executePython_2--wi-0000:1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aProofRoundTripsThroughJson() {
        WaitMemberDispatchProof proof = proof(OPERATION, "task-9");

        Optional<WaitMemberDispatchProof> read =
                WaitMemberDispatchProof.fromJson(objectMapper, proof.toJson(objectMapper));

        assertThat(read).contains(proof);
    }

    @Test
    void aProofWithoutATaskIdStillCountsAsAProof() {
        WaitMemberDispatchProof proof = proof(OPERATION, null);

        assertThat(proof.taskConfirmed()).isFalse();
        assertThat(WaitMemberDispatchProof.fromJson(objectMapper, proof.toJson(objectMapper)))
                .as("任务编号还没证实的证明照样能读回来，接收方按外部作业身份继续回查")
                .get()
                .satisfies(read -> {
                    assertThat(read.taskId()).isNull();
                    assertThat(read.taskConfirmed()).isFalse();
                    assertThat(read.operationId()).isEqualTo(OPERATION);
                });
    }

    @Test
    void ablankTaskIdIsNormalizedToAbsent() {
        assertThat(proof(OPERATION, "   ").taskId()).isNull();
        assertThat(proof(OPERATION, " task-9 ").taskId()).isEqualTo("task-9");
    }

    @Test
    void everyRequiredFactIsRequired() {
        assertThatThrownBy(() -> new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION, null, "task-9", "sha256:a", "{}", "{}", "{}",
                "2026-09-22T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION, OPERATION, "task-9", " ", "{}", "{}", "{}",
                "2026-09-22T00:00:00Z"))
                .as("没有请求指纹就无法核对终态结果属于哪一次调用")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION, OPERATION, "task-9", "sha256:a", "{}", "{}", "",
                "2026-09-22T00:00:00Z"))
                .as("没有名额凭证就无法把容量还回去")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION, OPERATION, "task-9", "sha256:a", "{}", "{}", "{}",
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownSchemaVersionIsRejected() {
        assertThatThrownBy(() -> new WaitMemberDispatchProof(
                WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION + 1, OPERATION, "task-9", "sha256:a", "{}", "{}",
                "{}", "2026-09-22T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replayableProofRequiresTheOriginalRequestWhileLegacyProofRemainsReadable() {
        WaitMemberDispatchProof legacy = proof(OPERATION, null);
        assertThat(WaitMemberDispatchProof.fromJson(objectMapper, legacy.toJson(objectMapper)))
                .get().extracting(WaitMemberDispatchProof::replayable).isEqualTo(false);

        WaitMemberDispatchProof current = new WaitMemberDispatchProof(
                WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, OPERATION, null,
                "sha256:tests", "{}", "{}", "{}", "2026-09-22T00:00:00Z",
                "{\"operationId\":\"" + OPERATION + "\"}");
        assertThat(WaitMemberDispatchProof.fromJson(objectMapper, current.toJson(objectMapper)))
                .get().extracting(WaitMemberDispatchProof::replayable).isEqualTo(true);
        assertThatThrownBy(() -> new WaitMemberDispatchProof(
                WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, OPERATION, null,
                "sha256:tests", "{}", "{}", "{}", "2026-09-22T00:00:00Z", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workspaceRefusalKeepsTheOriginalRequestAndSurvivesJsonRecovery() {
        WaitMemberDispatchProof original = new WaitMemberDispatchProof(
                WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, OPERATION, null,
                "sha256:tests", "{}", "{}", "{}", "2026-09-22T00:00:00Z",
                "{\"operationId\":\"" + OPERATION + "\"}");

        WaitMemberDispatchProof refused = original.withWorkspaceRefusal("WORKSPACE_DIRTY");
        WaitMemberDispatchProof recovered = WaitMemberDispatchProof.fromJson(
                objectMapper, refused.toJson(objectMapper)).orElseThrow();

        assertThat(recovered.workspaceRefused()).isTrue();
        assertThat(recovered.workspaceRefusalCode()).isEqualTo("WORKSPACE_DIRTY");
        assertThat(recovered.createRequestJson()).isEqualTo(original.createRequestJson());
        assertThatThrownBy(() -> original.withWorkspaceRefusal("WORKSPACE_RESULT_UNSPECIFIED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runIneligibleRefusalCanBePersistedAndRecovered() {
        WaitMemberDispatchProof original = new WaitMemberDispatchProof(
                WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, OPERATION, null,
                "sha256:tests", "{}", "{}", "{}", "2026-09-22T00:00:00Z",
                "{\"operationId\":\"" + OPERATION + "\"}");

        WaitMemberDispatchProof refused = original.withWorkspaceRefusal("WORKSPACE_RUN_INELIGIBLE");
        WaitMemberDispatchProof recovered = WaitMemberDispatchProof.fromJson(
                objectMapper, refused.toJson(objectMapper)).orElseThrow();

        assertThat(recovered.workspaceRefused()).isTrue();
        assertThat(recovered.workspaceRefusalCode()).isEqualTo("WORKSPACE_RUN_INELIGIBLE");
        assertThat(recovered.createRequestJson()).isEqualTo(original.createRequestJson());
    }

    @Test
    void settledProofRemainsReadableAfterOnlyReplayBodyExpires() throws Exception {
        WaitMemberDispatchProof original = new WaitMemberDispatchProof(
                WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, OPERATION, "task-9",
                "sha256:tests", "{\"codeHash\":\"sha256:code\"}", "{}", "{}",
                "2026-09-22T00:00:00Z", "{\"code\":\"private script\"}");
        ObjectNode compact = (ObjectNode) objectMapper.readTree(original.toJson(objectMapper));
        compact.remove("createRequestJson");
        compact.put("createRequestExpired", true);

        WaitMemberDispatchProof recovered = WaitMemberDispatchProof.fromJson(
                objectMapper, compact.toString()).orElseThrow();
        assertThat(recovered.replayable()).isFalse();
        assertThat(recovered.operationId()).isEqualTo(OPERATION);
        assertThat(recovered.taskId()).isEqualTo("task-9");
        assertThat(recovered.requestFingerprint()).isEqualTo("sha256:tests");
        assertThat(recovered.canonicalCreateSpecJson()).contains("codeHash");
        assertThat(recovered.createRequestJson()).isNull();

        compact.remove("createRequestExpired");
        assertThat(WaitMemberDispatchProof.fromJson(objectMapper, compact.toString()))
                .as("原始 schema2 证明缺完整请求仍是损坏数据")
                .isEmpty();
    }

    @Test
    void oldSchemaTwoProofWithoutExpiryKeyKeepsWorkspaceRefusalCasShape() throws Exception {
        WaitMemberDispatchProof initial = new WaitMemberDispatchProof(
                WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, OPERATION, null,
                "sha256:tests", "{}", "{}", "{}", "2026-09-22T00:00:00Z",
                "{\"operationId\":\"" + OPERATION + "\"}");
        ObjectNode stored = (ObjectNode) objectMapper.readTree(initial.toJson(objectMapper));
        stored.remove("createRequestExpired");

        WaitMemberDispatchProof recovered = WaitMemberDispatchProof.fromJson(
                objectMapper, stored.toString()).orElseThrow();
        ObjectNode refusal = (ObjectNode) objectMapper.readTree(
                recovered.withWorkspaceRefusal("WORKSPACE_DIRTY").toJson(objectMapper));
        assertThat(refusal.has("createRequestExpired")).isFalse();
        assertThat(refusal.remove("workspaceRefusalCode")).isNotNull();
        stored.remove("workspaceRefusalCode");
        assertThat(refusal).as("数据库只排除拒绝码后，两份证明仍逐字段一致")
                .isEqualTo(stored);
    }

    @Test
    void unreadableOrIncompleteJsonReadsAsAbsent() {
        assertThat(WaitMemberDispatchProof.fromJson(objectMapper, "{")).isEmpty();
        assertThat(WaitMemberDispatchProof.fromJson(objectMapper, "{}")).isEmpty();
        assertThat(WaitMemberDispatchProof.fromJson(objectMapper, null)).isEmpty();
        assertThat(WaitMemberDispatchProof.fromJson(null, "{}")).isEmpty();
    }

    private static WaitMemberDispatchProof proof(String operationId, String taskId) {
        return new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION,
                operationId,
                taskId,
                "sha256:tests",
                "{\"schemaVersion\":\"sandbox_create_v1\"}",
                "{\"estimatedRows\":1}",
                "{\"reservationId\":\"" + operationId + "\"}",
                "2026-09-22T00:00:00Z");
    }
}
