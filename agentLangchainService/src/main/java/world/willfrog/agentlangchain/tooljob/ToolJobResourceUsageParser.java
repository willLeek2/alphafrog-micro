package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.agent.tools.python.SandboxResourceUsageParser;

/** RELEASE proof 与 observability recorder 共用的 protobuf JSON usage 解析器。 */
final class ToolJobResourceUsageParser {

    private ToolJobResourceUsageParser() {
    }

    static DataAnalysisResourceUsage parse(
            ObjectMapper objectMapper,
            DataAnalysisResourceClass resourceClass,
            String usageJson) {
        return SandboxResourceUsageParser.parse(objectMapper, resourceClass, usageJson);
    }
}
