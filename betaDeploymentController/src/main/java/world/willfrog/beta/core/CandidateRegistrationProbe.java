package world.willfrog.beta.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 候选服务实例是否已由服务进程自行注册为可用提供者。
 *
 * <p>控制器只在候选健康后做一次可见性查询，不代替服务写注册、续心跳或修改权重。</p>
 */
public interface CandidateRegistrationProbe {

    boolean isVisible(JsonNode service, String address, int port);
}
