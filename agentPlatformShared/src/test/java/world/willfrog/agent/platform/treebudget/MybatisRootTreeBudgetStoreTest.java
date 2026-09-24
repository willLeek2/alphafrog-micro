package world.willfrog.agent.platform.treebudget;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.RootTreeBudgetMapper;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MybatisRootTreeBudgetStoreTest {
    private final RootTreeBudgetMapper mapper = mock(RootTreeBudgetMapper.class);
    private final MybatisRootTreeBudgetStore store = new MybatisRootTreeBudgetStore(mapper);

    @Test
    void replayCannotChangeRootOrKindAndNeverIncrementsAgain() {
        when(mapper.lockRoot("root-a")).thenReturn(new RootTreeBudgetSnapshotRow());
        RootTreeBudgetOperationRow existing = row("op-a", "root-a", "LLM_CALL", "CONFIRMED");
        when(mapper.operation("op-a")).thenReturn(existing);

        assertThat(store.reserve("root-a", "op-a", RootTreeBudgetStore.Kind.LLM_CALL, 1))
                .isEqualTo(RootTreeBudgetStore.State.CONFIRMED);
        assertThatThrownBy(() -> store.reserve("root-a", "op-a", RootTreeBudgetStore.Kind.TOOL_CALL, 1))
                .isInstanceOf(IllegalStateException.class);
        when(mapper.lockRoot("root-b")).thenReturn(new RootTreeBudgetSnapshotRow());
        assertThatThrownBy(() -> store.reserve("root-b", "op-a", RootTreeBudgetStore.Kind.LLM_CALL, 1))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).tryIncrement(anyString(), anyString(), anyLong());
    }

    @Test
    void invalidLimitAndIdentityFailBeforeDatabaseMutation() {
        assertThatThrownBy(() -> store.reserve("root-a", "op-a", RootTreeBudgetStore.Kind.LLM_CALL, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.reserve("root-a", " ", RootTreeBudgetStore.Kind.LLM_CALL, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.reserve("root-a", "op-a", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void confirmedCallCannotBeRefundedButConfirmedWaitCanBeReleased() {
        when(mapper.lockRoot("root-a")).thenReturn(new RootTreeBudgetSnapshotRow());
        when(mapper.operation("llm-a")).thenReturn(row("llm-a", "root-a", "LLM_CALL", "CONFIRMED"));
        assertThatThrownBy(() -> store.release("llm-a")).isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).decrement(anyString(), anyString());

        when(mapper.operation("wait-a")).thenReturn(row("wait-a", "root-a", "EXTERNAL_WAIT", "CONFIRMED"));
        when(mapper.decrement("root-a", "EXTERNAL_WAIT")).thenReturn(1);
        when(mapper.updateState("wait-a", "CONFIRMED", "RELEASED")).thenReturn(1);
        assertThat(store.release("wait-a")).isEqualTo(RootTreeBudgetStore.State.RELEASED);
    }

    @Test
    void productionMapperXmlParses() throws Exception {
        Configuration config = new Configuration();
        String resource = "mapper/RootTreeBudgetMapper.xml";
        try (InputStream xml = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
        }
        assertThat(config.hasStatement(RootTreeBudgetMapper.class.getName() + ".tryIncrement")).isTrue();
    }

    private static RootTreeBudgetOperationRow row(String id, String root, String kind, String state) {
        RootTreeBudgetOperationRow row = new RootTreeBudgetOperationRow();
        row.setOperationId(id);
        row.setRootRunId(root);
        row.setKind(kind);
        row.setState(state);
        return row;
    }
}
