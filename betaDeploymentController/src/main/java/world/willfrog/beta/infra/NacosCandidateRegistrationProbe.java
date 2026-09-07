package world.willfrog.beta.infra;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.CandidateRegistrationProbe;
import world.willfrog.beta.core.ControllerException;

/**
 * 只读确认候选端点已在 Nacos 中可见。
 */
@Component
@ConditionalOnProperty(prefix = "alphafrog.beta-controller", name = "enabled", havingValue = "true")
public class NacosCandidateRegistrationProbe implements CandidateRegistrationProbe {

    private final NamingService naming;
    private final String namespace;

    @Autowired
    public NacosCandidateRegistrationProbe(BetaControllerProperties properties) {
        try {
            BetaControllerProperties.Nacos source = properties.getNacos();
            if (source.getServerAddress() == null || source.getServerAddress().isBlank()) {
                throw new ControllerException("NACOS_CONFIG_INVALID", "Nacos server address is required");
            }
            Properties values = new Properties();
            values.setProperty("serverAddr", source.getServerAddress());
            namespace = normalizeNamespace(source.getNamespace());
            values.setProperty("namespace", namespace);
            if (source.getUsername() != null && !source.getUsername().isBlank()) {
                values.setProperty("username", source.getUsername());
            }
            if (source.getPassword() != null && !source.getPassword().isBlank()) {
                values.setProperty("password", source.getPassword());
            }
            naming = NacosFactory.createNamingService(values);
        } catch (NacosException exception) {
            throw new ControllerException("NACOS_START_FAILED", "Unable to initialize the Nacos client", exception);
        }
    }

    NacosCandidateRegistrationProbe(NamingService naming, String namespace) {
        this.naming = naming;
        this.namespace = normalizeNamespace(namespace);
    }

    @Override
    public boolean isVisible(JsonNode service, String address, int port) {
        JsonNode registration = service.path("registration");
        if (!namespace.equals(normalizeNamespace(registration.path("namespaceId").asText()))) {
            throw new ControllerException(
                    "NACOS_NAMESPACE_MISMATCH",
                    "Manifest namespace differs from the configured Nacos namespace");
        }
        try {
            List<Instance> instances = naming.getAllInstances(
                    registration.path("serviceName").asText(),
                    registration.path("groupName").asText(),
                    List.of(registration.path("clusterName").asText()),
                    false);
            return instances.stream().anyMatch(instance ->
                    address.equals(instance.getIp())
                            && port == instance.getPort()
                            && instance.isEnabled()
                            && instance.isHealthy()
                            && instance.getWeight() > 0.0d);
        } catch (NacosException exception) {
            throw new ControllerException(
                    "NACOS_QUERY_FAILED",
                    "Unable to confirm the candidate self-registration",
                    exception);
        }
    }

    @PreDestroy
    void close() {
        try {
            naming.shutDown();
        } catch (NacosException ignored) {
            // 服务实例的注册心跳由各服务进程持有，控制器查询客户端关闭不影响服务可用性。
        }
    }

    private static String normalizeNamespace(String value) {
        return value == null || value.isBlank() ? "public" : value;
    }
}
