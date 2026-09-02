package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.MDC;

/**
 * 提供方入站时恢复流量范围，无标请求先清掉线程残留。
 *
 * <p>精确实例绑定发生在消费方每次新调用开始时，这里只恢复范围标识和日志字段。</p>
 */
@Activate(group = CommonConstants.PROVIDER, order = -10000)
public final class LaneProviderEntryFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        String inbound = firstNonBlank(
                invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID),
                RpcContext.getServerAttachment().getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
        String previous = LaneContext.trafficScopeId();
        String previousMdc = MDC.get(LaneContext.MDC_LANE_TAG);
        if (inbound == null) {
            LaneContext.clear();
            MDC.remove(LaneContext.MDC_LANE_TAG);
        } else {
            LaneContext.setTrafficScopeId(inbound);
            MDC.put(LaneContext.MDC_LANE_TAG, inbound);
        }
        try {
            return invoker.invoke(invocation);
        } finally {
            LaneContext.restore(previous);
            if (previousMdc != null) {
                MDC.put(LaneContext.MDC_LANE_TAG, previousMdc);
            } else {
                MDC.remove(LaneContext.MDC_LANE_TAG);
            }
        }
    }

    private static String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        if (right != null && !right.isBlank()) {
            return right;
        }
        return null;
    }
}
