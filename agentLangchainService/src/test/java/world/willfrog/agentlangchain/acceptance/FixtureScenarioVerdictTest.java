package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终态核对：脚本里声明为必答的回合没有被领走、或者放行策略点名的规则一条都没打中时，
 * 这一次验收不能算通过。
 */
class FixtureScenarioVerdictTest {

    private final ObjectMapper objectMapper = new ObjectMapper();












    /** 一条规则的落库记录：默认已经打中并生效，指定结果时按给的写。 */
    private static FixtureRuleHitStore.RuleHit hit(int ruleIndex, String outcome) {
        OffsetDateTime now = OffsetDateTime.now();
        return new FixtureRuleHitStore.RuleHit(ruleIndex, "holdUntilPoint", "memberSeq=0;nodeId=n1",
                1L, new AcceptanceReleasePolicy.MemberFacts(3, "n1", 0, 0, 0, 0, "call-a"),
                now, outcome == null ? null : now, outcome, null);
    }

    private FrozenModelScript parse(String json) {
        return FrozenModelScript.parse("fx-1", json, objectMapper);
    }
}
