package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成员结果载荷的自检：正文原样保存并带摘要；超过上限时改写成失败说明，不拿半截数据冒充结果。
 */
class WaitMemberResultPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aSuccessfulResultKeepsTheOutputAndCarriesADigest() {
        String json = WaitMemberResultPayload.encode(objectMapper, "getStockDaily", "call-a", true,
                "日线数据", Map.of("taskId", "task-1"), 1024);

        assertThat(WaitMemberResultPayload.succeeded(json, objectMapper)).isTrue();
        assertThat(WaitMemberResultPayload.output(json, objectMapper)).isEqualTo("日线数据");
        assertThat(WaitMemberResultPayload.modelText(json, objectMapper)).isEqualTo("日线数据");
        assertThat(json).contains("\"taskId\":\"task-1\"");
        assertThat(json).contains("\"outputLength\":4");
        assertThat(json).as("同一份正文摘要是稳定的").contains("\"outputDigest\":\"sha256:");
    }

    @Test
    void anOversizedResultBecomesAnExplicitFailureInsteadOfTruncatedText() {
        String output = "x".repeat(2048);
        String json = WaitMemberResultPayload.encode(objectMapper, "executePython", "call-b", true,
                output, Map.of(), 1024);

        assertThat(WaitMemberResultPayload.succeeded(json, objectMapper)).isFalse();
        assertThat(WaitMemberResultPayload.output(json, objectMapper)).isEmpty();
        assertThat(json).contains(WaitMemberResultPayload.TOO_LARGE);
        assertThat(WaitMemberResultPayload.modelText(json, objectMapper))
                .as("模型看到的是一句明确的说明").contains(WaitMemberResultPayload.TOO_LARGE);
    }

    @Test
    void aFailedResultGivesTheModelSomethingItCanActOn() {
        String json = WaitMemberResultPayload.encode(objectMapper, "getStockDaily", "call-c", false,
                "参数缺少资产代码", Map.of(), 1024);

        assertThat(WaitMemberResultPayload.succeeded(json, objectMapper)).isFalse();
        assertThat(WaitMemberResultPayload.modelText(json, objectMapper)).isEqualTo("参数缺少资产代码");
    }
}
