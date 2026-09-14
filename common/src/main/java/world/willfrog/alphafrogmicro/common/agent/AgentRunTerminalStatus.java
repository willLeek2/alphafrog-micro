package world.willfrog.alphafrogmicro.common.agent;

import java.util.Locale;
import java.util.Set;

/**
 * Agent Run 对外终态的共享归一入口。
 *
 * <p>持久层只写五个规范值；旧客户端和旧任务留下的英式拼写、超时别名只在边界读取时
 * 归一。{@code CANCELING} 等过渡态不会被误判为终态。</p>
 */
public final class AgentRunTerminalStatus {

    public static final Set<String> CANONICAL_TERMINAL_STATUSES = Set.of(
            "COMPLETED", "PARTIAL", "FAILED", "CANCELED", "EXPIRED"
    );

    private AgentRunTerminalStatus() {
    }

    /**
     * 返回规范化状态；非终态也会统一 trim/大写，空值返回 {@code null}。
     */
    public static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CANCELLED" -> "CANCELED";
            case "TIMEOUT", "TIMED_OUT" -> "EXPIRED";
            default -> normalized;
        };
    }

    public static boolean isTerminal(String status) {
        String normalized = normalize(status);
        return normalized != null && CANONICAL_TERMINAL_STATUSES.contains(normalized);
    }
}
