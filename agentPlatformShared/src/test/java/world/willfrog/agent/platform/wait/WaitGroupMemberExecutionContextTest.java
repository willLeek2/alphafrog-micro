package world.willfrog.agent.platform.wait;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 成员派发上下文的装上与恢复：工具层只该看到自己那一次派发的事实，用完必须恢复原样。
 */
class WaitGroupMemberExecutionContextTest {

    @Test
    void theContextIsVisibleOnlyUntilTheScopeCloses() {
        assertThat(WaitGroupMemberExecutionContext.current()).isNull();

        WaitGroupMemberExecutionContext.Snapshot snapshot = snapshot(1);
        try (WaitGroupMemberExecutionContext.Scope ignored =
                     WaitGroupMemberExecutionContext.install(snapshot)) {
            assertThat(WaitGroupMemberExecutionContext.current()).isSameAs(snapshot);
        }

        assertThat(WaitGroupMemberExecutionContext.current())
                .as("离开作用域之后不能再把上一次派发的事实留给下一次调用")
                .isNull();
    }

    @Test
    void nestedScopesRestoreTheOuterOne() {
        WaitGroupMemberExecutionContext.Snapshot outer = snapshot(1);
        WaitGroupMemberExecutionContext.Snapshot inner = snapshot(2);

        try (WaitGroupMemberExecutionContext.Scope ignored =
                     WaitGroupMemberExecutionContext.install(outer)) {
            try (WaitGroupMemberExecutionContext.Scope nested =
                         WaitGroupMemberExecutionContext.install(inner)) {
                assertThat(WaitGroupMemberExecutionContext.current()).isSameAs(inner);
            }
            assertThat(WaitGroupMemberExecutionContext.current()).isSameAs(outer);
        }

        assertThat(WaitGroupMemberExecutionContext.current()).isNull();
    }

    @Test
    void aContextWithoutItsIdentityIsRejected() {
        assertThatThrownBy(() -> new WaitGroupMemberExecutionContext.Snapshot(
                "run-1", 7L, "member-1", 0, "durable-call", null, "seg"))
                .as("没有外部作业身份的成员上下文不能装上")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitGroupMemberExecutionContext.Snapshot(
                "run-1", 0L, "member-1", 0, "durable-call", "op-1", "seg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDescriptionCarriesTheFactsNeededToFindTheMemberInLogs() {
        String describe = snapshot(1).describe();

        assertThat(describe).contains("operation=run-1:call-a:1", "member=member-1", "seq=1");
    }

    private static WaitGroupMemberExecutionContext.Snapshot snapshot(int memberSeq) {
        return new WaitGroupMemberExecutionContext.Snapshot(
                "run-1", 7L, "member-1", memberSeq, "call-a--wi-0000", "run-1:call-a:1", "segment=run-1/3/todo_1/1/0");
    }
}
