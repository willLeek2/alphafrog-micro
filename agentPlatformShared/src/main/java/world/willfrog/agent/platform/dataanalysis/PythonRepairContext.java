package world.willfrog.agent.platform.dataanalysis;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 当前 todo 内 Python 代码修复的运行时保护上下文。
 *
 * <p>durable 真相保存在 {@link ToolJobAnchor}；恢复 worker 把它投影到
 * {@code AgentContext}，供下一次 {@code executePython} 在容量准入和 Sandbox
 * 创建前拒绝已经失败过的相同请求。该对象本身不可变，不能作为跨进程真相源。</p>
 */
public record PythonRepairContext(
        int repairAttempt,
        List<String> failedRequestFingerprints) {

    public PythonRepairContext {
        if (repairAttempt < 0) {
            throw new IllegalArgumentException("repairAttempt must not be negative");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (failedRequestFingerprints != null) {
            for (String fingerprint : failedRequestFingerprints) {
                if (fingerprint != null && !fingerprint.isBlank()) {
                    normalized.add(fingerprint.trim());
                }
            }
        }
        failedRequestFingerprints = List.copyOf(normalized);
    }

    public boolean hasFailed(String requestFingerprint) {
        return requestFingerprint != null
                && failedRequestFingerprints.contains(requestFingerprint);
    }
}
