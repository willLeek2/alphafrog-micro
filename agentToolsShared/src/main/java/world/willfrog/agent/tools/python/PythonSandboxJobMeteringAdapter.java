package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.agent.tools.sandboxjob.SandboxJobMeteringAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;

/**
 * executePython 的计量适配器：把沙箱终态里的资源用量映射成容量账本的用量模型。
 *
 * <p>用量 JSON 缺失时按既有的「缺失」语义记（{@link DataAnalysisResourceUsage#missing}），
 * 解析失败抛异常——终态三段持久会因此中断并转后台收尾，与既有行为一致。</p>
 */
public final class PythonSandboxJobMeteringAdapter implements SandboxJobMeteringAdapter {

    private final ObjectMapper objectMapper;

    public PythonSandboxJobMeteringAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public DataAnalysisResourceUsage toUsage(
            SandboxTerminalResultView result, DataAnalysisResourceClass resourceClass) {
        if (result.usageJson() == null) {
            return DataAnalysisResourceUsage.missing(resourceClass);
        }
        try {
            return SandboxResourceUsageParser.parse(objectMapper, resourceClass, result.usageJson());
        } catch (Exception parseFailure) {
            throw new IllegalStateException("sandbox resource usage could not be parsed", parseFailure);
        }
    }
}
