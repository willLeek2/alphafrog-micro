package world.willfrog.agent.platform.finance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Application defaults and immutable code-side ceilings for the finance record channel. */
@ConfigurationProperties(prefix = "agent.finance-record-channel")
public class FinanceRecordChannelProperties {

    public static final int HARD_RECORD_COUNT_MAX = 512;
    public static final int HARD_RECORD_MAX_BYTES = 65_536;
    public static final int HARD_RECORD_CHANNEL_MAX_BYTES = 1_048_576;
    public static final int HARD_STDOUT_MAX_BYTES = 4_194_304;
    public static final int HARD_STDERR_MAX_BYTES = 1_048_576;

    private boolean enabled;
    private int recordCountMax = 128;
    private int recordMaxBytes = 16_384;
    private int recordChannelMaxBytes = 262_144;
    private int stdoutMaxBytes = 1_048_576;
    private int stderrMaxBytes = 262_144;
    private String configFile = "";
    private TargetEnvironment targetEnvironment = new TargetEnvironment();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getRecordCountMax() { return recordCountMax; }
    public void setRecordCountMax(int recordCountMax) { this.recordCountMax = recordCountMax; }
    public int getRecordMaxBytes() { return recordMaxBytes; }
    public void setRecordMaxBytes(int recordMaxBytes) { this.recordMaxBytes = recordMaxBytes; }
    public int getRecordChannelMaxBytes() { return recordChannelMaxBytes; }
    public void setRecordChannelMaxBytes(int recordChannelMaxBytes) { this.recordChannelMaxBytes = recordChannelMaxBytes; }
    public int getStdoutMaxBytes() { return stdoutMaxBytes; }
    public void setStdoutMaxBytes(int stdoutMaxBytes) { this.stdoutMaxBytes = stdoutMaxBytes; }
    public int getStderrMaxBytes() { return stderrMaxBytes; }
    public void setStderrMaxBytes(int stderrMaxBytes) { this.stderrMaxBytes = stderrMaxBytes; }
    public String getConfigFile() { return configFile; }
    public void setConfigFile(String configFile) { this.configFile = configFile; }
    public TargetEnvironment getTargetEnvironment() { return targetEnvironment; }
    public void setTargetEnvironment(TargetEnvironment targetEnvironment) {
        this.targetEnvironment = targetEnvironment == null ? new TargetEnvironment() : targetEnvironment;
    }

    public static class TargetEnvironment {
        private String environmentId = "";
        private String imageDigest = "";
        private String librarySetDigest = "";
        private List<PackageApi> packageApis = new ArrayList<>();

        public String getEnvironmentId() { return environmentId; }
        public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
        public String getImageDigest() { return imageDigest; }
        public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }
        public String getLibrarySetDigest() { return librarySetDigest; }
        public void setLibrarySetDigest(String librarySetDigest) { this.librarySetDigest = librarySetDigest; }
        public List<PackageApi> getPackageApis() { return packageApis; }
        public void setPackageApis(List<PackageApi> packageApis) {
            this.packageApis = packageApis == null ? new ArrayList<>() : new ArrayList<>(packageApis);
        }
    }

    public static class PackageApi {
        private String name = "";
        private String version = "";
        private String apiVersion = "";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    }
}
