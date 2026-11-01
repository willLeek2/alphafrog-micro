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
