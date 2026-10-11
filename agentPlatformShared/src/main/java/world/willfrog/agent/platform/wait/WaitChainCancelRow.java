package world.willfrog.agent.platform.wait;

import lombok.Data;

/**
 * 取消一条等待链时每个动作各自影响的行数。
 */
@Data
public class WaitChainCancelRow {

    private Integer groupsCanceled;
    private Integer membersCanceled;
    private Integer segmentsCanceled;
    private Integer notificationsCanceled;
}
