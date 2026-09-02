package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.slf4j.MDC;

/**
 * 提供方入站时恢复流量范围，无标请求先清掉线程残留。
 *
 * <p>本次请求的权威范围只来自当前 {@link Invocation} 附件，不用服务器线程上下文补值。
 * 精确实例绑定发生在消费方每次新调用开始时。</p>
 */
@Activate(group = CommonConstants.PROVIDER, order = -10000)
public final class LaneProviderEntryFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        String inbound = invocation == null
                ? null
                : blankToNull(invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
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

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
