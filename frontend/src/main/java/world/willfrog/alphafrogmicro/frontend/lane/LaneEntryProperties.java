package world.willfrog.alphafrogmicro.frontend.lane;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 入口流量范围配置。{@code traffic-scope-id} 是部署控制器注入的本实例泳道名：
 * 只有泳道自己的 frontend 实例会被注入非空值；作为共用入口的主 Beta frontend 保持为空，
 * 改读请求头指定的泳道名。
 */
@ConfigurationProperties(prefix = "alphafrog.lane.entry")
public class LaneEntryProperties {

    private boolean enabled;
    private String trafficScopeId = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTrafficScopeId() {
        return trafficScopeId;
    }

    public void setTrafficScopeId(String trafficScopeId) {
        this.trafficScopeId = trafficScopeId == null ? "" : trafficScopeId;
    }
}
