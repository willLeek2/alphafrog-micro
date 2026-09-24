package world.willfrog.agentlangchain.acceptance;

import java.time.OffsetDateTime;

/**
 * 一条验收夹具当前的样子。
 *
 * <p>内容全部由受限控制面预先写入（宿主机按泳道与部署代际发布），业务请求里只带一个编号。
 * 这里不解释计划与模型脚本的内容，只回答「这条夹具现在能不能用」。</p>
 *
 * @param fixtureId         请求上下文里带的编号（{@code acceptanceFixtureId}）
 * @param scenarioId        场景名，用于证据里跟验收清单对照
 * @param enabled           是否已启用
 * @param enabledAt         启用时间；启用必须有时间，停用必须没有
 * @param expiresAt         授权到期时间；过了这一刻一律不可用
 * @param planJson          冻结计划（可空：不由夹具决定计划的场景留空）
 * @param modelScriptJson   冻结的模型回合，按顺序消费
 * @param dispatchPolicyJson 结果放行与故障策略
 */
public record AcceptanceFixtureRow(String fixtureId,
                                   String scenarioId,
                                   boolean enabled,
                                   OffsetDateTime enabledAt,
                                   OffsetDateTime expiresAt,
                                   String planJson,
                                   String modelScriptJson,
                                   String dispatchPolicyJson) {

    /** 这一刻是不是已经过期：过期只说明不能再拿它跑样本，不等于这条夹具被删掉了。 */
    public boolean expiredAt(OffsetDateTime now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    /** 给日志与拒绝原因用的一句话描述。 */
    public String describe() {
        return "fixture=" + fixtureId + " scenario=" + scenarioId + " enabled=" + enabled
                + " expiresAt=" + expiresAt;
    }
}
