package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Router;

import java.util.ArrayList;
import java.util.List;

/**
 * 带隔离范围的新调用只保留指针指向的那一个实例。
 *
 * <p>{@link #isRuntime()} 为 true，所以不会把过滤结果缓存到下一次调用。匹配失败时抛错，
 * 绝不把原来的未过滤列表交回去。</p>
 */
public final class LaneExactInstanceRouter implements Router {

    private final URL url;

    public LaneExactInstanceRouter(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        if (!LaneRoutingSupport.enabled()) {
            return invokers;
        }
        String scope = LaneContext.trafficScopeId();
        if (scope == null || scope.isBlank()) {
            return invokers;
        }
        try {
            LaneCallBinding binding = bind(url, invocation);
            List<Invoker<T>> matched = new ArrayList<>();
            if (invokers != null) {
                for (Invoker<T> invoker : invokers) {
                    if (invoker != null && matches(invoker.getUrl(), binding)) {
                        matched.add(invoker);
                    }
                }
            }
            if (matched.isEmpty()) {
                throw new RpcException(
                        RpcException.FORBIDDEN_EXCEPTION,
                        LaneRouteFactsUncertainException.CODE);
            }
            return matched;
        } catch (LaneRouteUnavailableException unavailable) {
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, unavailable.getMessage(), unavailable);
        } catch (LaneRouteFactsUncertainException uncertain) {
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, uncertain.getMessage(), uncertain);
        }
    }

    @Override
    public boolean isRuntime() {
        return true;
    }

    @Override
    public boolean isForce() {
        return true;
    }

    @Override
    public int getPriority() {
        return -10_000;
    }

    private static LaneCallBinding bind(URL url, Invocation invocation) {
        String registrationName = registrationName(url, invocation);
        return LaneRoutingSupport.router().bindNewCallByRegistration(
                LaneContext.trafficScopeId(),
                registrationName);
    }

    private static String registrationName(URL url, Invocation invocation) {
        if (invocation != null && invocation.getServiceName() != null && !invocation.getServiceName().isBlank()) {
            return invocation.getServiceName();
        }
        if (url == null) {
            return "";
        }
        String serviceKey = url.getServiceKey();
        if (serviceKey != null && !serviceKey.isBlank()) {
            return serviceKey;
        }
        return url.getPath();
    }

    static boolean matches(URL invokerUrl, LaneCallBinding binding) {
        if (invokerUrl == null || binding == null) {
            return false;
        }
        String host = invokerUrl.getHost();
        int port = invokerUrl.getPort();
        if (binding.matches(host, port)) {
            return true;
        }
        String instanceId = invokerUrl.getParameter("alphafrog.instance-id");
        return binding.instanceId().equals(instanceId);
    }
}
