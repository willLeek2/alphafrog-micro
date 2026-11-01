package world.willfrog.alphafrogmicro.frontend.controller.agent;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentHttpBoundaryTest {





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

    private boolean hasHttpMapping(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }

    private List<RequestParameterContract> requestParameters(Method method) {
        return Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestParam.class))
                .filter(java.util.Objects::nonNull)
                .map(parameter -> parameter(parameter.value(), parameter.defaultValue()))
                .toList();
    }

    private RequestParameterContract parameter(String name, String defaultValue) {
        return new RequestParameterContract(name, defaultValue);
    }

    private record RequestParameterContract(String name, String defaultValue) {
    }

    private static final class ListHolder {
        private static final java.util.List<Class<?>> BOUNDARIES = java.util.List.of(
                AgentObservabilityController.class,
                AgentArtifactController.class,
                AgentMessageController.class
        );
    }
}
