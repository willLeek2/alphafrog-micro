package world.willfrog.agent.platform.coordination;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一个 Run 的协调资格：延期原因、下次可见时间与两个轮次空间的轮转位置。
 *
 * <p>它直接使用已有的 Run 身份（主键就是 run_id），不另外造一份分组身份。计划代际在这里只做记录，
 * 用来把「提醒去重」的键与 Run 主记录对齐。</p>
 *
 * <p>轮转位置分两组：Run 协调与节点派发各自走一个轮次空间，各记各的进度。合成一组会让两个调度器
 * 互相改写对方的进度，「上一轮谁先被服务」就无从算起。</p>
 */
@Data
public class RunCoordination {

    private String runId;
    private String schedulerVersion;
    private Integer planGeneration;
    /** 最近一次 Run 协调没能推进这个 Run 的原因；成功推进时清空。取值见 {@link RunCoordinationDeferReason}。 */
    private String deferReason;
    private OffsetDateTime nextVisibleAt;
    /** 最近一次被 Run 协调服务时的全局轮次号；补扫按它升序取，没有图连续错过两个完整轮次。 */
    private Long coordinationServedRound;
    /** 连续没有获得 Run 协调的轮数，给观测看，也用来验证公平性。 */
    private Integer coordinationMissedRounds;
    /** 最近一次被节点派发服务时的全局轮次号；与 Run 协调各记一组，互不覆盖。 */
    private Long dispatchServedRound;
    /** 连续没有获得节点派发的轮数，给观测看。 */
    private Integer dispatchMissedRounds;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public boolean deferred() {
        return deferReason != null && !deferReason.isBlank();
    }

    /** 延期原因的枚举读法；没有延期时返回空。 */
    public RunCoordinationDeferReason deferReasonEnum() {
        return deferred() ? RunCoordinationDeferReason.fromWire(deferReason) : null;
    }
}
