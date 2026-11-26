package world.willfrog.alphafrogmicro.common.racing;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.dubbo.rpc.RpcContext;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.racing.idl.RacingService;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.RuntimeIdentity;
import world.willfrog.racing.idl.TargetIdentity;

/** 只在本次平台调用期间使用目标路由，完成或失败后恢复原上下文。 */
public final class RacingTransport {
    public static final String KEY_ID_ATTACHMENT = "racing.service-key-id";
    public static final String TOKEN_ATTACHMENT = "racing.service-token";
    private static final String FORCE_TAG_ATTACHMENT = "dubbo.force.tag";

    private final RacingService service;
    private final RacingClientSettings settings;

    public RacingTransport(RacingService service, RacingClientSettings settings) {
        this.service = Objects.requireNonNull(service);
        this.settings = Objects.requireNonNull(settings);
    }

    /** 回调使用本次已核对的上下文构造请求；身份读取器先保留业务错误再返回实际目标。 */
    public <T> T call(RuntimeCallContext context,
                      BiFunction<RacingService, RuntimeCallContext, T> invocation,
                      Function<T, RuntimeIdentity> identityReader) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(invocation);
        Objects.requireNonNull(identityReader);
        TargetIdentity configured = settings.target();
        requireSameLogicalTarget(context.getTarget(), configured);
        // 普通发布可变化构建代际；根绑定的创建编号、部署与标签保持原值。
        RuntimeCallContext current = context.toBuilder().setTarget(configured)
                .setCallerGenerationId(settings.callerGenerationId()).build();
        RacingClientSettings.Credentials credentials = settings.credentials();
        String previousScope = LaneContext.trafficScopeId();
        String previousKey = attachment(KEY_ID_ATTACHMENT);
        String previousToken = attachment(TOKEN_ATTACHMENT);
        String previousForce = attachment(FORCE_TAG_ATTACHMENT);
        try {
            LaneContext.setTrafficScopeId(configured.getTargetRouteTag());
            setAttachment(KEY_ID_ATTACHMENT, credentials.keyId());
            setAttachment(TOKEN_ATTACHMENT, credentials.token());
            setAttachment(FORCE_TAG_ATTACHMENT, LaneContext.officialDubboTag() == null ? null : "true");
            T reply = invocation.apply(service, current);
            requireActualTarget(configured, Objects.requireNonNull(identityReader.apply(reply)));
            return reply;
        } finally {
            LaneContext.restore(previousScope);
            setAttachment(KEY_ID_ATTACHMENT, previousKey);
            setAttachment(TOKEN_ATTACHMENT, previousToken);
            setAttachment(FORCE_TAG_ATTACHMENT, previousForce);
        }
    }

    private static void requireSameLogicalTarget(TargetIdentity saved, TargetIdentity configured) {
        if (!saved.getEnvironment().equals(configured.getEnvironment())
                || !saved.getTargetScopeInstanceId().equals(configured.getTargetScopeInstanceId())
                || !saved.getTargetRouteTag().equals(configured.getTargetRouteTag())
                || !saved.getTargetDeploymentId().equals(configured.getTargetDeploymentId())) {
            throw RacingCallException.failure("SCOPE_MISMATCH", "根运行保存的平台目标与当前部署映射不一致");
        }
    }

    private static void requireActualTarget(TargetIdentity expected, RuntimeIdentity actual) {
        if (!expected.getEnvironment().equals(actual.getEnvironment())
                || !expected.getTargetScopeInstanceId().equals(actual.getScopeInstanceId())
                || !expected.getTargetRouteTag().equals(actual.getRouteTag())
                || !expected.getTargetDeploymentId().equals(actual.getDeploymentId())
                || !expected.getExpectedTargetGenerationId().equals(actual.getActualTargetGenerationId())) {
            throw RacingCallException.failure("SCOPE_MISMATCH", "平台实际处理实例与本次预期目标不一致");
        }
    }

    private static String attachment(String key) {
        return RpcContext.getClientAttachment().getAttachment(key);
    }

    private static void setAttachment(String key, String value) {
        if (value == null) RpcContext.getClientAttachment().removeAttachment(key);
        else RpcContext.getClientAttachment().setAttachment(key, value);
    }
}
