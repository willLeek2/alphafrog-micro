package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LaneFilterResidualTest {

    @AfterEach
    void reset() {
        LaneContext.clear();
        MDC.clear();
        RpcContext.removeContext();
        RpcContext.removeServerContext();
        LaneRoutingSupport.reset();
    }

    @Test
    void provider_unmarkedCall_shouldClearResidualsInsideInvokerAndRestoreAfterward() {
        LaneContext.setTrafficScopeId("stale-scope");
        MDC.put(LaneContext.MDC_LANE_TAG, "stale-scope");
        AtomicBoolean insideClean = new AtomicBoolean();
        Filter filter = new LaneProviderEntryFilter();

        filter.invoke(invoker(() -> {
            insideClean.set(LaneContext.trafficScopeId() == null
                    && MDC.get(LaneContext.MDC_LANE_TAG) == null);
            return mock(Result.class);
        }), unmarkedInvocation());

        assertThat(insideClean).isTrue();
        assertThat(LaneContext.trafficScopeId()).isEqualTo("stale-scope");
        assertThat(MDC.get(LaneContext.MDC_LANE_TAG)).isEqualTo("stale-scope");
    }

    @Test
    void provider_markedCall_shouldExposeScopeInsideInvoker() {
        LaneContext.setTrafficScopeId("previous-scope");
        Invocation invocation = mock(Invocation.class);
        when(invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID)).thenReturn("main-beta");
        AtomicReference<String> inside = new AtomicReference<>();
        new LaneProviderEntryFilter().invoke(invoker(() -> {
            inside.set(LaneContext.trafficScopeId());
            return mock(Result.class);
        }), invocation);
        assertThat(inside.get()).isEqualTo("main-beta");
        assertThat(LaneContext.trafficScopeId()).isEqualTo("previous-scope");
    }

    @Test
    void consumer_unmarkedCall_shouldRemoveOutboundAttachmentBeforeInvoke() {
        RpcContext.getClientAttachment().setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "stale-scope");
        MDC.put(LaneContext.MDC_LANE_TAG, "stale-scope");
        LaneContext.clear();
        AtomicBoolean insideClean = new AtomicBoolean();
        new LaneConsumerHopFilter().invoke(invoker(() -> {
            insideClean.set(
                    RpcContext.getClientAttachment().getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID) == null
                            && MDC.get(LaneContext.MDC_LANE_TAG) == null);
            return mock(Result.class);
        }), mock(Invocation.class));
        assertThat(insideClean).isTrue();
        assertThat(RpcContext.getClientAttachment().getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID))
                .isEqualTo("stale-scope");
    }

    @Test
    void consumer_markedCall_shouldWriteScopeBeforeInvoke() {
        LaneContext.setTrafficScopeId("main-beta");
        AtomicReference<String> outbound = new AtomicReference<>();
        new LaneConsumerHopFilter().invoke(invoker(() -> {
            outbound.set(RpcContext.getClientAttachment().getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
            return mock(Result.class);
        }), mock(Invocation.class));
        assertThat(outbound.get()).isEqualTo("main-beta");
        assertThat(LaneContext.trafficScopeId()).isEqualTo("main-beta");
    }

    @Test
    void consumer_unmarkedRpcInvocation_shouldClearStaleInvocationAttachmentInsideInvoker() {
        LaneContext.clear();
        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "stale-invocation-scope");
        AtomicBoolean insideClean = new AtomicBoolean();
        new LaneConsumerHopFilter().invoke(invoker(() -> {
            insideClean.set(
                    invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID) == null
                            && RpcContext.getClientAttachment()
                                    .getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID) == null
                            && MDC.get(LaneContext.MDC_LANE_TAG) == null);
            return mock(Result.class);
        }), invocation);
        assertThat(insideClean).isTrue();
        assertThat(invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID))
                .isEqualTo("stale-invocation-scope");
    }

    @Test
    void provider_unmarkedRpcInvocation_shouldIgnoreStaleServerAttachment() {
        RpcContext.getServerAttachment()
                .setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "stale-server-scope");
        LaneContext.setTrafficScopeId("outer-scope");
        MDC.put(LaneContext.MDC_LANE_TAG, "outer-scope");
        RpcInvocation invocation = new RpcInvocation();
        AtomicBoolean insideClean = new AtomicBoolean();
        new LaneProviderEntryFilter().invoke(invoker(() -> {
            insideClean.set(LaneContext.trafficScopeId() == null
                    && MDC.get(LaneContext.MDC_LANE_TAG) == null);
            return mock(Result.class);
        }), invocation);
        assertThat(insideClean).isTrue();
        assertThat(LaneContext.trafficScopeId()).isEqualTo("outer-scope");
        assertThat(MDC.get(LaneContext.MDC_LANE_TAG)).isEqualTo("outer-scope");
    }

    @SuppressWarnings("unchecked")
    private static Invoker<Object> invoker(java.util.concurrent.Callable<Result> body) {
        Invoker<Object> invoker = mock(Invoker.class);
        try {
            when(invoker.invoke(any())).thenAnswer(invocation -> body.call());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return invoker;
    }

    private static Invocation unmarkedInvocation() {
        Invocation invocation = mock(Invocation.class);
        when(invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID)).thenReturn(null);
        return invocation;
    }
}
