package world.willfrog.alphafrogmicro.frontend.lane;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import world.willfrog.alphafrogmicro.common.lane.LaneDubboServiceKey;

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
    private String identityServiceName = "agent-langchain-service";
    private String identityDubboServiceKey =
            "langchain/world.willfrog.alphafrogmicro.agent.idl.AgentDubboService";
    private URI controllerBaseUrl = URI.create("http://127.0.0.1:19090");
    private Path controllerApiTokenFile = Path.of("/etc/alphafrog-beta/secrets/controller-api-token");
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(2);
    private String localDeploymentId = "";
    private String localDeploymentGenerationId = "";

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

    public String getIdentityServiceName() {
        return identityServiceName;
    }

    public void setIdentityServiceName(String identityServiceName) {
        this.identityServiceName = identityServiceName;
    }

    public String getIdentityDubboServiceKey() {
        return identityDubboServiceKey;
    }

    public void setIdentityDubboServiceKey(String identityDubboServiceKey) {
        this.identityDubboServiceKey = identityDubboServiceKey;
    }

    public LaneDubboServiceKey resolvedIdentityDubboServiceKey() {
        return LaneDubboServiceKey.parse(identityDubboServiceKey);
    }

    public URI getControllerBaseUrl() {
        return controllerBaseUrl;
    }

    public void setControllerBaseUrl(URI controllerBaseUrl) {
        this.controllerBaseUrl = controllerBaseUrl;
    }

    public Path getControllerApiTokenFile() {
        return controllerApiTokenFile;
    }

    public void setControllerApiTokenFile(Path controllerApiTokenFile) {
        this.controllerApiTokenFile = controllerApiTokenFile;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public String getLocalDeploymentId() {
        return localDeploymentId;
    }

    public void setLocalDeploymentId(String localDeploymentId) {
        this.localDeploymentId = localDeploymentId == null ? "" : localDeploymentId;
    }

    public String getLocalDeploymentGenerationId() {
        return localDeploymentGenerationId;
    }

    public void setLocalDeploymentGenerationId(String localDeploymentGenerationId) {
        this.localDeploymentGenerationId = localDeploymentGenerationId == null ? "" : localDeploymentGenerationId;
    }

    public boolean hasUsableEntryConfiguration() {
        return enabled
                && passphrase != null
                && passphrase.length() >= 32
                && passphrase.equals(passphrase.strip())
                && passphrase.chars().noneMatch(Character::isISOControl)
                && trafficScopeId != null
                && !trafficScopeId.isBlank()
                && identityServiceName != null
                && !identityServiceName.isBlank()
                && LaneDubboServiceKey.isValid(identityDubboServiceKey)
                && testUsernames.stream().anyMatch(username -> username != null && !username.isBlank());
    }

    public void validateStaticConfiguration() {
        if (passphraseHeader == null
                || !passphraseHeader.matches("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
                || "authorization".equalsIgnoreCase(passphraseHeader)) {
            throw new IllegalArgumentException("入口口令请求头名称不合法");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " 必须大于零");
        }
    }
}
