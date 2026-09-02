package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
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
    private static final LaneCallBinding NEW_BINDING = new LaneCallBinding(
            SCOPE,
            "agent-service",
            "instance-new",
            "release-2",
            GEN,
            8L,
            new LaneEndpoint("10.0.0.8", 28081));

    @AfterEach
    void reset() {
        LaneContext.clear();
        LaneCallBindingContext.clear();
        LaneRoutingSupport.reset();
    }

    @Test
    void route_shouldKeepOnlyExactIdentityAndNeverReturnUnfilteredList() {
        installNewRoute();
        Invoker<Object> oldInvoker = invoker("10.0.0.8", 28080, "instance-old");
        Invoker<Object> newInvoker = invoker("10.0.0.8", 28081, "instance-new");
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
    void route_shouldRejectSameEndpointWithWrongInstanceId() {
        installNewRoute();
        assertRouteFails(List.of(invoker("10.0.0.8", 28081, "instance-old")));
        assertThat(LaneExactInstanceRouter.matches(
                url("10.0.0.8", 28081, "instance-old"), NEW_BINDING)).isFalse();
    }

    @Test
    void route_shouldRejectSameInstanceIdWithWrongPort() {
        installNewRoute();
        assertRouteFails(List.of(invoker("10.0.0.8", 28080, "instance-new")));
        assertThat(LaneExactInstanceRouter.matches(
                url("10.0.0.8", 28080, "instance-new"), NEW_BINDING)).isFalse();
    }

    @Test
    void route_shouldRejectMissingInstanceIdEvenWhenEndpointMatches() {
        installNewRoute();
        assertRouteFails(List.of(invoker("10.0.0.8", 28081, null)));
        assertThat(LaneExactInstanceRouter.matches(
                url("10.0.0.8", 28081, null), NEW_BINDING)).isFalse();
    }

    @Test
    void route_shouldRejectMultipleRecordsClaimingTheSameExactIdentity() {
        installNewRoute();
        Invoker<Object> first = invoker("10.0.0.8", 28081, "instance-new");
        Invoker<Object> duplicate = invoker("10.0.0.8", 28081, "instance-new");
        assertRouteFails(List.of(first, duplicate));
    }

    @Test
    void route_shouldFailClosedInsteadOfReturningOriginalInvokers() {
        AtomicLaneRoutePointer pointer = new AtomicLaneRoutePointer();
        pointer.replaceAll(LaneRouteTable.empty());
        LaneRoutingSupport.install(new LaneCallRouter(pointer), true);
        LaneContext.setTrafficScopeId(SCOPE);
        List<Invoker<Object>> unfiltered = List.of(invoker("10.0.0.8", 28080, "instance-old"));
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
        List<Invoker<Object>> unfiltered = List.of(invoker("10.0.0.8", 28080, "instance-old"));
        LaneExactInstanceRouter router = new LaneExactInstanceRouter(URL.valueOf("dubbo://127.0.0.1/agent-service"));
        assertThat(router.route(unfiltered, null, null)).isSameAs(unfiltered);
    }

    @Test
    void route_shouldHonorTrustedRequestBindingWithoutAProcessLevelPointer() {
        LaneContext.setTrafficScopeId(SCOPE);
        LaneCallBindingContext.set("com.alphafrog.AgentService:1.0@@providers", NEW_BINDING);
        Invoker<Object> oldInvoker = invoker("10.0.0.8", 28080, "instance-old");
        Invoker<Object> newInvoker = invoker("10.0.0.8", 28081, "instance-new");
        LaneExactInstanceRouter router = new LaneExactInstanceRouter(URL.valueOf("dubbo://127.0.0.1/agent-service"));

        List<Invoker<Object>> selected = router.route(
                List.of(oldInvoker, newInvoker),
                URL.valueOf("dubbo://127.0.0.1/com.alphafrog.AgentService:1.0@@providers"),
                realInvocation("com.alphafrog.AgentService", "com.alphafrog.AgentService:1.0"));

        assertThat(selected).containsExactly(newInvoker);
    }

    @Test
    void route_shouldUseRequestBindingForMatchingServiceButReadCurrentPointerForOtherServices() {
        AtomicLaneRoutePointer pointer = new AtomicLaneRoutePointer();
        pointer.replaceAll(LaneRouteTable.of(List.of(
                new LaneServiceRoute(
                        SCOPE,
                        "agent-service",
                        "com.alphafrog.AgentService:1.0@@providers",
                        "instance-current",
                        "release-3",
                        "gen-" + "d".repeat(64),
                        9L,
                        "2026-09-01T00:03:00Z",
                        new LaneEndpoint("10.0.0.8", 28082)),
                new LaneServiceRoute(
                        SCOPE,
                        "tools-service",
                        "com.alphafrog.ToolsService:1.0@@providers",
                        "tools-current",
                        "tools-release-3",
                        "gen-" + "e".repeat(64),
                        10L,
                        "2026-09-01T00:04:00Z",
                        new LaneEndpoint("10.0.0.9", 29082)))));
        LaneRoutingSupport.install(new LaneCallRouter(pointer), true);
        LaneContext.setTrafficScopeId(SCOPE);
        LaneCallBindingContext.set("com.alphafrog.AgentService:1.0@@providers", NEW_BINDING);
        Invoker<Object> pinned = invoker("10.0.0.8", 28081, "instance-new");
        Invoker<Object> current = invoker("10.0.0.8", 28082, "instance-current");
        LaneExactInstanceRouter router = new LaneExactInstanceRouter(URL.valueOf("dubbo://127.0.0.1/agent-service"));

        List<Invoker<Object>> selected = router.route(
                List.of(pinned, current),
                URL.valueOf("dubbo://127.0.0.1/com.alphafrog.AgentService:1.0@@providers"),
                realInvocation("com.alphafrog.AgentService", "com.alphafrog.AgentService:1.0"));

        assertThat(selected).containsExactly(pinned);

        Invoker<Object> toolsCurrent = invoker("10.0.0.9", 29082, "tools-current");
        List<Invoker<Object>> selectedTools = router.route(
                List.of(toolsCurrent),
                URL.valueOf("dubbo://127.0.0.1/com.alphafrog.ToolsService:1.0@@providers"),
                realInvocation("com.alphafrog.ToolsService", "com.alphafrog.ToolsService:1.0"));
        assertThat(selectedTools).containsExactly(toolsCurrent);
    }

    @Test
    void route_shouldRetainProtocolVersionWhenMatchingARequestBinding() {
        AtomicLaneRoutePointer pointer = new AtomicLaneRoutePointer();
        pointer.replaceAll(LaneRouteTable.of(List.of(new LaneServiceRoute(
                SCOPE,
                "agent-service-v2",
                "com.alphafrog.AgentService:2.0@@providers",
                "instance-v2",
                "release-v2",
                "gen-" + "f".repeat(64),
                10L,
                "2026-09-01T00:05:00Z",
                new LaneEndpoint("10.0.0.8", 28083)))));
        LaneRoutingSupport.install(new LaneCallRouter(pointer), true);
        LaneContext.setTrafficScopeId(SCOPE);
        LaneCallBindingContext.set("com.alphafrog.AgentService:1.0@@providers", NEW_BINDING);
        Invoker<Object> pinnedV1 = invoker("10.0.0.8", 28081, "instance-new");
        Invoker<Object> currentV2 = invoker("10.0.0.8", 28083, "instance-v2");
        LaneExactInstanceRouter router = new LaneExactInstanceRouter(URL.valueOf("dubbo://127.0.0.1/agent-service"));

        List<Invoker<Object>> selected = router.route(
                List.of(pinnedV1, currentV2),
                URL.valueOf("dubbo://127.0.0.1/com.alphafrog.AgentService:2.0@@providers"),
                realInvocation("com.alphafrog.AgentService", "com.alphafrog.AgentService:2.0"));

        assertThat(selected).containsExactly(currentV2);
    }

    private static void installNewRoute() {
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
    }

    private static void assertRouteFails(List<Invoker<Object>> invokers) {
        LaneExactInstanceRouter router = new LaneExactInstanceRouter(URL.valueOf("dubbo://127.0.0.1/agent-service"));
        AtomicReference<List<Invoker<Object>>> captured = new AtomicReference<>();
        assertThatThrownBy(() -> captured.set(router.route(
                invokers,
                URL.valueOf("dubbo://127.0.0.1/com.alphafrog.AgentService:1.0@@providers"),
                invocation("com.alphafrog.AgentService:1.0@@providers"))))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining(LaneRouteFactsUncertainException.CODE);
        assertThat(captured.get()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Invoker<Object> invoker(String host, int port, String instanceId) {
        Invoker<Object> invoker = mock(Invoker.class);
        when(invoker.getUrl()).thenReturn(url(host, port, instanceId));
        return invoker;
    }

    private static URL url(String host, int port, String instanceId) {
        String spec = "dubbo://" + host + ":" + port + "/com.alphafrog.AgentService";
        if (instanceId != null) {
            spec += "?alphafrog.instance-id=" + instanceId;
        }
        return URL.valueOf(spec);
    }

    private static Invocation invocation(String serviceName) {
        Invocation invocation = mock(Invocation.class);
        when(invocation.getServiceName()).thenReturn(serviceName);
        return invocation;
    }

    @SuppressWarnings("deprecation")
    private static RpcInvocation realInvocation(String interfaceName, String protocolServiceKey) {
        RpcInvocation invocation = new RpcInvocation(
                "invoke", interfaceName, protocolServiceKey, new Class<?>[0], new Object[0]);
        assertThat(invocation.getServiceName()).isEqualTo(interfaceName);
        assertThat(invocation.getProtocolServiceKey()).isEqualTo(protocolServiceKey);
        return invocation;
    }
}
