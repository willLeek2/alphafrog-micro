package world.willfrog.agentlangchain.acceptance;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 调用身份：写法稳定、字段认得出来，声明的匹配按「写了的字段都要对上」算。
 */
class FixtureCallIdentityTest {

    @Test
    void theIdentityTextIsSortedByFieldNameSoTheSameCallAlwaysLooksTheSame() {
        FixtureCallIdentity one = new FixtureCallIdentity("run-1", FixtureCallIdentity.Stage.NODE,
                ordered("nodeId", "n1", "planGeneration", "0", "modelTurn", "2"));
        FixtureCallIdentity other = new FixtureCallIdentity("run-1", FixtureCallIdentity.Stage.NODE,
                ordered("modelTurn", "2", "nodeId", "n1", "planGeneration", "0"));

        assertThat(one.describe()).isEqualTo(other.describe());
        assertThat(one.describe()).isEqualTo("stage=node;modelTurn=2;nodeId=n1;planGeneration=0");
    }

    @Test
    void everyStageCarriesTheFieldsItsCallersCanSupply() {
        assertThat(FixtureCallIdentity.planning("run-1", 2, 1, "todos").describe())
                .isEqualTo("stage=planning;planAttempt=1;planGeneration=2;planPhase=todos");
        assertThat(FixtureCallIdentity.nodeSegment("run-1", 0, "n1", 0, 3, 1).describe())
                .isEqualTo("stage=node;modelTurn=1;nodeAttempt=0;nodeId=n1;planGeneration=0;segmentSequence=3");
        assertThat(FixtureCallIdentity.answer("run-1", 0).describe()).isEqualTo("stage=answer;planGeneration=0");
        assertThat(FixtureCallIdentity.judge("run-1", 1, 2).describe())
                .isEqualTo("stage=judge;decisionIndex=2;planGeneration=1");
    }

    @Test
    void aDeclarationMatchesWhenEveryFieldItWritesMatches() {
        FixtureCallIdentity identity = FixtureCallIdentity.nodeSegment("run-1", 4, "n7", 0, 1, 0);

        assertThat(identity.matches("node", Map.of())).isTrue();
        assertThat(identity.matches("node", Map.of("nodeId", "n7"))).isTrue();
        assertThat(identity.matches("node", Map.of("nodeId", "n7", "segmentSequence", "1"))).isTrue();
        assertThat(identity.matches("node", Map.of("nodeId", "n8"))).isFalse();
        assertThat(identity.matches("node", Map.of("planGeneration", "3"))).isFalse();
        assertThat(identity.matches("answer", Map.of())).isFalse();
        assertThat(identity.matches(null, Map.of())).isFalse();
    }

    @Test
    void anUnknownFieldInAnIdentityIsRejected() {
        assertThatThrownBy(() -> new FixtureCallIdentity("run-1", FixtureCallIdentity.Stage.NODE,
                Map.of("todoId", "t1")))
                .hasMessageContaining("认不出的域字段");
    }

    @Test
    void anEmptyFieldValueInAnIdentityIsRejected() {
        assertThatThrownBy(() -> new FixtureCallIdentity("run-1", FixtureCallIdentity.Stage.NODE,
                Map.of("nodeId", " ")))
                .hasMessageContaining("没有值");
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> scope = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            scope.put(pairs[index], pairs[index + 1]);
        }
        return scope;
    }
}
