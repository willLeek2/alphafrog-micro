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
    /** 旧锚点上的操作标识；非空才说明有活跃长工具，迁移期用来兼容上一版等待。 */
    private String anchorOperationId;
}
