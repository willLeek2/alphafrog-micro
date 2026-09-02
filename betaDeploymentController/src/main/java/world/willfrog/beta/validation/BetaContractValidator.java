package world.willfrog.beta.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import world.willfrog.beta.core.ControllerException;
import world.willfrog.beta.core.JsonSupport;

@Component
public class BetaContractValidator {
    private final ObjectMapper mapper;
    private final JsonSchema manifestSchema;
    private final JsonSchema stateSchema;
    private final JsonSchema formatProbeSchema;

    public BetaContractValidator(ObjectMapper mapper) {
        this.mapper = mapper;
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        manifestSchema = load(factory, config, "/META-INF/alphafrog/beta/manifest.schema.json");
        stateSchema = load(factory, config, "/META-INF/alphafrog/beta/controller-state.schema.json");
        try {
            formatProbeSchema = factory.getSchema(mapper.readTree("""
                    {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                     "additionalProperties":false,"required":["ip","time"],"properties":{
                       "ip":{"oneOf":[{"type":"string","format":"ipv4"},{"type":"string","format":"ipv6"}]},
                       "time":{"type":"string","format":"date-time","pattern":"Z$"}}}
                    """), config);
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to build the format assertion probe", impossible);
        }
        formatSelfTest();
    }

    public void validateManifest(JsonNode manifest) {
        validate(manifestSchema, manifest, "MANIFEST_INVALID");
        if (!manifest.path("expiresAt").asText().endsWith("Z")
                || !manifest.path("createdAt").asText().endsWith("Z")
                || !Instant.parse(manifest.path("expiresAt").asText())
                    .isAfter(Instant.parse(manifest.path("createdAt").asText()))) {
            throw new ControllerException("MANIFEST_INVALID", "Deployment expiry must be after creation time");
        }
        Set<String> names = new HashSet<>();
        Set<String> ports = new HashSet<>();
        for (JsonNode service : manifest.path("services")) {
            String name = service.path("serviceName").asText();
            if (!names.add(name)) throw new ControllerException("MANIFEST_INVALID", "Duplicate service " + name);
            if (!JsonSupport.serviceSha256(mapper, service).equals(service.path("serviceSpecSha256").asText())) {
                throw new ControllerException("MANIFEST_INVALID", "Service digest mismatch for " + name);
            }
            if (service.path("runtime").path("applicationDrainSeconds").asInt() + 5
                    > service.path("runtime").path("drainGraceSeconds").asInt()) {
                throw new ControllerException("MANIFEST_INVALID", "Drain reserve is too small for " + name);
            }
            for (JsonNode port : service.path("runtime").path("hostPorts")) {
                String key = service.path("machineId").asText() + ':' + port.asInt();
                if (!ports.add(key)) throw new ControllerException("MANIFEST_INVALID", "Reserved port collision " + key);
            }
        }
    }

    public void validateState(JsonNode state) { validate(stateSchema, state, "STATE_INVALID"); }

    public void validatePair(JsonNode manifest, JsonNode state) {
        validateManifest(manifest);
        validateState(state);
        Set<String> instanceIds = new HashSet<>();
        Set<String> containerIds = new HashSet<>();
        int operations = 0;
        for (JsonNode deployment : state.path("deployments")) {
            if (!deployment.path("deploymentId").asText().equals(manifest.path("deploymentId").asText())) continue;
            if (deployment.path("acceptedManifestVersion").asLong() != manifest.path("manifestVersion").asLong()
                    || !deployment.path("manifestSha256").asText().equals(JsonSupport.sha256(mapper, manifest))) {
                throw new ControllerException("STATE_MANIFEST_MISMATCH", "State does not identify the accepted manifest");
            }
            if (!deployment.path("trafficScopeId").equals(manifest.path("trafficScopeId"))
                    || !deployment.path("gitCommit").equals(manifest.path("gitCommit"))
                    || !deployment.path("owner").equals(manifest.path("owner"))
                    || !deployment.path("expiresAt").equals(manifest.path("expiresAt")))
                throw new ControllerException("STATE_MANIFEST_MISMATCH", "Deployment metadata differs from the accepted manifest");
            for (JsonNode service : deployment.path("services")) {
                JsonNode spec = findService(manifest, service.path("serviceName").asText());
                if (spec == null || service.path("targetManifestVersion").asLong() != manifest.path("manifestVersion").asLong()
                        || !service.path("targetServiceSpecSha256").equals(spec.path("serviceSpecSha256")))
                    throw new ControllerException("STATE_MANIFEST_MISMATCH", "Service target differs from the accepted manifest");
                if (!service.path("operation").isNull()) operations++;
                for (String role : new String[]{"activeInstance", "candidateInstance", "drainingInstance"}) {
                    JsonNode instance = service.path(role);
                    if (instance.isMissingNode() || instance.isNull()) continue;
                    if (!instanceIds.add(instance.path("instanceId").asText()))
                        throw new ControllerException("STATE_INVALID", "Duplicate instance identifier");
                    if (!containerIds.add(instance.path("containerId").asText()))
                        throw new ControllerException("STATE_INVALID", "Duplicate container identifier");
                    JsonNode registration = instance.path("registration");
                    if (registration.path("port").asInt() != instance.path("hostPort").asInt()
                            || !registration.path("ip").equals(instance.path("endpoint").path("address"))
                            || registration.path("port").asInt() != instance.path("endpoint").path("port").asInt()
                            || !registration.path("ephemeral").asBoolean()
                            || !registration.path("metadata").equals(expectedMetadata(deployment, instance))) {
                        throw new ControllerException("STATE_INVALID", "Instance registration identity mismatch");
                    }
                    String slot = instance.path("portSlot").asText();
                    int expectedPort = spec.path("runtime").path("hostPorts").path("A".equals(slot) ? 0 : 1).asInt();
                    if (!instance.path("machineId").equals(spec.path("machineId"))
                            || instance.path("hostPort").asInt() != expectedPort)
                        throw new ControllerException("STATE_INVALID", "Instance does not use its reserved machine and port slot");
                }
                JsonNode candidate = service.path("candidateInstance");
                if (candidate.isObject()) {
                    requireTargetInstance(candidate, manifest, spec);
                    if ((service.path("operation").isObject()
                            && !candidate.path("instanceId").equals(service.path("operation").path("candidateInstanceId")))
                            || candidate.path("registration").path("enabled").asBoolean()
                            || candidate.path("registration").path("weight").asInt() != 0)
                        throw new ControllerException("STATE_INVALID", "Candidate identity or selectable state is invalid");
                }
                JsonNode active = service.path("activeInstance");
                JsonNode route = service.path("route");
                if (active.isObject() && (!route.path("defaultInstanceId").equals(active.path("instanceId"))
                        || !route.path("defaultReleaseId").equals(active.path("releaseId"))
                        || !route.path("defaultDeploymentGenerationId").equals(active.path("deploymentGenerationId")))) {
                    throw new ControllerException("STATE_INVALID", "Stable route does not point to its active instance");
                }
                if (active.isObject() && ("STABLE".equals(service.path("phase").asText())
                        && active.path("manifestVersion").asLong() == manifest.path("manifestVersion").asLong()
                        || "DRAINING_PREVIOUS".equals(service.path("operation").path("phase").asText())))
                    requireTargetInstance(active, manifest, spec);
                JsonNode draining = service.path("drainingInstance");
                if (draining.isObject()) {
                    if (!draining.path("trafficRemovedAt").equals(route.path("updatedAt")))
                        throw new ControllerException("STATE_INVALID", "Traffic removal time differs from the route switch time");
                    if (!draining.path("preStopCompletedAt").isNull()
                            && (draining.path("registration").path("enabled").asBoolean()
                            || draining.path("registration").path("weight").asInt() != 0))
                        throw new ControllerException("STATE_INVALID", "A pre-stopped instance is still selectable");
                }
            }
        }
        if (operations > 1) throw new ControllerException("STATE_INVALID", "Only one deployment operation may run at a time");
    }

    private void requireTargetInstance(JsonNode instance, JsonNode manifest, JsonNode spec) {
        if (instance.path("manifestVersion").asLong() != manifest.path("manifestVersion").asLong()
                || !instance.path("serviceSpecSha256").equals(spec.path("serviceSpecSha256"))
                || !instance.path("releaseId").equals(spec.path("releaseId"))
                || !instance.path("deploymentGenerationId").asText().equals(JsonSupport.deploymentGeneration(manifest))
                || !instance.path("preStopPolicy").equals(spec.path("runtime").path("preStopPolicy"))
                || !instance.path("shutdownProfile").equals(spec.path("runtime").path("shutdownProfile"))
                || !instance.path("applicationDrainSeconds").equals(spec.path("runtime").path("applicationDrainSeconds"))
                || !instance.path("drainGraceSeconds").equals(spec.path("runtime").path("drainGraceSeconds")))
            throw new ControllerException("STATE_INVALID", "Candidate or promoted instance differs from the current target");
    }

    private JsonNode expectedMetadata(JsonNode deployment, JsonNode instance) {
        var expected = mapper.createObjectNode();
        expected.put("alphafrog.traffic-scope-id", deployment.path("trafficScopeId").asText());
        expected.put("alphafrog.release-id", instance.path("releaseId").asText());
        expected.put("alphafrog.deployment-generation-id", instance.path("deploymentGenerationId").asText());
        expected.put("alphafrog.instance-id", instance.path("instanceId").asText());
        return expected;
    }

    private JsonNode findService(JsonNode manifest, String name) {
        for (JsonNode service : manifest.path("services"))
            if (name.equals(service.path("serviceName").asText())) return service;
        return null;
    }

    private void validate(JsonSchema schema, JsonNode value, String code) {
        Set<ValidationMessage> failures = schema.validate(value);
        if (!failures.isEmpty()) {
            String message = failures.stream()
                    .map(failure -> "Schema validation failed at " + failure.getInstanceLocation()
                            + " (" + failure.getType() + ')')
                    .sorted().findFirst().orElse("Schema validation failed");
            throw new ControllerException(code, message);
        }
    }

    private JsonSchema load(JsonSchemaFactory factory, SchemaValidatorsConfig config, String resource) {
        try (InputStream input = BetaContractValidator.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing schema " + resource);
            return factory.getSchema(input, config);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load schema " + resource, exception);
        }
    }

    private void formatSelfTest() {
        try {
            Set<ValidationMessage> ipv4 = formatProbeSchema.validate(mapper.readTree("{\"ip\":\"192.0.2.1\",\"time\":\"2026-09-03T00:00:00Z\"}"));
            Set<ValidationMessage> ipv6 = formatProbeSchema.validate(mapper.readTree("{\"ip\":\"2001:db8::1\",\"time\":\"2026-09-03T00:00:00Z\"}"));
            Set<ValidationMessage> badIp = formatProbeSchema.validate(mapper.readTree("{\"ip\":\"not-an-ip\",\"time\":\"2026-09-03T00:00:00Z\"}"));
            Set<ValidationMessage> badTime = formatProbeSchema.validate(mapper.readTree("{\"ip\":\"192.0.2.1\",\"time\":\"2026-02-30T00:00:00Z\"}"));
            if (!ipv4.isEmpty() || !ipv6.isEmpty() || badIp.isEmpty() || badTime.isEmpty())
                throw new IllegalStateException("Schema validator format assertions are not active: ipv4=" + ipv4
                        + ", ipv6=" + ipv6 + ", badIp=" + badIp + ", badTime=" + badTime);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
