package world.willfrog.agentlangchain.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import world.willfrog.agentlangchain.config.LangchainServiceProperties;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.control.AgentLangchainOrchestrator;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.agentlangchain.control.dualpool.DualPoolDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRecoveryDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolSchedulerSettings;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;
import world.willfrog.agentlangchain.tooljob.WaitMemberResultReceiver;
import world.willfrog.agentlangchain.workspace.PythonWorkspaceRetentionCoordinator;
import world.willfrog.agent.platform.capacity.SchedulerBackpressureProbe;
import world.willfrog.agent.platform.service.AgentRunEventService;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static world.willfrog.agentlangchain.control.AgentLangchainOrchestrator.LINEAR_PIPELINE_READY;
import static world.willfrog.agentlangchain.control.AgentLangchainOrchestrator.LINEAR_PIPELINE_UNAVAILABLE;
import static world.willfrog.agentlangchain.control.AgentLangchainOrchestrator.PROVIDER_DISABLED;

@WebMvcTest(AgentLangchainHealthController.class)
class AgentLangchainHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LangchainRunConcurrencyScheduler concurrencyScheduler;

    @MockBean
    private LangchainToolConcurrencyThrottle toolThrottle;

    @MockBean
    private LangchainServiceProperties properties;

    @MockBean
    private AgentLangchainOrchestrator orchestrator;

    @MockBean
    private DualPoolDispatcher dualPoolDispatcher;

    @MockBean
    private SchedulerBackpressureProbe schedulerBackpressureProbe;

    // 下面这几个是 /scheduler 那棵树里的读数来源：控制器少一个都建不出来，
    // 所以这里的替身要与构造器的入参一一对上。
    @MockBean
    private DualPoolRecoveryDispatcher dualPoolRecoveryDispatcher;

    @MockBean
    private DualPoolSchedulerSettings dualPoolSchedulerSettings;

    @MockBean
    private FrozenEffectiveSettings frozenEffectiveSettings;

    @MockBean
    private WaitMemberResultReceiver waitMemberResultReceiver;

    // 控制器为 /sandbox-governance 回读端点新增了两个构造器入参
    // （工作区保留协调器、运行事件服务），装配替身需一并对上。
    @MockBean
    private PythonWorkspaceRetentionCoordinator retentionCoordinator;

    @MockBean
    private AgentRunEventService runEventService;





}
