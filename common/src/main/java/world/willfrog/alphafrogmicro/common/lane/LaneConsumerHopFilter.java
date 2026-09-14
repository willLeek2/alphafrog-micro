package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.cluster.filter.ClusterFilter;
import org.slf4j.MDC;

/**
 * 消费方在标签路由之前写入官方 {@code dubbo.tag}，无标调用在发出前清掉残留。
 *
 * <p>本组件是集群过滤器，包在集群调用器外面，因此 {@code TagStateRouter} 读取本次
 * {@link Invocation} 时已经能看到标签。无标路径会同时清除客户端上下文、MDC 和本次调用上的
 * 官方标签。嵌套调用只保存并恢复官方标签和日志字段，结束后外层线程上下文回到进入前的值。
 * 本过滤器不选择实例，也不读写旧的自定义流量范围附件。</p>
 */
@Activate(group = CommonConstants.CONSUMER, order = -10000)
public final class LaneConsumerHopFilter implements ClusterFilter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        String tag = LaneContext.officialDubboTag();
        String previousClientTag = RpcContext.getClientAttachment().getAttachment(LaneContext.DUBBO_TAG_KEY);
        String previousInvocationTag = invocation == null
                ? null
                : invocation.getAttachment(LaneContext.DUBBO_TAG_KEY);
        String previousMdc = MDC.get(LaneContext.MDC_LANE_TAG);
        if (tag == null) {
            RpcContext.getClientAttachment().removeAttachment(LaneContext.DUBBO_TAG_KEY);
            clearInvocationAttachment(invocation, LaneContext.DUBBO_TAG_KEY);
            MDC.remove(LaneContext.MDC_LANE_TAG);
        } else {
            RpcContext.getClientAttachment().setAttachment(LaneContext.DUBBO_TAG_KEY, tag);
            if (invocation != null) {
                invocation.setAttachment(LaneContext.DUBBO_TAG_KEY, tag);
            }
            MDC.put(LaneContext.MDC_LANE_TAG, tag);
        }
        try {
            return invoker.invoke(invocation);
        } finally {
            restoreClientAttachment(LaneContext.DUBBO_TAG_KEY, previousClientTag);
            restoreInvocationAttachment(invocation, LaneContext.DUBBO_TAG_KEY, previousInvocationTag);
            if (previousMdc != null) {
                MDC.put(LaneContext.MDC_LANE_TAG, previousMdc);
            } else {
                MDC.remove(LaneContext.MDC_LANE_TAG);
            }
        }
    }

    private static void clearInvocationAttachment(Invocation invocation, String key) {
        if (invocation == null) {
            return;
        }
        invocation.setAttachment(key, null);
    }

    private static void restoreClientAttachment(String key, String previous) {
        if (previous != null) {
            RpcContext.getClientAttachment().setAttachment(key, previous);
        } else {
            RpcContext.getClientAttachment().removeAttachment(key);
        }
    }

    private static void restoreInvocationAttachment(Invocation invocation, String key, String previous) {
        if (invocation == null) {
            return;
        }
        invocation.setAttachment(key, previous);
    }
}
