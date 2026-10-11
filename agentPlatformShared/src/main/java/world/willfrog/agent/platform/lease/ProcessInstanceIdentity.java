package world.willfrog.agent.platform.lease;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * 本进程的持有者标识：应用名 + 主机 + 进程号 + 本次启动的短标识。
 *
 * <p>最后那一段是关键：同机重启后进程号可能被复用，只按进程号认人会把新一代当成上一代，
 * 于是上一代留下的租约被自己「续上」，谁也接不了手。带上每次启动都不同的短标识，
 * 重启前后就是两位持有者。</p>
 */
@Component
public class ProcessInstanceIdentity {

    private final String value;

    public ProcessInstanceIdentity(
            @Value("${spring.application.name:unknown-app}") String applicationName) {
        this.value = applicationName + "@" + hostname() + "@" + ProcessHandle.current().pid()
                + "@" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 写进租约、写进日志的那一个标识。 */
    public String value() {
        return value;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
