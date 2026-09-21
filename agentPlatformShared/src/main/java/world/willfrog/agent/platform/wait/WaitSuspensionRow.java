package world.willfrog.agent.platform.wait;

import lombok.Data;

/**
 * 整组挂起语句返回的原始计数行。
 *
 * <p>它只回答「语句执行时库里的实际情况是什么」，怎么解读由 {@link MybatisWaitGroupStore} 负责：
 * 分段交出去了没有、组是这次新建的还是上一次已经建好的、成员与下一段各写了几行。</p>
 */
@Data
public class WaitSuspensionRow {

    /** 这次真的把当前执行分段结束掉的行数：0 表示这一行已经不是当前执行者手里那一段了。 */
    private Integer closedSegments;
    /** 这次新建出来的等待组编号；重试时为 null。 */
    private Long createdGroupId;
    /** 库里已经存在的同身份等待组编号；新建成功时为 null。 */
    private Long existingGroupId;
    private Integer writtenMembers;
    private Integer writtenNextSegments;
    private Integer nextSegmentSequence;
}
