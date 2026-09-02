package world.willfrog.agentlangchain.deployment;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/** 在 Spring 销毁线程池前标记普通进程关闭，使在手 Run 保留给同代际重启恢复。 */
@Component
public class AgentServiceShutdownState implements ApplicationListener<ContextClosedEvent> {

    private volatile boolean shuttingDown;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown = true;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }
}
