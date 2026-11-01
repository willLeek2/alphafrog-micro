package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.URL;
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
            URL.valueOf("dubbo://10.0.0.8:20880/demo").addParameter(LaneContext.DUBBO_TAG_KEY, "lane-test");
    private static final URL UNTAGGED_PROVIDER = URL.valueOf("dubbo://10.0.0.9:20880/demo");

    @AfterEach
    void reset() {
        LaneContext.clear();
        MDC.clear();
        RpcContext.removeContext();
        RpcContext.removeServerContext();
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
