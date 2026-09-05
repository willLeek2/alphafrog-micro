package world.willfrog.agentlangchain.deployment;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/** 使用 Beta 逻辑注册分组中的实例元数据核对一个部署代际是否仍存活。 */
@Component
@ConditionalOnProperty(prefix = "agent.langchain.generation-reaper", name = "enabled", havingValue = "true")
public class NacosDeploymentGenerationLivenessProbe implements DeploymentGenerationLivenessProbe {

    private static final String DEPLOYMENT_ID = "alphafrog.deployment-id";
    private static final String GENERATION_ID = "alphafrog.deployment-generation-id";

    private final NamingService namingService;
    private final String groupName;
    private final int servicePageSize;

    public NacosDeploymentGenerationLivenessProbe(
            @Value("${agent.langchain.generation-reaper.nacos.server-address}") String serverAddress,
            @Value("${agent.langchain.generation-reaper.nacos.namespace:public}") String namespace,
            @Value("${agent.langchain.generation-reaper.nacos.username:${AF_CONFIG_NACOS_USERNAME:}}")
            String username,
            @Value("${agent.langchain.generation-reaper.nacos.password:${AF_CONFIG_NACOS_PASSWORD:}}")
            String password,
            @Value("${agent.langchain.generation-reaper.nacos.group-name:alphafrog-beta}") String groupName,
            @Value("${agent.langchain.generation-reaper.nacos.service-page-size:100}") int servicePageSize)
            throws NacosException {
        Properties properties = new Properties();
        properties.setProperty("serverAddr", requireText(serverAddress, "server-address"));
        properties.setProperty("namespace", normalizeNamespace(namespace));
        setIfPresent(properties, "username", username);
        setIfPresent(properties, "password", password);
        this.namingService = NacosFactory.createNamingService(properties);
        this.groupName = requireText(groupName, "group-name");
        this.servicePageSize = Math.max(1, Math.min(servicePageSize, 1000));
    }

    NacosDeploymentGenerationLivenessProbe(NamingService namingService, String groupName,
                                           int servicePageSize) {
        this.namingService = namingService;
        this.groupName = requireText(groupName, "group-name");
        this.servicePageSize = Math.max(1, Math.min(servicePageSize, 1000));
    }

    @Override
    public boolean hasLiveInstance(DeploymentIdentity identity) {
        try {
            int page = 1;
            while (true) {
                ListView<String> services = namingService.getServicesOfServer(page, servicePageSize, groupName);
                List<String> names = services.getData() == null ? List.of() : services.getData();
                for (String serviceName : names) {
                    List<Instance> instances = namingService.getAllInstances(serviceName, groupName, false);
                    if (instances.stream().anyMatch(instance -> matches(instance.getMetadata(), identity))) {
                        return true;
                    }
                }
                if (names.isEmpty() || page * servicePageSize >= services.getCount()) {
                    return false;
                }
                page++;
            }
        } catch (NacosException exception) {
            throw new IllegalStateException("无法核对部署代际的 Nacos 存活现场", exception);
        }
    }

    private boolean matches(Map<String, String> metadata, DeploymentIdentity identity) {
        return metadata != null
                && identity.deploymentId().equals(metadata.get(DEPLOYMENT_ID))
                && identity.generationId().equals(metadata.get(GENERATION_ID));
    }

    @PreDestroy
    void close() {
        try {
            namingService.shutDown();
        } catch (NacosException ignored) {
            // 进程已经在关闭，客户端释放失败不能覆盖 Run 的持久化收尾结果。
        }
    }

    private static String normalizeNamespace(String value) {
        return value == null || value.isBlank() ? "public" : value;
    }

    private static void setIfPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("generation-reaper " + name + " 不能为空");
        }
        return value.trim();
    }
}
