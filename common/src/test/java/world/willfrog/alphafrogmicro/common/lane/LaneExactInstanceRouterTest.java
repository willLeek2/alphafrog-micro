package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LaneExactInstanceRouterTest {

    private static final String SCOPE = "main-beta";
    private static final String GEN = "gen-" + "c".repeat(64);

    @AfterEach
    void reset() {
        LaneContext.clear();
        LaneRoutingSupport.reset();
    }

    @Test
    void route_shouldKeepOnlyExactEndpointAndNeverReturnUnfilteredList() {
        AtomicLaneRoutePointer pointer = new AtomicLaneRoutePointer();
        pointer.replaceAll(LaneRouteTable.of(List.of(new LaneServiceRoute(
                SCOPE,
                "agent-service",
                "com.alphafrog.AgentService:1.0@@providers",
                "instance-new",
                "release-2",
                GEN,
                8L,
                "2026-09-01T00:02:00Z",
                new LaneEndpoint("10.0.0.8", 28081)))));
        LaneRoutingSupport.install(new LaneCallRouter(pointer), true);
        LaneContext.setTrafficScopeId(SCOPE);

        Invoker<Object> oldInvoker = invoker("10.0.0.8", 28080);
        Invoker<Object> newInvoker = invoker("10.0.0.8", 28081);
        List<Invoker<Object>> unfiltered = List.of(oldInvoker, newInvoker);
        LaneExactInstanceRouter router = new LaneExactInstanceRouter(URL.valueOf("dubbo://127.0.0.1/agent-service"));

        List<Invoker<Object>> selected = router.route(
                unfiltered,
                URL.valueOf("dubbo://127.0.0.1/com.alphafrog.AgentService:1.0@@providers"),
                invocation("com.alphafrog.AgentService:1.0@@providers"));

        assertThat(selected).containsExactly(newInvoker);
        assertThat(selected).isNotSameAs(unfiltered);
        assertThat(router.isRuntime()).isTrue();
        assertThat(router.isForce()).isTrue();
    }

    @Test
    void route_shouldFailClosedInsteadOfReturningOriginalInvokers() {
        AtomicLaneRoutePointer pointer = new AtomicLaneRoutePointer();
        pointer.replaceAll(LaneRouteTable.empty());
        LaneRoutingSupport.install(new LaneCallRouter(pointer), true);
        LaneContext.setTrafficScopeId(SCOPE);
        List<Invoker<Object>> unfiltered = List.of(invoker("10.0.0.8", 28080));
        LaneExactInstanceRouter router = new LaneExactInstanceRouter(URL.valueOf("dubbo://127.0.0.1/agent-service"));

        AtomicReference<List<Invoker<Object>>> captured = new AtomicReference<>();
        assertThatThrownBy(() -> captured.set(router.route(
                unfiltered,
                URL.valueOf("dubbo://127.0.0.1/agent-service"),
                invocation("agent-service"))))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining(LaneRouteUnavailableException.CODE);
        assertThat(captured.get()).isNull();
    }

    @Test
    void route_shouldPassThroughWhenDisabledOrUnscoped() {
        List<Invoker<Object>> unfiltered = List.of(invoker("10.0.0.8", 28080));
        LaneExactInstanceRouter router = new LaneExactInstanceRouter(URL.valueOf("dubbo://127.0.0.1/agent-service"));
        assertThat(router.route(unfiltered, null, null)).isSameAs(unfiltered);
    }

    @SuppressWarnings("unchecked")
    private static Invoker<Object> invoker(String host, int port) {
        Invoker<Object> invoker = mock(Invoker.class);
        when(invoker.getUrl()).thenReturn(URL.valueOf("dubbo://" + host + ":" + port + "/com.alphafrog.AgentService"));
        return invoker;
    }

    private static Invocation invocation(String serviceName) {
        Invocation invocation = mock(Invocation.class);
        when(invocation.getServiceName()).thenReturn(serviceName);
        return invocation;
    }
}
