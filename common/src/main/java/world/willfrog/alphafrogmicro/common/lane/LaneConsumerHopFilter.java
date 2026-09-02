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
 * 消费方出站时把当前流量范围写入附件，无标调用在发出前清掉残留。
 *
 * <p>这里不缓存路由指针。精确实例由 {@link LaneExactInstanceRouter} 在同一次调用开始时读取。</p>
 */
@Activate(group = CommonConstants.CONSUMER, order = -10000)
public final class LaneConsumerHopFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        String scope = LaneContext.trafficScopeId();
        String previousAttachment = RpcContext.getClientAttachment()
                .getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID);
        String previousMdc = MDC.get(LaneContext.MDC_LANE_TAG);
        if (scope == null || scope.isBlank()) {
            RpcContext.getClientAttachment().removeAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID);
            MDC.remove(LaneContext.MDC_LANE_TAG);
        } else {
            RpcContext.getClientAttachment().setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, scope);
            invocation.setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, scope);
            MDC.put(LaneContext.MDC_LANE_TAG, scope);
        }
        try {
            return invoker.invoke(invocation);
        } finally {
            if (previousAttachment != null) {
                RpcContext.getClientAttachment()
                        .setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, previousAttachment);
            } else {
                RpcContext.getClientAttachment().removeAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID);
            }
            if (previousMdc != null) {
                MDC.put(LaneContext.MDC_LANE_TAG, previousMdc);
            } else {
                MDC.remove(LaneContext.MDC_LANE_TAG);
            }
        }
    }
}
