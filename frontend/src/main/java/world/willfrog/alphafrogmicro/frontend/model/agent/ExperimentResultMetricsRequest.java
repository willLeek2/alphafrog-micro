package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

/**
 * 一次查看要读取的根运行编号。只包含编号，不包含用户身份、管理员标记或开发者密钥。
 */
public record ExperimentResultMetricsRequest(List<String> rootRunIds) {
}
