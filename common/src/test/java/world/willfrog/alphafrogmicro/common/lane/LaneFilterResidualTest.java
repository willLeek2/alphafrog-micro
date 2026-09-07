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
    void provider_markedCall_shouldExposeOfficialTagInsideInvoker() {
        LaneContext.setTrafficScopeId("previous-scope");
        Invocation invocation = mock(Invocation.class);
        when(invocation.getAttachment(LaneContext.DUBBO_TAG_KEY)).thenReturn("lane-test");
        AtomicReference<String> inside = new AtomicReference<>();
        new LaneProviderEntryFilter().invoke(invoker(() -> {
            inside.set(LaneContext.trafficScopeId());
            return mock(Result.class);
        }), invocation);
        assertThat(inside.get()).isEqualTo("lane-test");
        assertThat(LaneContext.trafficScopeId()).isEqualTo("previous-scope");
    }

    @Test
    void provider_legacyCustomAttachment_shouldNotRestoreScope() {
        LaneContext.setTrafficScopeId("previous-scope");
        Invocation invocation = mock(Invocation.class);
        when(invocation.getAttachment(LaneContext.DUBBO_TAG_KEY)).thenReturn(null);
        when(invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID)).thenReturn("lane-test");
        AtomicReference<String> inside = new AtomicReference<>();
        new LaneProviderEntryFilter().invoke(invoker(() -> {
            inside.set(LaneContext.trafficScopeId());
            return mock(Result.class);
        }), invocation);
        assertThat(inside.get()).isNull();
        assertThat(LaneContext.trafficScopeId()).isEqualTo("previous-scope");
    }

    @Test
    void consumer_unmarkedCall_shouldRemoveOfficialTagBeforeInvoke() {
        RpcContext.getClientAttachment().setAttachment(LaneContext.DUBBO_TAG_KEY, "stale-tag");
        RpcContext.getClientAttachment().setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "stale-scope");
        MDC.put(LaneContext.MDC_LANE_TAG, "stale-tag");
        LaneContext.clear();
        AtomicBoolean insideClean = new AtomicBoolean();
        new LaneConsumerHopFilter().invoke(invoker(() -> {
            insideClean.set(
                    RpcContext.getClientAttachment().getAttachment(LaneContext.DUBBO_TAG_KEY) == null
                            && "stale-scope".equals(RpcContext.getClientAttachment()
                                    .getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID))
                            && MDC.get(LaneContext.MDC_LANE_TAG) == null);
            return mock(Result.class);
        }), mock(Invocation.class));
        assertThat(insideClean).isTrue();
        assertThat(RpcContext.getClientAttachment().getAttachment(LaneContext.DUBBO_TAG_KEY))
                .isEqualTo("stale-tag");
        assertThat(RpcContext.getClientAttachment().getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID))
                .isEqualTo("stale-scope");
    }

    @Test
    void consumer_laneTag_shouldWriteOfficialTagBeforeInvoke() {
        LaneContext.setTrafficScopeId("lane-test");
        RpcContext.getClientAttachment().setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "leftover-scope");
        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "leftover-invocation-scope");
        AtomicReference<String> clientTag = new AtomicReference<>();
        AtomicReference<String> invocationTag = new AtomicReference<>();
        AtomicReference<String> clientLegacyInside = new AtomicReference<>();
        AtomicReference<String> invocationLegacyInside = new AtomicReference<>();
        new LaneConsumerHopFilter().invoke(invoker(() -> {
            clientTag.set(RpcContext.getClientAttachment().getAttachment(LaneContext.DUBBO_TAG_KEY));
            invocationTag.set(invocation.getAttachment(LaneContext.DUBBO_TAG_KEY));
            clientLegacyInside.set(
                    RpcContext.getClientAttachment().getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
            invocationLegacyInside.set(invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID));
            return mock(Result.class);
        }), invocation);
        assertThat(clientTag.get()).isEqualTo("lane-test");
        assertThat(invocationTag.get()).isEqualTo("lane-test");
        assertThat(clientLegacyInside.get()).isEqualTo("leftover-scope");
        assertThat(invocationLegacyInside.get()).isEqualTo("leftover-invocation-scope");
        assertThat(invocation.getAttachment(LaneContext.DUBBO_TAG_KEY)).isNull();
        assertThat(invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID))
                .isEqualTo("leftover-invocation-scope");
        assertThat(RpcContext.getClientAttachment().getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID))
                .isEqualTo("leftover-scope");
        assertThat(LaneContext.trafficScopeId()).isEqualTo("lane-test");
    }

    @Test
    void consumer_mainBeta_shouldNotWriteOfficialTag() {
        LaneContext.setTrafficScopeId(LaneContext.MAIN_BETA_TRAFFIC_SCOPE_ID);
        RpcContext.getClientAttachment().setAttachment(LaneContext.DUBBO_TAG_KEY, "stale-tag");
        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(LaneContext.DUBBO_TAG_KEY, "stale-tag");
        AtomicBoolean insideClean = new AtomicBoolean();
        new LaneConsumerHopFilter().invoke(invoker(() -> {
            insideClean.set(
                    RpcContext.getClientAttachment().getAttachment(LaneContext.DUBBO_TAG_KEY) == null
                            && invocation.getAttachment(LaneContext.DUBBO_TAG_KEY) == null);
            return mock(Result.class);
        }), invocation);
        assertThat(insideClean).isTrue();
        assertThat(LaneContext.trafficScopeId()).isEqualTo(LaneContext.MAIN_BETA_TRAFFIC_SCOPE_ID);
    }

    @Test
    void consumer_unmarkedRpcInvocation_shouldClearStaleOfficialTagInsideInvoker() {
        LaneContext.clear();
        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(LaneContext.DUBBO_TAG_KEY, "stale-invocation-tag");
        invocation.setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "stale-invocation-scope");
        AtomicBoolean insideClean = new AtomicBoolean();
        new LaneConsumerHopFilter().invoke(invoker(() -> {
            insideClean.set(
                    invocation.getAttachment(LaneContext.DUBBO_TAG_KEY) == null
                            && invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID)
                                    .equals("stale-invocation-scope")
                            && RpcContext.getClientAttachment().getAttachment(LaneContext.DUBBO_TAG_KEY) == null
                            && MDC.get(LaneContext.MDC_LANE_TAG) == null);
            return mock(Result.class);
        }), invocation);
        assertThat(insideClean).isTrue();
        assertThat(invocation.getAttachment(LaneContext.DUBBO_TAG_KEY)).isEqualTo("stale-invocation-tag");
        assertThat(invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID))
                .isEqualTo("stale-invocation-scope");
    }

    @Test
    void provider_unmarkedRpcInvocation_shouldIgnoreStaleServerAttachment() {
        RpcContext.getServerAttachment().setAttachment(LaneContext.DUBBO_TAG_KEY, "stale-server-tag");
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
        when(invocation.getAttachment(LaneContext.DUBBO_TAG_KEY)).thenReturn(null);
        return invocation;
    }
}
