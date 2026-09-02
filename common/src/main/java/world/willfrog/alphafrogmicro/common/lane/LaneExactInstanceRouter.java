package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Router;

import java.util.ArrayList;
import java.util.List;

/**
 * 带隔离范围的新调用只保留可信绑定指向的那一个实例。
 *
 * <p>匹配要求实例标识、地址和端口同时与当前绑定一致；缺少实例标识或多个记录同时命中
 * 都视为路由事实不确定。入口已经固定的目标调用优先使用请求级绑定，其他调用各自读取当前指针。
 * {@link #isRuntime()} 为 true，所以不会把过滤结果缓存到下一次调用。匹配失败时抛错，绝不把
 * 原来的未过滤列表交回去。</p>
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
        String scope = LaneContext.trafficScopeId();
        if (scope == null || scope.isBlank()) {
            return invokers;
        }
        try {
            String registrationName = registrationName(url, invocation);
            LaneCallBinding binding = LaneCallBindingContext.find(scope, registrationName);
            if (binding == null) {
                if (!LaneRoutingSupport.enabled()) {
                    return invokers;
                }
                binding = LaneRoutingSupport.router().bindNewCallByRegistration(scope, registrationName);
            }
            List<Invoker<T>> matched = new ArrayList<>();
            if (invokers != null) {
                for (Invoker<T> invoker : invokers) {
                    if (invoker != null && matches(invoker.getUrl(), binding)) {
                        matched.add(invoker);
                    }
                }
            }
            if (matched.size() != 1) {
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

    private static String registrationName(URL url, Invocation invocation) {
        // Dubbo 的 serviceName 只有接口名；protocolServiceKey 保留分组和版本，
        // 只需去掉 Nacos 名称末尾的 @@providers 就能做精确匹配。
        if (invocation != null
                && invocation.getProtocolServiceKey() != null
                && !invocation.getProtocolServiceKey().isBlank()) {
            return invocation.getProtocolServiceKey();
        }
        if (url != null) {
            String serviceKey = url.getServiceKey();
            if (serviceKey != null && !serviceKey.isBlank()) {
                return serviceKey;
            }
        }
        if (invocation != null && invocation.getServiceName() != null && !invocation.getServiceName().isBlank()) {
            return invocation.getServiceName();
        }
        return url == null ? "" : url.getPath();
    }

    static boolean matches(URL invokerUrl, LaneCallBinding binding) {
        if (invokerUrl == null || binding == null) {
            return false;
        }
        String instanceId = invokerUrl.getParameter("alphafrog.instance-id");
        if (instanceId == null || instanceId.isBlank()) {
            return false;
        }
        return binding.instanceId().equals(instanceId)
                && binding.matches(invokerUrl.getHost(), invokerUrl.getPort());
    }
}
