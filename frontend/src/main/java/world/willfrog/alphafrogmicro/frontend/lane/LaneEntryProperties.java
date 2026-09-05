package world.willfrog.alphafrogmicro.frontend.lane;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 入口流量范围配置。入口口令只用于比较，不得写入日志、响应或调用上下文。
 */
@ConfigurationProperties(prefix = "alphafrog.lane.entry")
public class LaneEntryProperties {

    private boolean enabled;
    private Set<String> testUsernames = new LinkedHashSet<>();
    private String passphrase = "";
    private String passphraseHeader = "X-AlphaFrog-Lane-Passphrase";
    private String trafficScopeId = "lane-test";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getTestUsernames() {
        return Set.copyOf(testUsernames);
    }

    public void setTestUsernames(Set<String> testUsernames) {
        this.testUsernames = testUsernames == null ? new LinkedHashSet<>() : new LinkedHashSet<>(testUsernames);
    }

    public String getPassphrase() {
        return passphrase;
    }

    public void setPassphrase(String passphrase) {
        this.passphrase = passphrase == null ? "" : passphrase;
    }

    public String getPassphraseHeader() {
        return passphraseHeader;
    }

    public void setPassphraseHeader(String passphraseHeader) {
        this.passphraseHeader = passphraseHeader;
    }

    public String getTrafficScopeId() {
        return trafficScopeId;
    }

    public void setTrafficScopeId(String trafficScopeId) {
        this.trafficScopeId = trafficScopeId;
    }

    public boolean hasUsableEntryConfiguration() {
        return enabled
                && passphrase != null
                && passphrase.length() >= 32
                && passphrase.equals(passphrase.strip())
                && passphrase.chars().noneMatch(Character::isISOControl)
                && trafficScopeId != null
                && !trafficScopeId.isBlank()
                && testUsernames.stream().anyMatch(username -> username != null && !username.isBlank());
    }

    public void validateStaticConfiguration() {
        if (passphraseHeader == null
                || !passphraseHeader.matches("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
                || "authorization".equalsIgnoreCase(passphraseHeader)) {
            throw new IllegalArgumentException("入口口令请求头名称不合法");
        }
    }
}
