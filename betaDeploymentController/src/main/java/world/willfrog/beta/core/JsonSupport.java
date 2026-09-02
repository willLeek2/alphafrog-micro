package world.willfrog.beta.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class JsonSupport {
    private JsonSupport() {}

    public static String sha256(ObjectMapper mapper, JsonNode value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String hexSha256(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String serviceSha256(ObjectMapper mapper, JsonNode service) {
        ObjectNode copy = service.deepCopy();
        copy.remove("serviceSpecSha256");
        return sha256(mapper, copy);
    }

    public static String deploymentGeneration(JsonNode manifest) {
        StringBuilder input = new StringBuilder("alphafrog-deployment-generation-v1\n")
                .append("manifest-version:").append(manifest.path("manifestVersion").asLong()).append('\n')
                .append("git-commit:").append(manifest.path("gitCommit").asText()).append('\n');
        List<JsonNode> services = new ArrayList<>();
        manifest.path("services").forEach(services::add);
        services.sort(Comparator.comparing(node -> node.path("serviceName").asText()));
        for (JsonNode service : services) {
            input.append("service:").append(service.path("serviceName").asText()).append('\0')
                    .append(service.path("image").path("repositoryDigest").asText()).append('\n');
        }
        return "gen-" + digest(input.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            fields.sort(Map.Entry.comparingByKey());
            StringBuilder result = new StringBuilder("{");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) result.append(',');
                Map.Entry<String, JsonNode> field = fields.get(i);
                result.append(quote(field.getKey())).append(':').append(canonical(field.getValue()));
            }
            return result.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder result = new StringBuilder("[");
            Iterator<JsonNode> values = node.elements();
            boolean first = true;
            while (values.hasNext()) {
                if (!first) result.append(',');
                first = false;
                result.append(canonical(values.next()));
            }
            return result.append(']').toString();
        }
        if (node.isTextual()) return quote(node.textValue());
        return node.toString();
    }

    private static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String digest(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
