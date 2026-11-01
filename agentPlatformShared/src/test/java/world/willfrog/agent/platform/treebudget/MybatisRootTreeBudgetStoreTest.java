package world.willfrog.agent.platform.treebudget;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.RootTreeBudgetMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MybatisRootTreeBudgetStoreTest {
    private final RootTreeBudgetMapper mapper = mock(RootTreeBudgetMapper.class);
    private final MybatisRootTreeBudgetStore store = new MybatisRootTreeBudgetStore(mapper);



    private static RootTreeBudgetOperationRow row(String id, String root, String kind, String state) {
        RootTreeBudgetOperationRow row = new RootTreeBudgetOperationRow();
        row.setOperationId(id);
        row.setRootRunId(root);
        row.setKind(kind);
        row.setState(state);
        return row;
    }
}
