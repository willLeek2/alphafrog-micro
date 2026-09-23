package world.willfrog.alphafrogmicro.common.config.nacos;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 Nacos 本地缓存文件按流量范围错开。
 *
 * <p>Beta 主环境和泳道如果挂同一份宿主目录，两边会轮流覆盖同一个
 * {@code agent-llm.local.json}。有 {@code AF_LANE_TRAFFIC_SCOPE_ID} 时，把文件放到
 * 该范围名的子目录里，主环境继续用原来的路径。</p>
 */
public final class NacosLocalCachePaths {

    private NacosLocalCachePaths() {
    }

    public static String isolate(String targetFile, String trafficScopeId) {
        if (targetFile == null || targetFile.isBlank()) {
            return targetFile;
        }
        if (trafficScopeId == null || trafficScopeId.isBlank()) {
            return targetFile;
        }
        String scope = trafficScopeId.trim();
        Path path = Paths.get(targetFile);
        Path parent = path.getParent();
        if (parent != null && scope.equals(parent.getFileName().toString())) {
            return path.toString();
        }
        Path isolated = parent == null
                ? Paths.get(scope).resolve(path.getFileName())
                : parent.resolve(scope).resolve(path.getFileName());
        return isolated.toString();
    }
}
