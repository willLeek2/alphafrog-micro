package world.willfrog.agentlangchain.acceptance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回合耗尽的说明：节点通配没写 nodeId 时，要一眼看出是多个节点在争同一份声明。
 */
class FixtureCallStoreUsedUpExplainTest {

    @Test
    void aNodeWildcardNamesTheCurrentNodeWhenTurnsAreUsedUp() {
        FrozenModelScript.TurnDeclaration wildcard = new FrozenModelScript.TurnDeclaration(
                0, "node", Map.of("segmentSequence", "0", "modelTurn", "0"), false);
        FixtureCallIdentity secondNode = FixtureCallIdentity.nodeSegment("run-1", 0, "todo_2", 0, 0, 0);

        String detail = FixtureCallStore.explainDeclaredTurnsUsedUp(
                "fx-1", "three-tools", secondNode, List.of(wildcard));

        assertThat(detail).contains("todo_2");
        assertThat(detail).contains("没写 nodeId");
        assertThat(detail).contains("当前节点是 todo_2");
        assertThat(detail).contains("钉死节点数");
    }

    @Test
    void aPinnedNodeIdKeepsThePlainUsedUpWording() {
        FrozenModelScript.TurnDeclaration pinned = new FrozenModelScript.TurnDeclaration(
                0, "node", Map.of("nodeId", "todo_1"), false);
        FixtureCallIdentity other = FixtureCallIdentity.nodeSegment("run-1", 0, "todo_2", 0, 0, 0);

        String detail = FixtureCallStore.explainDeclaredTurnsUsedUp(
                "fx-1", "three-tools", other, List.of(pinned));

        assertThat(detail).doesNotContain("没写 nodeId");
        assertThat(detail).contains("都已经被别的调用领走了");
    }
}
