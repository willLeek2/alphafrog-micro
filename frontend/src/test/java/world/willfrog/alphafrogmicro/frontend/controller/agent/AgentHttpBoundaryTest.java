package world.willfrog.alphafrogmicro.frontend.controller.agent;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentHttpBoundaryTest {

    @Test
    void runObservabilityArtifactAndMessageMappingsHaveSeparateOwners() {
        Set<String> runMappings = mappings(AgentController.class);
        Set<String> observabilityMappings = mappings(AgentObservabilityController.class);
        Set<String> artifactMappings = mappings(AgentArtifactController.class);
        Set<String> messageMappings = mappings(AgentMessageController.class);

        assertTrue(runMappings.contains("POST /api/agent/runs"));
        assertTrue(runMappings.contains("GET /api/agent/runs/{runId}/status"));
        assertFalse(runMappings.stream().anyMatch(this::isMovedBoundary));

        assertEquals(Set.of(
                "GET /api/agent/runs/{runId}/events",
                "GET /api/agent/runs/{runId}/timeline",
                "GET /api/agent/runs/{runId}/observability/full",
                "GET /api/agent/runs/{runId}/traces",
                "GET /api/agent/runs/{runId}/traces/{traceId}",
                "GET /api/agent/runs/{runId}/llm-calls/{llmCallId}/detail",
                "GET /api/agent/runs/{runId}/tool-calls/{toolCallId}/detail",
                "GET /api/agent/runs/{runId}/traces/{traceId}/full/parts",
                "GET /api/agent/runs/{runId}/traces/{traceId}/full/parts/{partIndex}"
        ), observabilityMappings);
        assertEquals(Set.of(
                "GET /api/agent/runs/{runId}/snapshot/parts",
                "GET /api/agent/runs/{runId}/snapshot/parts/{partIndex}",
                "GET /api/agent/runs/{runId}/artifacts",
                "GET /api/agent/runs/{runId}/artifacts/{artifactId}/parts",
                "GET /api/agent/runs/{runId}/artifacts/{artifactId}/parts/{partIndex}",
                "GET /api/agent/runs/{runId}/artifacts/{artifactId}/download"
        ), artifactMappings);
        assertEquals(Set.of(
                "POST /api/agent/runs/{runId}/messages",
                "GET /api/agent/runs/{runId}/messages"
        ), messageMappings);
    }

    @Test
    void compatibilityBoundariesDelegateToTheSameSafeHandler() throws Exception {
        for (Class<?> boundary : ListHolder.BOUNDARIES) {
            var fields = Arrays.stream(boundary.getDeclaredFields())
                    .filter(field -> AgentController.class.equals(field.getType()))
                    .toList();
            assertEquals(1, fields.size(), boundary.getSimpleName() + " must have one safe handler delegate");
        }
    }

    @Test
    void agentApiControllersDoNotReintroducePrivateAuthResolversOrAdminConstants() {
        for (Class<?> controller : java.util.List.of(
                AgentController.class,
                AgentToolsController.class,
                AgentConfigController.class,
                AgentCreditController.class,
                AgentSseController.class)) {
            assertFalse(Arrays.stream(controller.getDeclaredMethods())
                            .anyMatch(method -> "resolveUserId".equals(method.getName())
                                    || "isAdmin".equals(method.getName())),
                    controller.getSimpleName() + " must use AgentAuthSupport");
            assertFalse(Arrays.stream(controller.getDeclaredFields())
                            .anyMatch(field -> "ADMIN_USER_TYPE".equals(field.getName())),
                    controller.getSimpleName() + " must not own the admin role constant");
        }
    }

    private Set<String> mappings(Class<?> controller) {
        Set<String> mappings = new LinkedHashSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            add(mappings, "GET", method.getAnnotation(GetMapping.class));
            add(mappings, "POST", method.getAnnotation(PostMapping.class));
            add(mappings, "PUT", method.getAnnotation(PutMapping.class));
            add(mappings, "DELETE", method.getAnnotation(DeleteMapping.class));
        }
        return mappings;
    }

    private void add(Set<String> mappings, String verb, Object annotation) {
        if (annotation instanceof GetMapping mapping) {
            Arrays.stream(mapping.value()).forEach(path -> mappings.add(verb + " " + path));
        } else if (annotation instanceof PostMapping mapping) {
            Arrays.stream(mapping.value()).forEach(path -> mappings.add(verb + " " + path));
        } else if (annotation instanceof PutMapping mapping) {
            Arrays.stream(mapping.value()).forEach(path -> mappings.add(verb + " " + path));
        } else if (annotation instanceof DeleteMapping mapping) {
            Arrays.stream(mapping.value()).forEach(path -> mappings.add(verb + " " + path));
        }
    }

    private boolean isMovedBoundary(String mapping) {
        return mapping.contains("/events")
                || mapping.contains("/timeline")
                || mapping.contains("/observability/")
                || mapping.contains("/traces")
                || mapping.contains("/llm-calls")
                || mapping.contains("/tool-calls")
                || mapping.contains("/snapshot/parts")
                || mapping.contains("/artifacts")
                || mapping.contains("/messages");
    }

    private static final class ListHolder {
        private static final java.util.List<Class<?>> BOUNDARIES = java.util.List.of(
                AgentObservabilityController.class,
                AgentArtifactController.class,
                AgentMessageController.class
        );
    }
}
