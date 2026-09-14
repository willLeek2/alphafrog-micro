package world.willfrog.agent.platform.entity;

import lombok.Data;

/** 数据库中仍有未结束 Run 的部署代际坐标。 */
@Data
public class DeploymentGenerationRecord {
    private String deploymentId;
    private String deploymentGenerationId;
}
