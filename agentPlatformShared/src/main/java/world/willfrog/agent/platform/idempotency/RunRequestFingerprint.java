package world.willfrog.agent.platform.idempotency;

/**
 * 创建 Run 的请求指纹：参与摘要的字段就是这一组。
 *
 * <p>只放客户端能决定、且决定「这条 Run 要做什么」的字段。发起人身份不在里面：键的唯一范围已经按用户
 * 划开，同一个键在不同用户下本来就可以各建一条。执行环境相关的东西（部署代际、泳道、调度器版本）
 * 不在里面：同一次创建请求在滚动部署前后重复提交，应当读回原来那条 Run。</p>
 *
 * <p>「是否生成产物」「候选计划数量」「调试模式」三个字段与环境无关，但会改变这次运行产出什么、
 * 跑几张候选计划和按哪种调试方式跑，所以必须参与摘要：同一个键换掉其中任何一个，都不是同一次请求。</p>
 *
 * @param userId             发起人，用来把「请求是不是同一个人发的」写进摘要，便于排查时对上号
 * @param message            用户请求文本
 * @param contextJson        客户端上下文
 * @param modelName          指定的模型
 * @param endpointName       指定的端点
 * @param provider           供应方顺序
 * @param captureLlmRequests   是否抓取模型请求
 * @param plannerCandidateCount 规划阶段候选计划数量
 * @param debugMode            是否按调试方式运行
 * @param generateArtifacts    是否生成产物
 * @param stageConfigJson      分阶段模型配置
 */
public record RunRequestFingerprint(
        String userId,
        String message,
        String contextJson,
        String modelName,
        String endpointName,
        String provider,
        boolean captureLlmRequests,
        int plannerCandidateCount,
        boolean debugMode,
        boolean generateArtifacts,
        String stageConfigJson) {
}
