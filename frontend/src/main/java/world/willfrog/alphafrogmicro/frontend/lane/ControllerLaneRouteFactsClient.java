package world.willfrog.alphafrogmicro.frontend.lane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.lane.LaneCallBinding;
import world.willfrog.alphafrogmicro.common.lane.LaneEndpoint;
import world.willfrog.alphafrogmicro.common.lane.LaneDubboServiceKey;

/** 通过部署控制器只读状态接口取得流量范围的默认部署身份。 */
public final class ControllerLaneRouteFactsClient implements LaneRouteFactsFetcher {

    private static final Pattern PATH_SEGMENT = Pattern.compile("^[a-z0-9][a-z0-9-]{0,126}[a-z0-9]$|^[a-z0-9]$");

    private final LaneEntryProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ControllerLaneRouteFactsClient(LaneEntryProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    ControllerLaneRouteFactsClient(
            LaneEntryProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public Optional<LaneRouteFacts> fetch(String trafficScopeId, String serviceName) {
        requirePathSegment(trafficScopeId, "流量范围");
        requirePathSegment(serviceName, "服务名称");
        String token = readControllerToken(properties.getControllerApiTokenFile());
        URI endpoint = properties.getControllerBaseUrl().resolve(
                "/internal/beta/status/" + trafficScopeId + "/" + serviceName);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (isAuthoritativeNotFound(response.statusCode(), response.body())) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new LaneRouteFactsUnavailableException(
                        "部署控制器状态接口返回 HTTP " + response.statusCode());
            }
            return parseStatus(response.body(), trafficScopeId, serviceName);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LaneRouteFactsUnavailableException("读取部署控制器状态时线程被中断", exception);
        } catch (IOException exception) {
            throw new LaneRouteFactsUnavailableException("无法读取部署控制器状态", exception);
        }
    }

    boolean isAuthoritativeNotFound(int statusCode, String body) {
        if (statusCode == 404) {
            return true;
        }
        if (statusCode != 409) {
            return false;
        }
        try {
            String code = objectMapper.readTree(body).path("code").asText();
            return "STATUS_NOT_FOUND".equals(code) || "ROUTE_NOT_FOUND".equals(code);
        } catch (IOException exception) {
            return false;
        }
    }

    Optional<LaneRouteFacts> parseStatus(String body, String trafficScopeId, String serviceName) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!trafficScopeId.equals(root.path("trafficScopeId").asText())
                    || !serviceName.equals(root.path("serviceName").asText())) {
                return Optional.empty();
            }
            String phase = root.path("phase").asText();
            if ("FAILED".equals(phase) || "DELETING".equals(phase) || "DELETED".equals(phase)) {
                return Optional.empty();
            }
            JsonNode route = root.path("route");
            JsonNode active = root.path("activeInstance");
            if (!route.isObject() || !active.isObject()) {
                return Optional.empty();
            }
            String instanceId = text(active, "instanceId");
            String releaseId = text(active, "releaseId");
            String generationId = text(active, "deploymentGenerationId");
            if (!instanceId.equals(text(route, "defaultInstanceId"))
                    || !releaseId.equals(text(route, "defaultReleaseId"))
                    || !generationId.equals(text(route, "defaultDeploymentGenerationId"))) {
                return Optional.empty();
            }
            JsonNode registration = active.path("registration");
            if (!registration.isObject()
                    || !registration.path("enabled").asBoolean(false)
                    || !registration.path("healthy").asBoolean(false)
                    || registration.path("weight").asInt(0) != 1) {
                return Optional.empty();
            }
            String registrationServiceName = text(registration, "serviceName");
            LaneDubboServiceKey dubboServiceKey = LaneDubboServiceKey.parse(text(root, "dubboServiceKey"));
            if (!dubboServiceKey.equals(properties.resolvedIdentityDubboServiceKey())
                    || !registrationServiceName.equals(dubboServiceKey.interfaceLevelNacosServiceName())) {
                return Optional.empty();
            }
            JsonNode endpoint = active.path("endpoint");
            if (!endpoint.isObject()) {
                return Optional.empty();
            }
            String endpointAddress = text(endpoint, "address");
            int endpointPort = port(endpoint, "port");
            if (!endpointAddress.equals(text(registration, "ip"))
                    || endpointPort != port(registration, "port")) {
                return Optional.empty();
            }
            JsonNode metadata = registration.path("metadata");
            if (!trafficScopeId.equals(metadata.path("alphafrog.traffic-scope-id").asText())
                    || !instanceId.equals(metadata.path("alphafrog.instance-id").asText())
                    || !releaseId.equals(metadata.path("alphafrog.release-id").asText())
                    || !generationId.equals(metadata.path("alphafrog.deployment-generation-id").asText())) {
                return Optional.empty();
            }
            DeploymentIdentity identity = new DeploymentIdentity(text(root, "deploymentId"), generationId);
            long routeVersion = route.path("routeVersion").asLong(-1);
            LaneCallBinding callBinding = new LaneCallBinding(
                    trafficScopeId,
                    serviceName,
                    instanceId,
                    releaseId,
                    generationId,
                    routeVersion,
                    new LaneEndpoint(endpointAddress, endpointPort));
            return Optional.of(new LaneRouteFacts(
                    trafficScopeId,
                    serviceName,
                    identity,
                    dubboServiceKey,
                    registrationServiceName,
                    callBinding,
                    root.path("stateVersion").asLong(-1)));
        } catch (IllegalArgumentException | IOException exception) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " 不能为空或包含首尾空白");
        }
        return value;
    }

    private static int port(JsonNode node, String field) {
        int value = node.path(field).asInt(-1);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(field + " 不是合法端口");
        }
        return value;
    }

    private static void requirePathSegment(String value, String name) {
        if (value == null || !PATH_SEGMENT.matcher(value).matches()) {
            throw new LaneRouteFactsUnavailableException(name + "格式不合法");
        }
    }

    private static String readControllerToken(Path tokenFile) {
        try {
            if (tokenFile == null || !Files.isRegularFile(tokenFile) || Files.isSymbolicLink(tokenFile)) {
                throw new LaneRouteFactsUnavailableException("部署控制器凭证文件不存在或不安全");
            }
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tokenFile);
                if (permissions.stream().anyMatch(permission -> permission != PosixFilePermission.OWNER_READ
                        && permission != PosixFilePermission.OWNER_WRITE)) {
                    throw new LaneRouteFactsUnavailableException("部署控制器凭证文件权限不安全");
                }
            } catch (UnsupportedOperationException ignored) {
                // 不支持 POSIX 权限的平台仍保留普通文件和符号链接检查。
            }
            String token = Files.readString(tokenFile);
            if (token.length() < 32
                    || !token.equals(token.strip())
                    || token.chars().anyMatch(Character::isISOControl)) {
                throw new LaneRouteFactsUnavailableException("部署控制器凭证内容不合法");
            }
            return token;
        } catch (IOException exception) {
            throw new LaneRouteFactsUnavailableException("无法读取部署控制器凭证文件", exception);
        }
    }
}
