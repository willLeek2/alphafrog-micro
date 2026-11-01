package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 脚本读得懂、每个回合都声明了它回答哪一次调用、认不出的写法一律拒绝。
 */
class FrozenModelScriptTest {

    private final ObjectMapper objectMapper = new ObjectMapper();





















    private FrozenModelScript parse(String json) {
        return FrozenModelScript.parse("fx-1", json, objectMapper);
    }

    private static String code(Throwable error) {
        return error instanceof AcceptanceFixtureExecutionException fixture ? fixture.code() : null;
    }



}
