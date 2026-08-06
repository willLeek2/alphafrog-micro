package world.willfrog.agent.platform.util;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Classpath 文本文件加载工具（prompts / tool description 共用）。
 *
 * <p>agentToolsShared 和 agentPlatformShared 都需要从 classpath 加载长文本配置；
 * {@link world.willfrog.agent.platform.service.AgentPromptService} 的
 * {@code loadPromptFileFromClasspath} 历史上是 {@code private}，agentToolsShared 复用不到。
 * 这里提到公共 util，两个 jar 都可调用。</p>
 *
 * <p>读取失败不抛异常（按 warn 级别打日志返回空串）——prompt / tool description 缺失
 * 不应该让整个 agent run 崩溃，调用方拿到空串可以做默认行为兜底。</p>
 */
@Slf4j
public final class PromptFileLoader {

    private PromptFileLoader() {
    }

    /**
     * 从 classpath 加载文本文件内容。
     *
     * @param classpathPath 相对 classloader 根的路径，如 {@code prompts/python/execute_python_tool_description.txt}
     * @return UTF-8 文本内容；不存在或读取失败时返回空串
     */
    public static String load(String classpathPath) {
        if (classpathPath == null || classpathPath.isBlank()) {
            return "";
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = PromptFileLoader.class.getClassLoader();
        }
        try (InputStream is = cl.getResourceAsStream(classpathPath)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            log.warn("Prompt/tool description classpath resource not found: {}", classpathPath);
        } catch (Exception e) {
            log.warn("Failed to load classpath resource {}: {}", classpathPath, e.getMessage());
        }
        return "";
    }
}
