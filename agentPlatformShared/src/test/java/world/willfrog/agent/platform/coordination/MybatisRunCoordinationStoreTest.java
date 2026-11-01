package world.willfrog.agent.platform.coordination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.mapper.RunCoordinationMapper;
import world.willfrog.agent.platform.workitem.ServiceOwnershipFence;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MybatisRunCoordinationStore} 的入参自检、影响行数解读，以及「栅栏值真的传下去了」。
 *
 * <p>这里只能证明 Java 侧把哪些值交给了语句。条件更新在并发下到底影响几行，靠真库场景验，
 * 不靠这些桩。</p>
 */
@ExtendWith(MockitoExtension.class)
class MybatisRunCoordinationStoreTest {

    /** Run 级写入要带的服务所有权凭据：这里只证明它被原样交给语句。 */
    private static final ServiceOwnershipFence FENCE = new ServiceOwnershipFence("instance-a", 7L);

    @Mock
    private RunCoordinationMapper mapper;

    private MybatisRunCoordinationStore store;

    @BeforeEach
    void setUp() {
        store = new MybatisRunCoordinationStore(mapper);
    }













    private static RunCoordination row(String runId, String schedulerVersion) {
        RunCoordination row = new RunCoordination();
        row.setRunId(runId);
        row.setSchedulerVersion(schedulerVersion);
        return row;
    }
}
