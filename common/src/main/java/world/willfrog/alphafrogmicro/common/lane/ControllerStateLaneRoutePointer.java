package world.willfrog.alphafrogmicro.common.lane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 每次查找都重新读取 {@code controller-state.json} 的完整内容。
 *
 * <p>读失败、文件缺失或 JSON 无法解析时返回空表，由 {@link LaneCallRouter} 失败关闭。
 * 这里不保留上一份成功快照。</p>
 */
public final class ControllerStateLaneRoutePointer implements LaneRoutePointer {

    public static final String DEFAULT_STATE_FILE = "/var/lib/alphafrog-beta/controller-state.json";

    private final ObjectMapper objectMapper;
    private final Path stateFile;

    public ControllerStateLaneRoutePointer(ObjectMapper objectMapper, Path stateFile) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
    }

    @Override
    public LaneRouteTable current() {
        if (!Files.isRegularFile(stateFile)) {
            return LaneRouteTable.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(stateFile);
            JsonNode root = objectMapper.readTree(bytes);
            return parse(root);
        } catch (LaneRouteFactsUncertainException uncertain) {
            throw uncertain;
        } catch (IOException | RuntimeException ignored) {
            return LaneRouteTable.empty();
        }
    }

    static LaneRouteTable parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return LaneRouteTable.empty();
        }
        JsonNode deployments = root.get("deployments");
        if (deployments == null || !deployments.isArray()) {
            return LaneRouteTable.empty();
        }
        List<LaneServiceRoute> routes = new ArrayList<>();
        for (JsonNode deployment : deployments) {
            String trafficScopeId = text(deployment, "trafficScopeId");
            JsonNode services = deployment.get("services");
            if (trafficScopeId == null || services == null || !services.isArray()) {
                continue;
            }
            for (JsonNode service : services) {
                LaneServiceRoute route = parseService(trafficScopeId, service);
                if (route != null) {
                    routes.add(route);
                }
            }
        }
        return LaneRouteTable.of(routes);
    }

    private static LaneServiceRoute parseService(String trafficScopeId, JsonNode service) {
        String serviceName = text(service, "serviceName");
        String dubboServiceKeyValue = text(service, "dubboServiceKey");
        JsonNode routeNode = service.get("route");
        if (serviceName == null || routeNode == null || !routeNode.isObject()) {
            return null;
        }
        if (dubboServiceKeyValue == null) {
            throw new LaneRouteFactsUncertainException();
        }
        final LaneDubboServiceKey dubboServiceKey;
        try {
            dubboServiceKey = LaneDubboServiceKey.parse(dubboServiceKeyValue);
        } catch (IllegalArgumentException invalid) {
            throw new LaneRouteFactsUncertainException();
        }
        String defaultInstanceId = text(routeNode, "defaultInstanceId");
        if (defaultInstanceId == null) {
            return null;
        }
        String defaultReleaseId = text(routeNode, "defaultReleaseId");
        String defaultGenerationId = text(routeNode, "defaultDeploymentGenerationId");
        JsonNode instance = findInstance(service, defaultInstanceId);
        if (instance == null || defaultReleaseId == null || defaultGenerationId == null) {
            throw new LaneRouteFactsUncertainException();
        }
        String instanceReleaseId = text(instance, "releaseId");
        String instanceGenerationId = text(instance, "deploymentGenerationId");
        if (!defaultReleaseId.equals(instanceReleaseId)
                || !defaultGenerationId.equals(instanceGenerationId)) {
            throw new LaneRouteFactsUncertainException();
        }
        JsonNode endpointNode = instance.get("endpoint");
        if (endpointNode == null || !endpointNode.isObject()) {
            throw new LaneRouteFactsUncertainException();
        }
        String address = text(endpointNode, "address");
        JsonNode portNode = endpointNode.get("port");
        if (address == null || portNode == null || !portNode.canConvertToInt()) {
            throw new LaneRouteFactsUncertainException();
        }
        String registrationServiceName = null;
        JsonNode registration = instance.get("registration");
        if (registration != null && registration.isObject()) {
            registrationServiceName = text(registration, "serviceName");
        }
        if (!dubboServiceKey.interfaceLevelNacosServiceName().equals(registrationServiceName)) {
            throw new LaneRouteFactsUncertainException();
        }
        long routeVersion = routeNode.path("routeVersion").asLong(0L);
        return new LaneServiceRoute(
                trafficScopeId,
                serviceName,
                dubboServiceKey,
                registrationServiceName,
                defaultInstanceId,
                defaultReleaseId,
                defaultGenerationId,
                routeVersion,
                text(routeNode, "updatedAt"),
                new LaneEndpoint(address, portNode.intValue()));
    }

    private static JsonNode findInstance(JsonNode service, String instanceId) {
        for (String field : List.of("activeInstance", "candidateInstance", "drainingInstance")) {
            JsonNode instance = service.get(field);
            if (instance != null && instance.isObject() && instanceId.equals(text(instance, "instanceId"))) {
                return instance;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
