package world.willfrog.agentlangchain.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.mapper.AgentRunMessageMapper;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.agent.platform.service.AgentArtifactService.ParsedEventsView;
import world.willfrog.agent.platform.service.AgentArtifactService.PythonScript;

import java.util.ArrayList;
import java.util.List;

/**
 * workspace dump 资产收集器。
 *
 * <p>从 conversation（AgentRunMessageMapper）、event（AgentArtifactService.collectParsedEvents）、
 * dataset registry 收集三类资产：atomic dataset、dataset_manifest、python_script。</p>
 *
 * <h3>设计点</h3>
 * <ul>
 *   <li>不走 AgentArtifactService.listArtifacts() —— 避免 snapshotPythonScript 写临时文件的副作用</li>
 *   <li>走 collectParsedEvents 公开入口，避免反射或改 parseEvents 可见性</li>
 *   <li>dataset 列表先在 collector 这层 deduplicate（按 python script 中出现 + fallback 列表）</li>
 * </ul>
 *
 * @author wang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceAssetCollector {

    private final AgentArtifactService artifactService;
    private final AgentRunMessageMapper messageMapper;

    /**
     * 收集指定 run 的所有 workspace 资产。
     *
     * @param run 目标 run
     * @return 收集到的资产
     */
    public CollectedAssets collectWorkspaceAssets(AgentRun run) {
        if (run == null || run.getId() == null || run.getId().isBlank()) {
            throw new IllegalArgumentException("run / runId 不能为空");
        }

        // 1) messages（conversation 来源）
        List<AgentRunMessage> messages = messageMapper.listByRunId(run.getId());

        // 2) parsed events（python scripts + dataset ids）
        ParsedEventsView parsed = artifactService.collectParsedEvents(run);
        List<PythonScript> pythonScripts = parsed.pythonScripts() == null ? List.of() : parsed.pythonScripts();

        // 3) 汇总 dataset ids（python scripts 中出现 + fallback 列表）
        List<String> datasetIds = new ArrayList<>();
        for (PythonScript script : pythonScripts) {
            if (script.datasetIds() != null) {
                datasetIds.addAll(script.datasetIds());
            }
        }
        if (parsed.fallbackDatasetIds() != null) {
            datasetIds.addAll(parsed.fallbackDatasetIds());
        }

        return new CollectedAssets(messages, pythonScripts, datasetIds);
    }
}
