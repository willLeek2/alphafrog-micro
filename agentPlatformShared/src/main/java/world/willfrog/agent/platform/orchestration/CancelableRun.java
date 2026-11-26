package world.willfrog.agent.platform.orchestration;

import lombok.Data;

/** 取消事务开始时锁住并读到的运行事实。 */
@Data
public class CancelableRun {
    private String runId;
    private String userId;
    private String status;
    private String schedulerVersion;
    private Long runControlVersion;
    /** 旧锚点列是否还有内容；迁移期用来兼容上一版仍在等待长工具的运行。 */
    private Boolean activeAnchor;
}
