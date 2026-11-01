package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentModelCatalogService;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.AgentRunCostService;
import world.willfrog.agent.platform.service.AgentRunCreditQueryService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.service.SnapshotPartService;
import world.willfrog.agentlangchain.control.dualpool.TerminalResidueCloser;
import world.willfrog.agentlangchain.tools.LangchainToolCatalogService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityContractFixtures;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityQuery;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityReadMode;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySnapshot;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentDiagnosticReadCapabilitiesRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LangchainRunReadServiceTest {

    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentRunEventService eventService = mock(AgentRunEventService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final AgentRunObservabilityService observabilityService = mock(AgentRunObservabilityService.class);
    private final AgentCreditService creditService = mock(AgentCreditService.class);
    private final AgentRunCostService runCostService = mock(AgentRunCostService.class);
    private final AgentRunCreditQueryService runCreditQueryService = mock(AgentRunCreditQueryService.class);
    private final AgentRunCreditSettlementService creditSettlementService = mock(AgentRunCreditSettlementService.class);
    private final AgentModelCatalogService modelCatalogService = mock(AgentModelCatalogService.class);
    private final AgentMessageService messageService = mock(AgentMessageService.class);
    private final SnapshotPartService snapshotPartService = mock(SnapshotPartService.class);
    private final LangchainToolCatalogService toolCatalogService = mock(LangchainToolCatalogService.class);
    private final AgentArtifactService artifactService = mock(AgentArtifactService.class);
    private final DataAnalysisObservabilityQuery dataAnalysisQuery = mock(DataAnalysisObservabilityQuery.class);
    private final DataAnalysisReadResponseSerializer dataAnalysisSerializer = new DataAnalysisReadResponseSerializer(new ObjectMapper());
    private final AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);

    private final LangchainRunReadService service = new LangchainRunReadService(
            runMapper,
            eventService,
            stateStore,
            observabilityService,
            creditService,
            runCostService,
            runCreditQueryService,
            creditSettlementService,
            modelCatalogService,
            messageService,
            snapshotPartService,
            toolCatalogService,
            artifactService,
            new ObjectMapper(),
            dataAnalysisQuery,
            dataAnalysisSerializer,
            finalizationService);

    @BeforeEach
    void stubDataAnalysisQuery() {
        when(dataAnalysisQuery.findByRunId(anyString(), any())).thenReturn(Optional.empty());
    }

























    private AgentRun run(String ext) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setExt(ext);
        run.setStartedAt(OffsetDateTime.now());
        return run;
    }

    private AgentRunEvent event(int seq, String type) {
        AgentRunEvent event = new AgentRunEvent();
        event.setId((long) seq);
        event.setRunId("r1");
        event.setSeq(seq);
        event.setEventType(type);
        event.setPayloadJson("{}");
        event.setCreatedAt(OffsetDateTime.now());
        return event;
    }


}
