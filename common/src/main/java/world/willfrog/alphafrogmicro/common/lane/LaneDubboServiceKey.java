package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;

/**
 * Dubbo 调用的稳定服务身份，由分组、接口名和显式版本组成。
 *
 * <p>该身份对应 Dubbo 的 {@code targetServiceUniqueName}，不包含传输协议后缀，
 * 也不等同于 Nacos 的登记名称。入口固定绑定时保存这份身份，路由器只对完全相同
 * 的调用使用该绑定，避免同一接口的其他分组或版本误用入口已经选定的实例。</p>
 */
public record LaneDubboServiceKey(String group, String interfaceName, String version) {

    public LaneDubboServiceKey {
        group = normalizeOptional(group, "Dubbo 分组");
        interfaceName = requireSegment(interfaceName, "Dubbo 接口名");
        version = normalizeOptional(version, "Dubbo 版本");
    }

    public static LaneDubboServiceKey parse(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank() || !serviceKey.equals(serviceKey.strip())) {
            throw new IllegalArgumentException("Dubbo 服务键不能为空或包含首尾空白");
        }
        int slash = serviceKey.indexOf('/');
        if (slash != serviceKey.lastIndexOf('/')) {
            throw new IllegalArgumentException("Dubbo 服务键只能包含一个分组分隔符");
        }
        String group = slash < 0 ? "" : serviceKey.substring(0, slash);
        String interfaceAndVersion = slash < 0 ? serviceKey : serviceKey.substring(slash + 1);
        int colon = interfaceAndVersion.indexOf(':');
        if (colon != interfaceAndVersion.lastIndexOf(':')) {
            throw new IllegalArgumentException("Dubbo 服务键只能包含一个版本分隔符");
        }
        String interfaceName = colon < 0 ? interfaceAndVersion : interfaceAndVersion.substring(0, colon);
        String version = colon < 0 ? "" : interfaceAndVersion.substring(colon + 1);
        return new LaneDubboServiceKey(group, interfaceName, version);
    }

    public static boolean isValid(String serviceKey) {
        try {
            parse(serviceKey);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static LaneDubboServiceKey fromInvocation(URL routeUrl, Invocation invocation) {
        // Dubbo 的 InvocationUtil 在进入集群调用前把 URL.getServiceKey() 写入这个字段。
        // 它保留分组、接口与显式版本，但不会混入 getProtocolServiceKey() 的传输协议后缀。
        String targetServiceKey = invocation == null ? null : invocation.getTargetServiceUniqueName();
        if (targetServiceKey == null || targetServiceKey.isBlank()) {
            targetServiceKey = routeUrl == null ? null : routeUrl.getServiceKey();
        }
        if (targetServiceKey == null || targetServiceKey.isBlank()) {
            throw new LaneRouteFactsUncertainException();
        }
        final LaneDubboServiceKey parsed;
        try {
            parsed = parse(targetServiceKey);
        } catch (IllegalArgumentException invalid) {
            throw new LaneRouteFactsUncertainException();
        }
        String invocationInterface = invocation == null ? null : invocation.getServiceName();
        if (invocationInterface != null
                && !invocationInterface.isBlank()
                && !parsed.interfaceName().equals(invocationInterface)) {
            throw new LaneRouteFactsUncertainException();
        }
        return parsed;
    }

    public String value() {
        StringBuilder result = new StringBuilder();
        if (!group.isEmpty()) {
            result.append(group).append('/');
        }
        result.append(interfaceName);
        if (!version.isEmpty()) {
            result.append(':').append(version);
        }
        return result.toString();
    }

    /** Dubbo 3.3 接口级提供者写入 Nacos 的完整服务名称。 */
    public String interfaceLevelNacosServiceName() {
        return "providers:" + interfaceName + ':' + version + ':' + group;
    }

    private static String requireSegment(String value, String fieldName) {
        String normalized = normalizeOptional(value, fieldName);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, String fieldName) {
        if (value == null) {
            return "";
        }
        if (!value.equals(value.strip())
                || value.indexOf('/') >= 0
                || value.indexOf(':') >= 0
                || value.chars().anyMatch(Character::isISOControl)
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(fieldName + "格式不合法");
        }
        return value;
    }
}
