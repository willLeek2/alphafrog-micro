package world.willfrog.agent.platform.childrun;

import lombok.Data;

/** 在预留或受理事务里锁定的父 Run 当前控制事实。 */
@Data
public class ChildRunParentSnapshot {
    private String id;
    private String status;
    private Integer planGeneration;
    private Long runControlVersion;
    private String schedulerVersion;
    private String deploymentId;
    private String deploymentGenerationId;
}
