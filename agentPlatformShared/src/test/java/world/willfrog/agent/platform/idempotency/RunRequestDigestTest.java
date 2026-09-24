package world.willfrog.agent.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 创建请求摘要：同样内容算出来必须一样，内容变一点点必须不一样，JSON 键序与空白不算内容变化。
 */
class RunRequestDigestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sameContentGivesTheSameDigest() {
        assertThat(RunRequestDigest.digest(fingerprint("hello", "{}"), objectMapper))
                .isEqualTo(RunRequestDigest.digest(fingerprint("hello", "{}"), objectMapper))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void jsonFieldOrderAndWhitespaceDoNotChangeTheDigest() {
        String first = RunRequestDigest.digest(
                fingerprint("hello", "{\"modelName\":\"m\",\"debug\":false}"), objectMapper);
        String second = RunRequestDigest.digest(
                fingerprint("hello", "{ \"debug\" : false , \"modelName\" : \"m\" }"), objectMapper);
        assertThat(first)
                .as("同一份上下文换个键序或空格是同一份请求，不该被当成另一份内容拒绝掉")
                .isEqualTo(second);
    }

    @Test
    void nonJsonContextFallsBackToRawComparison() {
        String first = RunRequestDigest.digest(fingerprint("hello", "not-json"), objectMapper);
        String second = RunRequestDigest.digest(fingerprint("hello", "not-json "), objectMapper);
        assertThat(first).isNotEqualTo(second);
        assertThat(first).isEqualTo(RunRequestDigest.digest(fingerprint("hello", "not-json"), objectMapper));
    }

    @Test
    void contentChangesChangeTheDigest() {
        String base = RunRequestDigest.digest(fingerprint("hello", "{}"), objectMapper);
        assertThat(RunRequestDigest.digest(fingerprint("hello!", "{}"), objectMapper)).isNotEqualTo(base);
        assertThat(RunRequestDigest.digest(fingerprint("hello", "{\"a\":1}"), objectMapper)).isNotEqualTo(base);
        RunRequestFingerprint other = new RunRequestFingerprint(
                "u-1", "hello", "{}", "other-model", "e", "p", false, 3, false, true, "{}");
        assertThat(RunRequestDigest.digest(other, objectMapper)).isNotEqualTo(base);
    }

    /**
     * 三个客户端可控、会改变产出的字段必须参与摘要：同一个幂等键换掉其中任何一个都不算同一次请求，
     * 否则重复提交会被当成同一次读回旧 Run，用户看到的是另一次运行的产出。
     */
    @Test
    void clientFieldsThatChangeTheOutcomeChangeTheDigest() {
        String base = RunRequestDigest.digest(fingerprint("hello", "{}"), objectMapper);
        assertThat(RunRequestDigest.digest(
                new RunRequestFingerprint("u-1", "hello", "{}", "m", "e", "p", false, 5, false, true, "{}"),
                objectMapper))
                .as("候选计划数量变了，算出来的东西也不一样")
                .isNotEqualTo(base);
        assertThat(RunRequestDigest.digest(
                new RunRequestFingerprint("u-1", "hello", "{}", "m", "e", "p", false, 3, true, true, "{}"),
                objectMapper))
                .as("调试模式变了")
                .isNotEqualTo(base);
        assertThat(RunRequestDigest.digest(
                new RunRequestFingerprint("u-1", "hello", "{}", "m", "e", "p", false, 3, false, false, "{}"),
                objectMapper))
                .as("是否生成产物变了")
                .isNotEqualTo(base);
    }

    @Test
    void missingArgumentsFailClosed() {
        assertThatThrownBy(() -> RunRequestDigest.digest(null, objectMapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunRequestDigest.digest(fingerprint("hello", "{}"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RunRequestFingerprint fingerprint(String message, String contextJson) {
        return new RunRequestFingerprint("u-1", message, contextJson, "m", "e", "p", false, 3, false, true, "{}");
    }
}
