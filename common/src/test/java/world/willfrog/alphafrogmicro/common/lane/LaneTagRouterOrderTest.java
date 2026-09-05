package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.filter.ClusterFilter;
import org.apache.dubbo.rpc.cluster.router.state.BitList;
import org.apache.dubbo.rpc.cluster.router.tag.TagStateRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LaneTagRouterOrderTest {

    private static final URL CONSUMER_URL = URL.valueOf("consumer://127.0.0.1/demo");
    private static final URL TAGGED_PROVIDER =
            URL.valueOf("dubbo://10.0.0.8:20880/demo?" + LaneContext.DUBBO_TAG_KEY + "=lane-test");
    private static final URL UNTAGGED_PROVIDER = URL.valueOf("dubbo://10.0.0.9:20880/demo");

    @AfterEach
    void reset() {
        LaneContext.clear();
        MDC.clear();
        RpcContext.removeContext();
        RpcContext.removeServerContext();
    }

    @Test
    void consumerHopFilter_shouldBeClusterFilterNotProtocolFilter() {
        assertThat(new LaneConsumerHopFilter()).isInstanceOf(ClusterFilter.class);
        assertThat(Filter.class.isAssignableFrom(LaneConsumerHopFilter.class)).isFalse();
    }

    @Test
    void tagRouter_withoutClusterFilter_shouldSelectUntaggedWhenOnlyLaneContextIsSet() {
        Invoker<Object> tagged = invoker(TAGGED_PROVIDER);
        Invoker<Object> untagged = invoker(UNTAGGED_PROVIDER);
        LaneContext.setTrafficScopeId("lane-test");
        RpcInvocation invocation = new RpcInvocation();

        List<Invoker<Object>> selected = route(invocation, tagged, untagged);

        assertThat(selected).containsExactly(untagged);
        assertThat(invocation.getAttachment(LaneContext.DUBBO_TAG_KEY)).isNull();
    }

    @Test
    void clusterFilterThenTagRouter_laneTest_shouldSelectTaggedInstance() {
        Invoker<Object> tagged = invoker(TAGGED_PROVIDER);
        Invoker<Object> untagged = invoker(UNTAGGED_PROVIDER);
        LaneContext.setTrafficScopeId("lane-test");
        RpcInvocation invocation = new RpcInvocation();
        AtomicReference<List<Invoker<Object>>> selected = new AtomicReference<>();

        new LaneConsumerHopFilter().invoke(routingInvoker(tagged, untagged, selected), invocation);

        assertThat(selected.get()).containsExactly(tagged);
        assertThat(invocation.getAttachment(LaneContext.DUBBO_TAG_KEY)).isNull();
        assertThat(LaneContext.trafficScopeId()).isEqualTo("lane-test");
    }

    @Test
    void clusterFilterThenTagRouter_mainBeta_shouldSelectUntaggedInstance() {
        Invoker<Object> tagged = invoker(TAGGED_PROVIDER);
        Invoker<Object> untagged = invoker(UNTAGGED_PROVIDER);
        LaneContext.setTrafficScopeId(LaneContext.MAIN_BETA_TRAFFIC_SCOPE_ID);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(LaneContext.DUBBO_TAG_KEY, "stale-tag");
        AtomicReference<List<Invoker<Object>>> selected = new AtomicReference<>();

        new LaneConsumerHopFilter().invoke(routingInvoker(tagged, untagged, selected), invocation);

        assertThat(selected.get()).containsExactly(untagged);
    }

    @Test
    void clusterFilterThenTagRouter_emptyScope_shouldSelectUntaggedInstance() {
        Invoker<Object> tagged = invoker(TAGGED_PROVIDER);
        Invoker<Object> untagged = invoker(UNTAGGED_PROVIDER);
        LaneContext.clear();
        RpcInvocation invocation = new RpcInvocation();
        AtomicReference<List<Invoker<Object>>> selected = new AtomicReference<>();

        new LaneConsumerHopFilter().invoke(routingInvoker(tagged, untagged, selected), invocation);

        assertThat(selected.get()).containsExactly(untagged);
    }

    @Test
    void clusterFilterThenTagRouter_legacyAttachmentOnly_shouldSelectUntaggedInstance() {
        Invoker<Object> tagged = invoker(TAGGED_PROVIDER);
        Invoker<Object> untagged = invoker(UNTAGGED_PROVIDER);
        LaneContext.clear();
        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID, "lane-test");
        AtomicReference<List<Invoker<Object>>> selected = new AtomicReference<>();

        new LaneConsumerHopFilter().invoke(routingInvoker(tagged, untagged, selected), invocation);

        assertThat(selected.get()).containsExactly(untagged);
        assertThat(invocation.getAttachment(LaneContext.ATTACHMENT_TRAFFIC_SCOPE_ID)).isEqualTo("lane-test");
    }

    @Test
    void nestedClusterFilter_shouldRestoreOuterAttachmentsAndKeepLaneContext() {
        Invoker<Object> tagged = invoker(TAGGED_PROVIDER);
        Invoker<Object> untagged = invoker(UNTAGGED_PROVIDER);
        LaneContext.setTrafficScopeId("lane-test");
        MDC.put(LaneContext.MDC_LANE_TAG, "outer-mdc");
        RpcContext.getClientAttachment().setAttachment(LaneContext.DUBBO_TAG_KEY, "outer-previous");
        RpcInvocation outer = new RpcInvocation();
        AtomicReference<List<Invoker<Object>>> outerSelected = new AtomicReference<>();
        AtomicReference<List<Invoker<Object>>> innerSelected = new AtomicReference<>();

        new LaneConsumerHopFilter().invoke(invoker(invocation -> {
            outerSelected.set(route(invocation, tagged, untagged));
            RpcInvocation inner = new RpcInvocation();
            new LaneConsumerHopFilter().invoke(routingInvoker(tagged, untagged, innerSelected), inner);
            assertThat(LaneContext.trafficScopeId()).isEqualTo("lane-test");
            assertThat(MDC.get(LaneContext.MDC_LANE_TAG)).isEqualTo("lane-test");
            assertThat(RpcContext.getClientAttachment().getAttachment(LaneContext.DUBBO_TAG_KEY))
                    .isEqualTo("lane-test");
            return mock(Result.class);
        }), outer);

        assertThat(outerSelected.get()).containsExactly(tagged);
        assertThat(innerSelected.get()).containsExactly(tagged);
        assertThat(LaneContext.trafficScopeId()).isEqualTo("lane-test");
        assertThat(MDC.get(LaneContext.MDC_LANE_TAG)).isEqualTo("outer-mdc");
        assertThat(RpcContext.getClientAttachment().getAttachment(LaneContext.DUBBO_TAG_KEY))
                .isEqualTo("outer-previous");
        assertThat(outer.getAttachment(LaneContext.DUBBO_TAG_KEY)).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Invoker<Object> routingInvoker(
            Invoker<Object> tagged,
            Invoker<Object> untagged,
            AtomicReference<List<Invoker<Object>>> selected) {
        return invoker(invocation -> {
            selected.set(route(invocation, tagged, untagged));
            return mock(Result.class);
        });
    }

    private static List<Invoker<Object>> route(
            RpcInvocation invocation,
            Invoker<Object> tagged,
            Invoker<Object> untagged) {
        TagStateRouter<Object> router = new TagStateRouter<>(CONSUMER_URL);
        BitList<Invoker<Object>> candidates = new BitList<>(List.of(tagged, untagged));
        return List.copyOf(router.route(candidates, CONSUMER_URL, invocation, false, null));
    }

    @SuppressWarnings("unchecked")
    private static Invoker<Object> invoker(URL url) {
        Invoker<Object> invoker = mock(Invoker.class);
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.isAvailable()).thenReturn(true);
        return invoker;
    }

    @SuppressWarnings("unchecked")
    private static Invoker<Object> invoker(java.util.function.Function<RpcInvocation, Result> body) {
        Invoker<Object> invoker = mock(Invoker.class);
        when(invoker.invoke(any())).thenAnswer(invocation -> body.apply(invocation.getArgument(0)));
        return invoker;
    }
}
