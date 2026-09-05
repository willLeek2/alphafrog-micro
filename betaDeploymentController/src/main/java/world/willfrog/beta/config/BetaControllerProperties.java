package world.willfrog.beta.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("alphafrog.beta-controller")
public class BetaControllerProperties {
    private boolean enabled;
    private Path stateRoot = Path.of("/var/lib/alphafrog-beta");
    private Duration reconcileDelay = Duration.ofSeconds(2);
    private int applicationDrainSeconds = 60;
    private Path apiTokenFile = Path.of("/etc/alphafrog-beta/secrets/controller-api-token");
    private Path healthcheckScript = Path.of("/opt/alphafrog-beta/bin/tcp-healthcheck");
    private final Nacos nacos = new Nacos();
    private Map<String, Machine> machines = new LinkedHashMap<>();
    private Map<String, ServiceTemplate> services = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getStateRoot() { return stateRoot; }
    public void setStateRoot(Path stateRoot) { this.stateRoot = stateRoot; }
    public Duration getReconcileDelay() { return reconcileDelay; }
    public void setReconcileDelay(Duration reconcileDelay) { this.reconcileDelay = reconcileDelay; }
    public int getApplicationDrainSeconds() { return applicationDrainSeconds; }
    public void setApplicationDrainSeconds(int applicationDrainSeconds) {
        this.applicationDrainSeconds = applicationDrainSeconds;
    }
    public Path getApiTokenFile() { return apiTokenFile; }
    public void setApiTokenFile(Path apiTokenFile) { this.apiTokenFile = apiTokenFile; }
    public Path getHealthcheckScript() { return healthcheckScript; }
    public void setHealthcheckScript(Path healthcheckScript) { this.healthcheckScript = healthcheckScript; }
    public Nacos getNacos() { return nacos; }
    public Map<String, Machine> getMachines() { return machines; }
    public void setMachines(Map<String, Machine> machines) { this.machines = machines; }
    public Map<String, ServiceTemplate> getServices() { return services; }
    public void setServices(Map<String, ServiceTemplate> services) { this.services = services; }

    public static class Machine {
        private URI dockerHost;
        private String bindIp;
        private String routableAddress;
        public URI getDockerHost() { return dockerHost; }
        public void setDockerHost(URI dockerHost) { this.dockerHost = dockerHost; }
        public String getBindIp() { return bindIp; }
        public void setBindIp(String bindIp) { this.bindIp = bindIp; }
        public String getRoutableAddress() { return routableAddress; }
        public void setRoutableAddress(String routableAddress) { this.routableAddress = routableAddress; }
    }

    public static class ServiceTemplate {
        private Path envFile;
        private List<String> volumes = List.of();
        public Path getEnvFile() { return envFile; }
        public void setEnvFile(Path envFile) { this.envFile = envFile; }
        public List<String> getVolumes() { return volumes; }
        public void setVolumes(List<String> volumes) { this.volumes = volumes; }
    }

    public static class Nacos {
        private String serverAddress;
        private String namespace = "public";
        private String username;
        private String password;
        public String getServerAddress() { return serverAddress; }
        public void setServerAddress(String serverAddress) { this.serverAddress = serverAddress; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
