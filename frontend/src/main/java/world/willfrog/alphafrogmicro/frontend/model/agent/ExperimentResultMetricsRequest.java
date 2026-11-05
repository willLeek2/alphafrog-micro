package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

/** 指标查询的根运行编号列表；用户身份由登录上下文提供。 */
public record ExperimentResultMetricsRequest(List<String> rootRunIds) {
}
