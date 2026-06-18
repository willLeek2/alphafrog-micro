package world.willfrog.agent.service.workspace;

import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.service.AgentArtifactService.PythonScript;

import java.util.List;

/**
 * workspace dump 阶段 collector 汇总出的资产集合。
 *
 * <p>三类：
 * <ul>
 *   <li>messages — conversation 历史</li>
 *   <li>pythonScripts — executePython 事件聚合后的 python script</li>
 *   <li>datasetIds — python script 中出现 + fallback 抓到的 dataset id 汇总</li>
 * </ul>
 *
 * @author wang
 */
public record CollectedAssets(
        List<AgentRunMessage> messages,
        List<PythonScript> pythonScripts,
        List<String> datasetIds
) {
}
