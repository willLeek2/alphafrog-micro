package world.willfrog.beta.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.state.AtomicJsonStore;
import world.willfrog.beta.validation.BetaContractValidator;

@Service
@ConditionalOnProperty(prefix = "alphafrog.beta-controller", name = "enabled", havingValue = "true")
public class BetaDeploymentService {
    private final ObjectMapper mapper;
    private final AtomicJsonStore store;
    private final BetaContractValidator validator;
    private final ContainerRuntime containers;
    private final ServiceRegistry registry;
    private final RetirementGateway retirement;
    private final Path retirementTokenFile;
    private final Clock clock;
    private final ReentrantLock mutationLock = new ReentrantLock(true);

    @Autowired
    public BetaDeploymentService(ObjectMapper mapper, AtomicJsonStore store, BetaContractValidator validator,
                                 ContainerRuntime containers, ServiceRegistry registry,
                                 RetirementGateway retirement, BetaControllerProperties properties) {
        this(mapper, store, validator, containers, registry, retirement,
                properties.getRetirementTokenFile(), Clock.systemUTC());
    }

    BetaDeploymentService(ObjectMapper mapper, AtomicJsonStore store, BetaContractValidator validator,
                          ContainerRuntime containers, ServiceRegistry registry,
                          RetirementGateway retirement, Path retirementTokenFile, Clock clock) {
        this.mapper = mapper;
        this.store = store;
        this.validator = validator;
        this.containers = containers;
        this.registry = registry;
        this.retirement = retirement;
        this.retirementTokenFile = retirementTokenFile;
        this.clock = clock;
    }

    @PostConstruct
    void verifyPersistentStateAtStartup() {
        readSecret(retirementTokenFile);
        recoverManifestLead();
        validateAll(store.snapshot());
        verifyExternalFactsAtStartup();
    }

    public ObjectNode submitManifest(ObjectNode manifest) {
        mutationLock.lock();
        try {
            validator.validateManifest(manifest);
            containers.validateManifest(manifest);
            String deploymentId = manifest.path("deploymentId").asText();
            store.read(state -> {
                validateAll(state.deepCopy());
                assertNoOtherScopeOwner(state, deploymentId, manifest.path("trafficScopeId").asText());
                assertManifestReservationsAvailable(state, deploymentId, manifest);
                JsonNode existing = findDeployment(state, deploymentId);
                if (existing != null) validateReplacement(existing, manifest);
                return null;
            });
            store.writeManifest(deploymentId, manifest);
            store.update(state -> {
                ObjectNode deployment = (ObjectNode) findDeployment(state, deploymentId);
                if (deployment == null) {
                    deployment = newDeployment(manifest);
                    ((ArrayNode) state.path("deployments")).add(deployment);
                } else {
                    acceptReplacement(deployment, manifest);
                }
                scheduleNext(state);
                validateAll(state);
                return null;
            });
            return statusByDeployment(deploymentId);
        } finally {
            mutationLock.unlock();
        }
    }

    public ObjectNode requestDelete(String deploymentId) {
        mutationLock.lock();
        try {
            final boolean[] empty = new boolean[1];
            store.update(state -> {
                requireNoOperation(state);
                ObjectNode deployment = requireDeployment(state, deploymentId);
                if (!"ACTIVE".equals(deployment.path("phase").asText()))
                    throw new ControllerException("DEPLOYMENT_BUSY", "A deployment deletion must be resumed through its failed service");
                for (JsonNode service : deployment.path("services"))
                    if ("FACTS_UNCERTAIN".equals(service.path("lastError").path("recoveryClass").asText()))
                        throw new ControllerException("FACTS_UNCERTAIN", "Conflicting external facts must be repaired before deletion");
                deployment.put("phase", "DELETING");
                startNextDelete(deployment);
                empty[0] = deployment.path("services").isEmpty();
                validateAll(state);
                return null;
            });
            if (empty[0]) {
                finalizeEmptyDeployment(deploymentId);
                ObjectNode deleted = mapper.createObjectNode();
                deleted.put("deploymentId", deploymentId);
                deleted.put("phase", "DELETED");
                return deleted;
            }
            return statusByDeployment(deploymentId);
        } finally {
            mutationLock.unlock();
        }
    }

    public ObjectNode retry(String deploymentId, String serviceName) {
        mutationLock.lock();
        try {
            store.read(state -> {
                ObjectNode deployment = requireDeployment(state, deploymentId);
                ObjectNode service = requireService(deployment, serviceName);
                if ("FACTS_UNCERTAIN".equals(service.path("lastError").path("recoveryClass").asText())) {
                    verifyServiceFacts(deployment, service, store.readManifest(deploymentId));
                }
                return null;
            });
            store.update(state -> {
                requireNoOperation(state);
                ObjectNode deployment = requireDeployment(state, deploymentId);
                ObjectNode service = requireService(deployment, serviceName);
                if (service.path("lastError").isNull())
                    throw new ControllerException("RETRY_NOT_ALLOWED", "Only a failed service can be retried");
                String failedType = service.path("lastError").path("failedOperationType").asText();
                service.putNull("failedManifestVersion");
                service.putNull("lastError");
                if (service.path("drainingInstance").isObject()) {
                    boolean deleting = "DELETE".equals(failedType);
                    service.put("phase", deleting ? "DELETING" : "UPDATING");
                    service.set("operation", operation(failedType,
                            deleting ? "DRAINING_ACTIVE" : "DRAINING_PREVIOUS", null));
                } else if (service.path("candidateInstance").isObject()) {
                    boolean create = service.path("activeInstance").isNull();
                    service.put("phase", create ? "CREATING" : "UPDATING");
                    service.set("operation", operation(create ? "CREATE" : "UPDATE",
                            "WAITING_CANDIDATE_READINESS",
                            service.path("candidateInstance").path("instanceId").asText()));
                } else if ("DELETE".equals(failedType)) {
                    service.put("phase", "DELETING");
                    service.set("operation", operation("DELETE", "REMOVING_TRAFFIC", null));
                } else {
                    boolean create = service.path("activeInstance").isNull();
                    service.put("phase", create ? "CREATING" : "UPDATING");
                    String instanceId = newInstanceId(deployment, service);
                    service.set("operation", operation(create ? "CREATE" : "UPDATE",
                            "STARTING_CANDIDATE", instanceId));
                }
                validateAll(state);
                return null;
            });
            return statusByDeployment(deploymentId);
        } finally {
            mutationLock.unlock();
        }
    }

    @Scheduled(fixedDelayString = "${alphafrog.beta-controller.reconcile-delay:PT2S}")
    public void scheduledReconcile() { reconcileOne(); }

    public ObjectNode reconcileOne() {
        mutationLock.lock();
        try {
            OperationRef ref = store.read(this::currentOperation);
            if (ref == null) {
                String expired = store.read(this::firstExpiredDeployment);
                if (expired == null) return store.snapshot();
                requestDelete(expired);
                ref = store.read(this::currentOperation);
            }
            try {
                switch (ref.phase()) {
                    case "STARTING_CANDIDATE" -> startCandidate(ref);
                    case "WAITING_CANDIDATE_READINESS" -> observeCandidate(ref);
                    case "SWITCHING_TRAFFIC" -> switchTraffic(ref);
                    case "DRAINING_PREVIOUS", "DRAINING_ACTIVE" -> drain(ref);
                    case "REMOVING_TRAFFIC" -> removeTraffic(ref);
                    default -> throw new ControllerException("OPERATION_PHASE_INVALID", "Unknown operation phase");
                }
            } catch (ControllerException failure) {
                fail(ref, failure);
            } catch (RuntimeException failure) {
                fail(ref, new ControllerException("EXTERNAL_OPERATION_FAILED", safeMessage(failure), failure));
            }
            return store.snapshot();
        } finally {
            mutationLock.unlock();
        }
    }

    public ObjectNode route(String trafficScopeId, String serviceName) {
        return store.read(state -> {
            for (JsonNode deployment : state.path("deployments")) {
                if (!trafficScopeId.equals(deployment.path("trafficScopeId").asText())) continue;
                for (JsonNode service : deployment.path("services")) {
                    if (serviceName.equals(service.path("serviceName").asText())) {
                        ObjectNode result = mapper.createObjectNode();
                        result.put("trafficScopeId", trafficScopeId);
                        result.put("serviceName", serviceName);
                        result.set("route", service.path("route").deepCopy());
                        JsonNode active = service.path("activeInstance");
                        if (active.isNull()) result.putNull("endpoint");
                        else result.set("endpoint", active.path("endpoint").deepCopy());
                        result.put("stateVersion", state.path("stateVersion").asLong());
                        return result;
                    }
                }
            }
            throw new ControllerException("ROUTE_NOT_FOUND", "No route exists for this traffic scope and service");
        });
    }

    public ObjectNode status(String trafficScopeId, String serviceName) {
        return store.read(state -> {
            for (JsonNode deployment : state.path("deployments")) {
                if (!trafficScopeId.equals(deployment.path("trafficScopeId").asText())) continue;
                for (JsonNode service : deployment.path("services")) {
                    if (serviceName.equals(service.path("serviceName").asText())) {
                        ObjectNode result = service.deepCopy();
                        result.put("deploymentId", deployment.path("deploymentId").asText());
                        result.put("trafficScopeId", trafficScopeId);
                        result.put("deploymentPhase", deployment.path("phase").asText());
                        result.put("stateVersion", state.path("stateVersion").asLong());
                        return result;
                    }
                }
            }
            throw new ControllerException("STATUS_NOT_FOUND", "No service state exists for this traffic scope");
        });
    }

    public ObjectNode statusByDeployment(String deploymentId) {
        return store.read(state -> requireDeployment(state, deploymentId).deepCopy());
    }

    private void startCandidate(OperationRef ref) {
        JsonNode manifest = store.readManifest(ref.deploymentId());
        JsonNode spec = findService(manifest, ref.serviceName());
        if (spec == null) throw new ControllerException("SERVICE_SPEC_MISSING", "Service is absent from the manifest");
        JsonNode active = ref.service().path("activeInstance");
        String slot = active.isObject() && "A".equals(active.path("portSlot").asText()) ? "B" : "A";
        int hostPort = spec.path("runtime").path("hostPorts").path("A".equals(slot) ? 0 : 1).asInt();
        String generation = JsonSupport.deploymentGeneration(manifest);
        String token = "AGENT_RETIRE_GENERATION_V1".equals(spec.path("runtime").path("preStopPolicy").asText())
                ? readSecret(retirementTokenFile) : "";
        ContainerRuntime.CandidatePlan plan = new ContainerRuntime.CandidatePlan(
                ref.deploymentId(), ref.trafficScopeId(), ref.candidateInstanceId(), generation, slot, hostPort);
        ContainerRuntime.ContainerObservation observation = containers.create(manifest, spec, plan, token);
        ServiceRegistry.Registration registration = registry.register(manifest, spec, ref.candidateInstanceId(), generation,
                observation.endpointAddress(), hostPort, false);
        Instant now = Instant.now(clock);
        ObjectNode candidate = instance(spec, manifest, ref.candidateInstanceId(), generation, slot, observation, registration);
        candidate.put("readiness", "STARTING");
        candidate.putNull("readinessObservedAt");
        candidate.put("readinessDeadline", now.plusSeconds(spec.path("runtime").path("readinessTimeoutSeconds").asLong()).toString());
        store.update(state -> {
            ObjectNode service = checkedService(state, ref);
            service.set("candidateInstance", candidate);
            ((ObjectNode) service.path("operation")).put("phase", "WAITING_CANDIDATE_READINESS");
            validateAll(state);
            return null;
        });
    }

    private void observeCandidate(OperationRef ref) {
        JsonNode candidate = ref.service().path("candidateInstance");
        JsonNode manifest = store.readManifest(ref.deploymentId());
        JsonNode spec = findService(manifest, ref.serviceName());
        containers.verifyPersistedInstance(manifest, spec, candidate);
        ContainerRuntime.ContainerObservation observed = containers.inspect(
                candidate.path("machineId").asText(), candidate.path("containerName").asText());
        Optional<ServiceRegistry.Registration> found = registry.find(registration(candidate.path("registration")));
        if (found.isEmpty()) {
            cleanupCandidate(candidate);
            markFailed(ref, "CANDIDATE_REGISTRATION_MISSING", "Candidate registration is missing",
                    "CLEAN_RETRYABLE", true);
            return;
        }
        ServiceRegistry.Registration registration = found.get();
        if ((!observed.containerId().isEmpty() && !observed.containerId().equals(candidate.path("containerId").asText()))
                || (!observed.endpointAddress().isEmpty()
                    && !observed.endpointAddress().equals(candidate.path("endpoint").path("address").asText()))
                || (observed.hostPort() != 0 && observed.hostPort() != candidate.path("hostPort").asInt()))
            throw new ControllerException("CONTAINER_IDENTITY_CONFLICT", "Observed candidate differs from the persisted instance");
        if (registration.enabled() || registration.weight() != 0)
            throw new ControllerException("CANDIDATE_SELECTABLE_UNEXPECTEDLY", "Candidate registration became selectable before the route switch");
        boolean ready = observed.running() && observed.health() == ContainerRuntime.ContainerObservation.Health.HEALTHY
                && registration.healthy() && !registration.enabled() && registration.weight() == 0;
        boolean expired = !Instant.now(clock).isBefore(Instant.parse(candidate.path("readinessDeadline").asText()));
        if (!ready && !expired && observed.health() != ContainerRuntime.ContainerObservation.Health.UNHEALTHY
                && observed.health() != ContainerRuntime.ContainerObservation.Health.MISSING) return;
        if (!ready) {
            cleanupCandidate(candidate);
            markFailed(ref, "CANDIDATE_NOT_READY", "Candidate did not become ready", "CLEAN_RETRYABLE", true);
            return;
        }
        store.update(state -> {
            ObjectNode service = checkedService(state, ref);
            ObjectNode stored = (ObjectNode) service.path("candidateInstance");
            stored.put("readiness", "READY");
            stored.put("readinessObservedAt", Instant.now(clock).toString());
            stored.set("registration", registration(registration));
            ((ObjectNode) service.path("operation")).put("phase", "SWITCHING_TRAFFIC");
            validateAll(state);
            return null;
        });
    }

    private void switchTraffic(OperationRef ref) {
        JsonNode candidate = ref.service().path("candidateInstance");
        ServiceRegistry.Registration enabled = registry.setSelectable(registration(candidate.path("registration")), true);
        if (!enabled.enabled() || enabled.weight() != 1 || !enabled.healthy())
            throw new ControllerException("NACOS_ENABLE_NOT_CONFIRMED", "Candidate registration was not enabled");
        final long[] routeVersion = new long[1];
        store.update(state -> {
            ObjectNode service = checkedService(state, ref);
            ObjectNode promoted = ((ObjectNode) service.path("candidateInstance")).deepCopy();
            promoted.remove(java.util.List.of("readiness", "readinessObservedAt", "readinessDeadline"));
            promoted.set("registration", registration(enabled));
            JsonNode previous = service.path("activeInstance").deepCopy();
            long nextRoute = Math.addExact(service.path("route").path("routeVersion").asLong(), 1);
            routeVersion[0] = nextRoute;
            String switchAt = Instant.now(clock).toString();
            ObjectNode route = route(promoted, nextRoute, switchAt);
            service.set("activeInstance", promoted);
            service.putNull("candidateInstance");
            service.set("route", route);
            if (previous.isObject()) {
                ObjectNode draining = ((ObjectNode) previous).deepCopy();
                draining.put("trafficRemovedAt", switchAt);
                draining.putNull("preStopCompletedAt");
                draining.putNull("stopSignalRequestedAt");
                draining.putNull("stopDeadline");
                service.set("drainingInstance", draining);
                service.put("phase", "UPDATING");
                ObjectNode operation = (ObjectNode) service.path("operation");
                operation.put("phase", "DRAINING_PREVIOUS");
                operation.putNull("candidateInstanceId");
            } else {
                service.putNull("drainingInstance");
                stable(service);
                scheduleNext(state);
            }
            validateAll(state);
            return null;
        });
        ObjectNode observedRoute = route(ref.trafficScopeId(), ref.serviceName());
        if (observedRoute.path("route").path("routeVersion").asLong() != routeVersion[0])
            throw new ControllerException("ROUTE_READBACK_FAILED", "Published route could not be read back");
    }

    private void removeTraffic(OperationRef ref) {
        store.update(state -> {
            ObjectNode service = checkedService(state, ref);
            ObjectNode active = ((ObjectNode) service.path("activeInstance")).deepCopy();
            String switchAt = Instant.now(clock).toString();
            active.put("trafficRemovedAt", switchAt);
            active.putNull("preStopCompletedAt");
            active.putNull("stopSignalRequestedAt");
            active.putNull("stopDeadline");
            service.putNull("activeInstance");
            service.set("drainingInstance", active);
            long nextRoute = Math.addExact(service.path("route").path("routeVersion").asLong(), 1);
            service.set("route", emptyRoute(nextRoute, switchAt));
            ObjectNode operation = (ObjectNode) service.path("operation");
            operation.put("phase", "DRAINING_ACTIVE");
            operation.putNull("candidateInstanceId");
            validateAll(state);
            return null;
        });
        ObjectNode observed = route(ref.trafficScopeId(), ref.serviceName());
        if (!observed.path("route").path("defaultInstanceId").isNull())
            throw new ControllerException("ROUTE_READBACK_FAILED", "Removed route is still selectable");
    }

    private void drain(OperationRef ref) {
        JsonNode current = store.read(state -> checkedService(state, ref).deepCopy());
        ObjectNode draining = (ObjectNode) current.path("drainingInstance");
        ObjectNode route = route(ref.trafficScopeId(), ref.serviceName());
        if (!route.path("route").equals(current.path("route")))
            throw new ControllerException("ROUTE_READBACK_FAILED", "Route readback differs from persisted state");
        JsonNode manifest = store.readManifest(ref.deploymentId());
        JsonNode spec = findService(manifest, ref.serviceName());
        containers.verifyPersistedInstance(manifest, spec, draining);
        ContainerRuntime.ContainerObservation beforeStop = containers.inspect(
                draining.path("machineId").asText(), draining.path("containerName").asText());
        if (!beforeStop.running() && draining.path("preStopCompletedAt").isNull())
            throw new ControllerException("PRESTOP_NOT_CONFIRMED", "Draining container exited before its stop action was confirmed");
        ServiceRegistry.Registration expected = registration(draining.path("registration"));
        Optional<ServiceRegistry.Registration> registered = registry.find(expected);
        ServiceRegistry.Registration disabled = registered.isPresent()
                ? registry.setSelectable(registered.get(), false) : disabled(expected);
        if (disabled.enabled() || disabled.weight() != 0)
            throw new ControllerException("NACOS_DISABLE_NOT_CONFIRMED", "Draining registration is still selectable");
        if (draining.path("preStopCompletedAt").isNull()) {
            if ("AGENT_RETIRE_GENERATION_V1".equals(draining.path("preStopPolicy").asText())) {
                retirement.retire(draining.path("endpoint").path("address").asText(),
                        draining.path("endpoint").path("port").asInt(), ref.deploymentId(),
                        draining.path("deploymentGenerationId").asText(), readSecret(retirementTokenFile));
            }
            store.update(state -> {
                ObjectNode service = checkedService(state, ref);
                ObjectNode value = (ObjectNode) service.path("drainingInstance");
                value.set("registration", registration(disabled));
                value.put("preStopCompletedAt", Instant.now(clock).toString());
                validateAll(state);
                return null;
            });
        }
        current = store.read(state -> checkedService(state, ref).deepCopy());
        draining = (ObjectNode) current.path("drainingInstance");
        if (draining.path("stopSignalRequestedAt").isNull()) {
            String requested = Instant.now(clock).toString();
            String deadline = Instant.parse(requested).plusSeconds(draining.path("drainGraceSeconds").asLong()).toString();
            store.update(state -> {
                ObjectNode value = (ObjectNode) checkedService(state, ref).path("drainingInstance");
                value.put("stopSignalRequestedAt", requested);
                value.put("stopDeadline", deadline);
                validateAll(state);
                return null;
            });
        }
        if (beforeStop.running()) {
            containers.stop(draining.path("machineId").asText(), draining.path("containerName").asText(),
                    draining.path("drainGraceSeconds").asInt());
        }
        ContainerRuntime.ContainerObservation stopped = containers.inspect(
                draining.path("machineId").asText(), draining.path("containerName").asText());
        if (stopped.running()) throw new ControllerException("CONTAINER_STILL_RUNNING", "Container did not stop before cleanup");
        registry.unregister(disabled);
        containers.remove(draining.path("machineId").asText(), draining.path("containerName").asText());
        finishDrain(ref);
    }

    private void finishDrain(OperationRef ref) {
        final boolean[] deploymentEmpty = new boolean[1];
        store.update(state -> {
            ObjectNode deployment = requireDeployment(state, ref.deploymentId());
            ObjectNode service = checkedService(state, ref);
            if ("DRAINING_ACTIVE".equals(ref.phase())) {
                removeService(deployment, ref.serviceName());
                startNextDelete(deployment);
                deploymentEmpty[0] = deployment.path("services").isEmpty();
            } else {
                service.putNull("drainingInstance");
                stable(service);
                scheduleNext(state);
            }
            validateAll(state);
            return null;
        });
        if (deploymentEmpty[0]) {
            finalizeEmptyDeployment(ref.deploymentId());
        }
    }

    private void finalizeEmptyDeployment(String deploymentId) {
        store.deleteManifest(deploymentId);
        store.update(state -> {
            ObjectNode deployment = requireDeployment(state, deploymentId);
            if (!"DELETING".equals(deployment.path("phase").asText()) || !deployment.path("services").isEmpty())
                throw new ControllerException("DELETE_STATE_INVALID", "Deployment is not ready for final removal");
            removeDeployment(state, deploymentId);
            validateAll(state);
            return null;
        });
    }

    private void fail(OperationRef ref, ControllerException failure) {
        String recovery = switch (ref.phase()) {
            case "STARTING_CANDIDATE", "WAITING_CANDIDATE_READINESS", "SWITCHING_TRAFFIC" -> "FACTS_UNCERTAIN";
            case "REMOVING_TRAFFIC" -> "DELETE_RETRYABLE";
            default -> "FACTS_UNCERTAIN";
        };
        markFailed(ref, failure.code(), failure.getMessage(), recovery, false);
    }

    private void markFailed(OperationRef ref, String code, String message, String recovery, boolean candidateCleaned) {
        store.update(state -> {
            ObjectNode service = checkedService(state, ref);
            if (candidateCleaned) service.putNull("candidateInstance");
            boolean oldInstanceStillServing = candidateCleaned && "UPDATE".equals(ref.type())
                    && service.path("activeInstance").isObject();
            service.put("phase", oldInstanceStillServing ? "STABLE" : "FAILED");
            service.putNull("operation");
            service.put("failedManifestVersion", service.path("targetManifestVersion").asLong());
            ObjectNode error = mapper.createObjectNode();
            error.put("code", code.replaceAll("[^A-Z0-9_]", "_"));
            error.put("message", sanitize(message));
            error.put("at", Instant.now(clock).toString());
            error.put("failedOperationType", ref.type());
            error.put("recoveryClass", recovery);
            service.set("lastError", error);
            scheduleNext(state);
            validateAll(state);
            return null;
        });
    }

    private void cleanupCandidate(JsonNode candidate) {
        ServiceRegistry.Registration registration = registration(candidate.path("registration"));
        registry.unregister(registration);
        containers.remove(candidate.path("machineId").asText(), candidate.path("containerName").asText());
    }

    private void verifyExternalFactsAtStartup() {
        ObjectNode snapshot = store.snapshot();
        for (JsonNode deployment : snapshot.path("deployments")) {
            JsonNode manifest = store.readManifest(deployment.path("deploymentId").asText());
            for (JsonNode service : deployment.path("services")) {
                if (service.path("lastError").isObject()) continue;
                try {
                    verifyServiceFacts(deployment, service, manifest);
                } catch (ControllerException failure) {
                    recordStartupFailure(deployment.path("deploymentId").asText(),
                            service.path("serviceName").asText(), failure);
                } catch (RuntimeException failure) {
                    recordStartupFailure(deployment.path("deploymentId").asText(),
                            service.path("serviceName").asText(),
                            new ControllerException("EXTERNAL_FACTS_UNAVAILABLE", safeMessage(failure), failure));
                }
            }
        }
    }

    private void verifyServiceFacts(JsonNode deployment, JsonNode service, JsonNode manifest) {
        ObjectNode observedRoute = route(deployment.path("trafficScopeId").asText(),
                service.path("serviceName").asText());
        if (!observedRoute.path("route").equals(service.path("route")))
            throw new ControllerException("ROUTE_READBACK_FAILED", "Route readback differs from persisted state");
        JsonNode spec = findService(manifest, service.path("serviceName").asText());
        Set<String> trackedContainerIds = new HashSet<>();
        Set<String> trackedInstanceIds = new HashSet<>();
        for (String role : new String[]{"activeInstance", "candidateInstance", "drainingInstance"}) {
            JsonNode instance = service.path(role);
            if (instance.isObject()) {
                trackedContainerIds.add(instance.path("containerId").asText());
                trackedInstanceIds.add(instance.path("instanceId").asText());
            }
        }
        containers.assertNoUntrackedInstances(manifest, spec, trackedContainerIds);
        registry.assertNoUntrackedRegistrations(manifest, spec, trackedInstanceIds);
        for (String role : new String[]{"activeInstance", "candidateInstance", "drainingInstance"}) {
            JsonNode instance = service.path(role);
            if (!instance.isObject()) continue;
            containers.verifyPersistedInstance(manifest, spec, instance);
            ContainerRuntime.ContainerObservation observed = containers.inspect(
                    instance.path("machineId").asText(), instance.path("containerName").asText());
            boolean missing = observed.health() == ContainerRuntime.ContainerObservation.Health.MISSING
                    || observed.containerId().isEmpty();
            boolean completedDrain = "drainingInstance".equals(role)
                    && !instance.path("preStopCompletedAt").isNull();
            if (missing && !completedDrain)
                throw new ControllerException("CONTAINER_FACT_MISSING", "Persisted container is missing");
            if (!missing && (!observed.containerId().equals(instance.path("containerId").asText())
                    || !observed.endpointAddress().equals(instance.path("endpoint").path("address").asText())
                    || observed.hostPort() != instance.path("hostPort").asInt()))
                throw new ControllerException("CONTAINER_IDENTITY_CONFLICT", "Observed container differs from persisted state");
            if (("activeInstance".equals(role) || "candidateInstance".equals(role)) && !observed.running())
                throw new ControllerException("CONTAINER_NOT_RUNNING", "Routable or candidate container is not running");
            if ("drainingInstance".equals(role) && !observed.running()
                    && instance.path("preStopCompletedAt").isNull())
                throw new ControllerException("PRESTOP_NOT_CONFIRMED", "Draining container exited before its stop action was confirmed");

            Optional<ServiceRegistry.Registration> registration = registry.find(registration(instance.path("registration")));
            if (registration.isEmpty()) {
                if (!completedDrain)
                    throw new ControllerException("NACOS_FACT_MISSING", "Persisted Nacos registration is missing");
                continue;
            }
            ServiceRegistry.Registration value = registration.get();
            if (!value.ephemeral())
                throw new ControllerException("NACOS_FACT_INVALID", "Nacos registration is not ephemeral");
            if ("activeInstance".equals(role) && (!value.enabled() || value.weight() != 1 || !value.healthy()))
                throw new ControllerException("ACTIVE_INSTANCE_DISABLED", "Active Nacos registration is disabled");
            if ("candidateInstance".equals(role)) {
                String operationPhase = service.path("operation").path("phase").asText();
                boolean selectable = value.enabled() && value.weight() == 1;
                boolean disabled = !value.enabled() && value.weight() == 0;
                boolean allowed = "SWITCHING_TRAFFIC".equals(operationPhase)
                        ? (selectable || disabled) : disabled;
                if (!allowed)
                    throw new ControllerException("CANDIDATE_SELECTABILITY_CONFLICT",
                            "Candidate Nacos selectability does not match the persisted phase");
            }
        }
    }

    private void recordStartupFailure(String deploymentId, String serviceName, ControllerException failure) {
        store.update(state -> {
            ObjectNode deployment = requireDeployment(state, deploymentId);
            ObjectNode service = requireService(deployment, serviceName);
            JsonNode operation = service.path("operation");
            String type = operation.isObject() ? operation.path("type").asText()
                    : "DELETING".equals(deployment.path("phase").asText()) ? "DELETE"
                    : service.path("activeInstance").isObject() ? "UPDATE" : "CREATE";
            service.put("phase", "FAILED");
            service.putNull("operation");
            service.put("failedManifestVersion", service.path("targetManifestVersion").asLong());
            ObjectNode error = mapper.createObjectNode();
            error.put("code", failure.code().replaceAll("[^A-Z0-9_]", "_"));
            error.put("message", sanitize(failure.getMessage()));
            error.put("at", Instant.now(clock).toString());
            error.put("failedOperationType", type);
            error.put("recoveryClass", "FACTS_UNCERTAIN");
            service.set("lastError", error);
            scheduleNext(state);
            validateAll(state);
            return null;
        });
    }

    private void validateAll(ObjectNode state) {
        validator.validateState(state);
        Set<String> deploymentIds = new HashSet<>();
        Set<String> trafficScopes = new HashSet<>();
        Set<String> reservedPorts = new HashSet<>();
        Set<String> instanceIds = new HashSet<>();
        Set<String> containerIds = new HashSet<>();
        Set<String> registrationIds = new HashSet<>();
        Set<String> nacosInstanceIds = new HashSet<>();
        int operations = 0;
        for (JsonNode deployment : state.path("deployments")) {
            if (!deploymentIds.add(deployment.path("deploymentId").asText()))
                throw new ControllerException("STATE_INVALID", "Deployment identifier is duplicated");
            if (!trafficScopes.add(deployment.path("trafficScopeId").asText()))
                throw new ControllerException("STATE_INVALID", "Traffic scope is owned by more than one deployment");
            String deploymentId = deployment.path("deploymentId").asText();
            if (!store.hasManifest(deploymentId)) {
                if ("DELETING".equals(deployment.path("phase").asText()) && deployment.path("services").isEmpty()) continue;
                throw new ControllerException("STATE_MANIFEST_MISMATCH", "Deployment manifest is missing");
            }
            JsonNode manifest = store.readManifest(deploymentId);
            validator.validatePair(manifest, state);
            for (JsonNode service : deployment.path("services")) {
                if (service.path("operation").isObject() && ++operations > 1)
                    throw new ControllerException("STATE_INVALID", "More than one deployment operation is active");
                Set<String> usedSlots = new HashSet<>();
                for (String role : new String[]{"activeInstance", "candidateInstance", "drainingInstance"}) {
                    JsonNode instance = service.path(role);
                    if (!instance.isObject()) continue;
                    if (!instanceIds.add(instance.path("instanceId").asText()))
                        throw new ControllerException("STATE_INVALID", "Instance identifier is duplicated");
                    if (!containerIds.add(instance.path("containerId").asText()))
                        throw new ControllerException("STATE_INVALID", "Container identifier is duplicated");
                    JsonNode registration = instance.path("registration");
                    String registrationKey = registration.path("namespaceId").asText() + '\u0000'
                            + registration.path("groupName").asText() + '\u0000'
                            + registration.path("serviceName").asText() + '\u0000'
                            + registration.path("clusterName").asText() + '\u0000'
                            + registration.path("ip").asText() + '\u0000' + registration.path("port").asInt();
                    if (!registrationIds.add(registrationKey))
                        throw new ControllerException("STATE_INVALID", "Nacos registration identity is duplicated");
                    if (!nacosInstanceIds.add(registration.path("nacosInstanceId").asText()))
                        throw new ControllerException("STATE_INVALID", "Nacos instance identifier is duplicated");
                    if (!usedSlots.add(instance.path("portSlot").asText()))
                        throw new ControllerException("STATE_INVALID", "Service instances reuse the same port slot");
                }
            }
            for (JsonNode spec : manifest.path("services")) {
                for (JsonNode port : spec.path("runtime").path("hostPorts")) {
                    String key = spec.path("machineId").asText() + ':' + port.asInt();
                    if (!reservedPorts.add(key))
                        throw new ControllerException("STATE_INVALID", "Fixed host port is reserved more than once");
                }
            }
        }
    }

    private void recoverManifestLead() {
        ObjectNode before = store.snapshot();
        Set<String> manifestIds = store.manifestDeploymentIds();
        boolean recoveryNeeded = false;
        for (String deploymentId : manifestIds) {
            JsonNode manifest = store.readManifest(deploymentId);
            validator.validateManifest(manifest);
            containers.validateManifest(manifest);
            JsonNode deployment = findDeployment(before, deploymentId);
            if (deployment == null
                    || deployment.path("acceptedManifestVersion").asLong() < manifest.path("manifestVersion").asLong()) {
                recoveryNeeded = true;
            }
        }
        for (JsonNode deployment : before.path("deployments")) {
            if ("DELETING".equals(deployment.path("phase").asText()) && deployment.path("services").isEmpty()
                    && !manifestIds.contains(deployment.path("deploymentId").asText())) recoveryNeeded = true;
        }
        if (!recoveryNeeded) return;
        store.update(state -> {
            for (String deploymentId : manifestIds) {
                JsonNode manifest = store.readManifest(deploymentId);
                ObjectNode deployment = (ObjectNode) findDeployment(state, deploymentId);
                if (deployment == null) {
                    assertNoOtherScopeOwner(state, deploymentId, manifest.path("trafficScopeId").asText());
                    ((ArrayNode) state.path("deployments")).add(newDeployment(manifest));
                    continue;
                }
                long accepted = deployment.path("acceptedManifestVersion").asLong();
                long onDisk = manifest.path("manifestVersion").asLong();
                if (onDisk == accepted) continue;
                if (onDisk < accepted || hasOperation(deployment))
                    throw new ControllerException("STATE_MANIFEST_MISMATCH", "Manifest lead cannot be recovered safely");
                assertRecoverableReplacement(deployment, manifest);
                acceptReplacement(deployment, manifest);
            }
            java.util.List<String> completedDeletes = new java.util.ArrayList<>();
            for (JsonNode deployment : state.path("deployments")) {
                if ("DELETING".equals(deployment.path("phase").asText()) && deployment.path("services").isEmpty()
                        && !manifestIds.contains(deployment.path("deploymentId").asText()))
                    completedDeletes.add(deployment.path("deploymentId").asText());
            }
            completedDeletes.forEach(deploymentId -> removeDeployment(state, deploymentId));
            scheduleNext(state);
            validateAll(state);
            return null;
        });
    }

    private void assertRecoverableReplacement(JsonNode deployment, JsonNode manifest) {
        for (JsonNode service : deployment.path("services")) {
            JsonNode spec = findService(manifest, service.path("serviceName").asText());
            if (spec == null)
                throw new ControllerException("STATE_MANIFEST_MISMATCH", "Leading manifest removed a service");
            for (String role : new String[]{"activeInstance", "candidateInstance", "drainingInstance"}) {
                JsonNode instance = service.path(role);
                if (!instance.isObject()) continue;
                if (!instance.path("machineId").equals(spec.path("machineId"))
                        || !contains(spec.path("runtime").path("hostPorts"), instance.path("hostPort")))
                    throw new ControllerException("STATE_MANIFEST_MISMATCH", "Leading manifest moved a live service");
            }
        }
    }

    private boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode item : array) if (item.equals(value)) return true;
        return false;
    }

    private String firstExpiredDeployment(JsonNode state) {
        if (currentOperation(state) != null) return null;
        Instant now = Instant.now(clock);
        return java.util.stream.StreamSupport.stream(state.path("deployments").spliterator(), false)
                .filter(deployment -> "ACTIVE".equals(deployment.path("phase").asText()))
                .filter(deployment -> !hasFailure(deployment))
                .filter(deployment -> !now.isBefore(Instant.parse(deployment.path("expiresAt").asText())))
                .map(deployment -> deployment.path("deploymentId").asText())
                .sorted().findFirst().orElse(null);
    }

    private ObjectNode newDeployment(JsonNode manifest) {
        ObjectNode deployment = mapper.createObjectNode();
        deployment.put("deploymentId", manifest.path("deploymentId").asText());
        deployment.put("trafficScopeId", manifest.path("trafficScopeId").asText());
        deployment.put("phase", "ACTIVE");
        copyManifestIdentity(deployment, manifest);
        ArrayNode services = mapper.createArrayNode();
        for (JsonNode spec : manifest.path("services")) services.add(newService(spec, manifest.path("manifestVersion").asLong()));
        deployment.set("services", services);
        return deployment;
    }

    private void acceptReplacement(ObjectNode deployment, JsonNode manifest) {
        copyManifestIdentity(deployment, manifest);
        Set<String> existing = new HashSet<>();
        for (JsonNode serviceNode : deployment.path("services")) {
            ObjectNode service = (ObjectNode) serviceNode;
            JsonNode spec = findService(manifest, service.path("serviceName").asText());
            existing.add(service.path("serviceName").asText());
            service.put("targetManifestVersion", manifest.path("manifestVersion").asLong());
            service.put("targetServiceSpecSha256", spec.path("serviceSpecSha256").asText());
            if ("CLEAN_RETRYABLE".equals(service.path("lastError").path("recoveryClass").asText())) {
                service.put("phase", service.path("activeInstance").isObject() ? "STABLE" : "CREATING");
                service.putNull("failedManifestVersion");
                service.putNull("lastError");
            }
        }
        for (JsonNode spec : manifest.path("services")) {
            if (!existing.contains(spec.path("serviceName").asText()))
                ((ArrayNode) deployment.path("services")).add(newService(spec, manifest.path("manifestVersion").asLong()));
        }
    }

    private void copyManifestIdentity(ObjectNode deployment, JsonNode manifest) {
        deployment.put("acceptedManifestVersion", manifest.path("manifestVersion").asLong());
        deployment.put("manifestSha256", JsonSupport.sha256(mapper, manifest));
        deployment.put("gitCommit", manifest.path("gitCommit").asText());
        deployment.set("owner", manifest.path("owner").deepCopy());
        deployment.put("expiresAt", manifest.path("expiresAt").asText());
    }

    private ObjectNode newService(JsonNode spec, long version) {
        ObjectNode service = mapper.createObjectNode();
        service.put("serviceName", spec.path("serviceName").asText());
        service.put("phase", "CREATING");
        service.put("targetManifestVersion", version);
        service.put("targetServiceSpecSha256", spec.path("serviceSpecSha256").asText());
        service.putNull("activeInstance");
        service.putNull("candidateInstance");
        service.putNull("drainingInstance");
        service.set("route", emptyRoute(0));
        service.putNull("operation");
        service.putNull("failedManifestVersion");
        service.putNull("lastError");
        return service;
    }

    private ObjectNode instance(JsonNode spec, JsonNode manifest, String instanceId, String generation, String slot,
                                ContainerRuntime.ContainerObservation container, ServiceRegistry.Registration registration) {
        ObjectNode result = mapper.createObjectNode();
        result.put("instanceId", instanceId);
        result.put("machineId", spec.path("machineId").asText());
        result.put("releaseId", spec.path("releaseId").asText());
        result.put("deploymentGenerationId", generation);
        result.put("preStopPolicy", spec.path("runtime").path("preStopPolicy").asText());
        result.put("shutdownProfile", spec.path("runtime").path("shutdownProfile").asText());
        result.put("applicationDrainSeconds", spec.path("runtime").path("applicationDrainSeconds").asInt());
        result.put("drainGraceSeconds", spec.path("runtime").path("drainGraceSeconds").asInt());
        result.put("manifestVersion", manifest.path("manifestVersion").asLong());
        result.put("serviceSpecSha256", spec.path("serviceSpecSha256").asText());
        result.put("containerName", container.containerName());
        result.put("containerId", container.containerId());
        result.put("portSlot", slot);
        result.put("hostPort", container.hostPort());
        ObjectNode endpoint = mapper.createObjectNode();
        endpoint.put("address", container.endpointAddress());
        endpoint.put("port", container.hostPort());
        result.set("endpoint", endpoint);
        result.set("registration", registration(registration));
        return result;
    }

    private ObjectNode registration(ServiceRegistry.Registration value) {
        ObjectNode node = mapper.valueToTree(value);
        node.set("metadata", mapper.valueToTree(value.metadata()));
        return node;
    }

    private ServiceRegistry.Registration disabled(ServiceRegistry.Registration value) {
        return new ServiceRegistry.Registration(value.serviceName(), value.groupName(), value.namespaceId(),
                value.clusterName(), value.ip(), value.port(), value.nacosInstanceId(), false,
                value.healthy(), 0, value.ephemeral(), value.metadata());
    }

    private ServiceRegistry.Registration registration(JsonNode value) {
        return new ServiceRegistry.Registration(value.path("serviceName").asText(), value.path("groupName").asText(),
                value.path("namespaceId").asText(), value.path("clusterName").asText(), value.path("ip").asText(),
                value.path("port").asInt(), value.path("nacosInstanceId").asText(), value.path("enabled").asBoolean(),
                value.path("healthy").asBoolean(), value.path("weight").asInt(), value.path("ephemeral").asBoolean(),
                mapper.convertValue(value.path("metadata"), mapper.getTypeFactory().constructMapType(java.util.Map.class, String.class, String.class)));
    }

    private ObjectNode route(JsonNode instance, long version) {
        return route(instance, version, Instant.now(clock).toString());
    }

    private ObjectNode route(JsonNode instance, long version, String updatedAt) {
        ObjectNode route = mapper.createObjectNode();
        route.put("defaultInstanceId", instance.path("instanceId").asText());
        route.put("defaultReleaseId", instance.path("releaseId").asText());
        route.put("defaultDeploymentGenerationId", instance.path("deploymentGenerationId").asText());
        route.put("routeVersion", version);
        route.put("updatedAt", updatedAt);
        return route;
    }

    private ObjectNode emptyRoute(long version) {
        return emptyRoute(version, Instant.now(clock).toString());
    }

    private ObjectNode emptyRoute(long version, String updatedAt) {
        ObjectNode route = mapper.createObjectNode();
        route.putNull("defaultInstanceId");
        route.putNull("defaultReleaseId");
        route.putNull("defaultDeploymentGenerationId");
        route.put("routeVersion", version);
        route.put("updatedAt", updatedAt);
        return route;
    }

    private ObjectNode operation(String type, String phase, String candidateId) {
        ObjectNode operation = mapper.createObjectNode();
        operation.put("operationId", "op-" + UUID.randomUUID());
        operation.put("type", type);
        operation.put("phase", phase);
        if (candidateId == null) operation.putNull("candidateInstanceId");
        else operation.put("candidateInstanceId", candidateId);
        operation.put("startedAt", Instant.now(clock).toString());
        return operation;
    }

    private void scheduleNext(ObjectNode state) {
        if (currentOperation(state) != null) return;
        java.util.List<ServiceRef> queue = new java.util.ArrayList<>();
        for (JsonNode deployment : state.path("deployments")) {
            if (!"ACTIVE".equals(deployment.path("phase").asText())) continue;
            if (hasFailure(deployment)) continue;
            for (JsonNode service : deployment.path("services")) {
                boolean create = "CREATING".equals(service.path("phase").asText()) && service.path("operation").isNull();
                boolean update = "STABLE".equals(service.path("phase").asText())
                        && service.path("targetManifestVersion").asLong() != service.path("activeInstance").path("manifestVersion").asLong();
                if (create || update) queue.add(new ServiceRef((ObjectNode) deployment, (ObjectNode) service, create));
            }
        }
        queue.stream().min(Comparator.comparing((ServiceRef item) -> item.deployment().path("trafficScopeId").asText())
                .thenComparing(item -> item.service().path("serviceName").asText())).ifPresent(item -> {
            String instanceId = newInstanceId(item.deployment(), item.service());
            item.service().put("phase", item.create() ? "CREATING" : "UPDATING");
            item.service().set("operation", operation(item.create() ? "CREATE" : "UPDATE", "STARTING_CANDIDATE", instanceId));
        });
    }

    private void startNextDelete(ObjectNode deployment) {
        ArrayNode stored = (ArrayNode) deployment.path("services");
        for (int index = stored.size() - 1; index >= 0; index--) {
            JsonNode service = stored.path(index);
            if (service.path("activeInstance").isNull() && service.path("candidateInstance").isNull()
                    && service.path("drainingInstance").isNull()) stored.remove(index);
        }
        java.util.List<ObjectNode> services = new java.util.ArrayList<>();
        deployment.path("services").forEach(node -> services.add((ObjectNode) node));
        services.stream().filter(service -> service.path("operation").isNull())
                .min(Comparator.comparing(service -> service.path("serviceName").asText())).ifPresent(service -> {
                    service.put("phase", "DELETING");
                    service.putNull("failedManifestVersion");
                    service.putNull("lastError");
                    service.set("operation", operation("DELETE", "REMOVING_TRAFFIC", null));
                });
    }

    private void stable(ObjectNode service) {
        service.put("phase", "STABLE");
        service.putNull("operation");
        service.putNull("failedManifestVersion");
        service.putNull("lastError");
    }

    private void validateReplacement(JsonNode existing, JsonNode manifest) {
        if (!"ACTIVE".equals(existing.path("phase").asText()))
            throw new ControllerException("DEPLOYMENT_BUSY", "Deleting deployment cannot accept a manifest");
        if (hasOperation(existing)) throw new ControllerException("DEPLOYMENT_BUSY", "Deployment has an unfinished operation");
        for (JsonNode service : existing.path("services"))
            if ("FACTS_UNCERTAIN".equals(service.path("lastError").path("recoveryClass").asText()))
                throw new ControllerException("FACTS_UNCERTAIN", "Conflicting external facts must be repaired first");
        if (manifest.path("manifestVersion").asLong() <= existing.path("acceptedManifestVersion").asLong())
            throw new ControllerException("MANIFEST_VERSION_CONFLICT", "Manifest version must increase");
        JsonNode previous = store.readManifest(existing.path("deploymentId").asText());
        for (JsonNode oldSpec : previous.path("services")) {
            JsonNode next = findService(manifest, oldSpec.path("serviceName").asText());
            if (next == null) throw new ControllerException("SERVICE_REMOVAL_REQUIRES_DELETE", "Ordinary update cannot remove a service");
            if (!oldSpec.path("machineId").equals(next.path("machineId"))
                    || !oldSpec.path("runtime").path("containerPort").equals(next.path("runtime").path("containerPort"))
                    || !oldSpec.path("runtime").path("hostPorts").equals(next.path("runtime").path("hostPorts"))) {
                throw new ControllerException("SERVICE_LOCATION_IMMUTABLE", "Machine and fixed host ports cannot change during update");
            }
        }
    }

    private void assertNoOtherScopeOwner(JsonNode state, String deploymentId, String scope) {
        for (JsonNode deployment : state.path("deployments")) {
            if (scope.equals(deployment.path("trafficScopeId").asText())
                    && !deploymentId.equals(deployment.path("deploymentId").asText()))
                throw new ControllerException("TRAFFIC_SCOPE_CONFLICT", "Traffic scope already belongs to another deployment");
        }
    }

    private void assertManifestReservationsAvailable(JsonNode state, String deploymentId, JsonNode candidate) {
        Set<String> requested = new HashSet<>();
        for (JsonNode spec : candidate.path("services"))
            for (JsonNode port : spec.path("runtime").path("hostPorts"))
                requested.add(spec.path("machineId").asText() + ':' + port.asInt());
        for (JsonNode deployment : state.path("deployments")) {
            if (deploymentId.equals(deployment.path("deploymentId").asText())) continue;
            JsonNode manifest = store.readManifest(deployment.path("deploymentId").asText());
            for (JsonNode spec : manifest.path("services")) {
                for (JsonNode port : spec.path("runtime").path("hostPorts")) {
                    if (requested.contains(spec.path("machineId").asText() + ':' + port.asInt()))
                        throw new ControllerException("HOST_PORT_CONFLICT", "A fixed host port belongs to another deployment");
                }
            }
        }
    }

    private void requireNoOperation(JsonNode state) {
        if (currentOperation(state) != null) throw new ControllerException("CONTROLLER_BUSY", "Another service operation is running");
    }

    private boolean hasFailure(JsonNode parent) {
        if (parent.path("services").isArray()) {
            for (JsonNode service : parent.path("services"))
                if (!service.path("lastError").isNull()) return true;
            return false;
        }
        for (JsonNode deployment : parent.path("deployments"))
            if (hasFailure(deployment)) return true;
        return false;
    }

    private boolean hasOperation(JsonNode deployment) {
        for (JsonNode service : deployment.path("services")) if (!service.path("operation").isNull()) return true;
        return false;
    }

    private OperationRef currentOperation(JsonNode state) {
        OperationRef found = null;
        for (JsonNode deployment : state.path("deployments")) {
            for (JsonNode service : deployment.path("services")) {
                JsonNode op = service.path("operation");
                if (op.isNull()) continue;
                if (found != null) throw new ControllerException("STATE_INVALID", "More than one operation is active");
                found = new OperationRef(deployment.path("deploymentId").asText(), deployment.path("trafficScopeId").asText(),
                        service.path("serviceName").asText(), op.path("operationId").asText(), op.path("type").asText(),
                        op.path("phase").asText(), op.path("candidateInstanceId").asText(null), service.deepCopy());
            }
        }
        return found;
    }

    private ObjectNode checkedService(JsonNode state, OperationRef ref) {
        ObjectNode deployment = requireDeployment(state, ref.deploymentId());
        ObjectNode service = requireService(deployment, ref.serviceName());
        JsonNode operation = service.path("operation");
        if (!ref.operationId().equals(operation.path("operationId").asText())
                || !ref.phase().equals(operation.path("phase").asText()))
            throw new ControllerException("OPERATION_CHANGED", "Operation changed while an external step was running");
        return service;
    }

    private ObjectNode requireDeployment(JsonNode state, String deploymentId) {
        JsonNode deployment = findDeployment(state, deploymentId);
        if (deployment == null) throw new ControllerException("DEPLOYMENT_NOT_FOUND", "Deployment does not exist");
        return (ObjectNode) deployment;
    }

    private ObjectNode requireService(JsonNode deployment, String name) {
        JsonNode service = findService(deployment, name);
        if (service == null) throw new ControllerException("SERVICE_NOT_FOUND", "Service does not exist");
        return (ObjectNode) service;
    }

    private JsonNode findDeployment(JsonNode state, String id) {
        for (JsonNode deployment : state.path("deployments"))
            if (id.equals(deployment.path("deploymentId").asText())) return deployment;
        return null;
    }

    private JsonNode findService(JsonNode parent, String name) {
        for (JsonNode service : parent.path("services"))
            if (name.equals(service.path("serviceName").asText())) return service;
        return null;
    }

    private void removeService(ObjectNode deployment, String name) {
        ArrayNode services = (ArrayNode) deployment.path("services");
        for (int i = 0; i < services.size(); i++) if (name.equals(services.path(i).path("serviceName").asText())) { services.remove(i); return; }
    }

    private void removeDeployment(ObjectNode state, String id) {
        ArrayNode deployments = (ArrayNode) state.path("deployments");
        for (int i = 0; i < deployments.size(); i++) if (id.equals(deployments.path(i).path("deploymentId").asText())) { deployments.remove(i); return; }
    }

    private String newInstanceId(JsonNode deployment, JsonNode service) {
        return "i-" + prefix(deployment.path("deploymentId").asText(), 20) + '-'
                + prefix(service.path("serviceName").asText(), 20) + '-'
                + UUID.randomUUID().toString().substring(0, 12);
    }

    private String prefix(String value, int length) { return value.substring(0, Math.min(length, value.length())); }

    private String readSecret(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file))
                throw new ControllerException("SECRET_FILE_INVALID", "Required secret file is missing or unsafe");
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
                if (permissions.stream().anyMatch(permission -> permission != PosixFilePermission.OWNER_READ
                        && permission != PosixFilePermission.OWNER_WRITE))
                    throw new ControllerException("SECRET_FILE_PERMISSIONS", "Secret file permissions are wider than 0600");
            } catch (UnsupportedOperationException ignored) { }
            String value = Files.readString(file);
            if (value.length() < 32 || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl))
                throw new ControllerException("SECRET_FILE_INVALID", "Secret file content is invalid");
            return value;
        } catch (IOException exception) {
            throw new ControllerException("SECRET_FILE_READ_FAILED", "Unable to read required secret file", exception);
        }
    }

    private String safeMessage(Throwable failure) { return sanitize(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); }
    private String sanitize(String value) {
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (clean.isEmpty()) clean = "External operation failed";
        return clean.substring(0, Math.min(1024, clean.length()));
    }

    private record OperationRef(String deploymentId, String trafficScopeId, String serviceName,
                                String operationId, String type, String phase, String candidateInstanceId,
                                JsonNode service) {}
    private record ServiceRef(ObjectNode deployment, ObjectNode service, boolean create) {}
}
