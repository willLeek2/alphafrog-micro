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
                "u-1", "hello", "{}", "other-model", "e", "p", false, "{}");
        assertThat(RunRequestDigest.digest(other, objectMapper)).isNotEqualTo(base);
    }

    @Test
    void missingArgumentsFailClosed() {
        assertThatThrownBy(() -> RunRequestDigest.digest(null, objectMapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunRequestDigest.digest(fingerprint("hello", "{}"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RunRequestFingerprint fingerprint(String message, String contextJson) {
        return new RunRequestFingerprint("u-1", message, contextJson, "m", "e", "p", false, "{}");
    }
}
