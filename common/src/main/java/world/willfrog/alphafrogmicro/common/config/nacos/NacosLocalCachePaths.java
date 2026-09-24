package world.willfrog.alphafrogmicro.common.config.nacos;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 Nacos 本地缓存文件按流量范围错开。
 *
 * <p>Beta 主环境和泳道如果挂同一份宿主目录，两边会轮流覆盖同一个
 * {@code agent-llm.local.json}。有 {@code AF_LANE_TRAFFIC_SCOPE_ID} 时，在同一目录下
 * 把文件名改成 {@code {范围}.{原文件名}}，主环境继续用原来的路径。不拆子目录：配置里
 * {@code file:} 引用的 prompt 相对路径以配置文件所在目录为根。</p>
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
        String name = path.getFileName().toString();
        String prefix = scope + ".";
        if (name.startsWith(prefix)) {
            return path.toString();
        }
        Path parent = path.getParent();
        Path isolated = parent == null ? Paths.get(prefix + name) : parent.resolve(prefix + name);
        return isolated.toString();
    }
}
