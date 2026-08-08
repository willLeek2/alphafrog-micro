package world.willfrog.build.methodspec;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.ValidationMessage;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class MethodSpecCompiler {

    private static final String KNOWLEDGE_REF = "^method-knowledge:([^@]+)@(.+)$";
    private static final Pattern KNOWLEDGE_PATTERN = Pattern.compile(KNOWLEDGE_REF);
    private static final Yaml YAML = new Yaml();

    private MethodSpecCompiler() {
    }

    public record CompileResult(
            List<Map<String, Object>> specs,
            Map<String, String> canonicalJsons,
            Map<String, String> knowledgeIndex,
            String combinedKnowledge
    ) {
    }

    public static CompileResult compile(
            Path schemaPath,
            Path specsDirectory,
            Path knowledgeDirectory,
            Path outputDirectory
    ) throws MethodSpecBuildException, IOException {

        JsonSchema schema = loadSchema(schemaPath);
        List<Path> specFiles = listYamlFiles(specsDirectory);
        List<Map<String, Object>> specs = new ArrayList<>(specFiles.size());
        for (Path file : specFiles) {
            Map<String, Object> spec = loadYaml(file);
            validateAgainstSchema(schema, spec, file.getFileName().toString());
            specs.add(spec);
        }
        specs.sort(Comparator.comparing(s -> String.valueOf(s.get("methodId"))));

        boolean knowledgeDirExists = knowledgeDirectory != null && Files.exists(knowledgeDirectory);
        Map<String, String> availableKnowledge = knowledgeDirExists
                ? loadKnowledgeHeaders(knowledgeDirectory)
                : null;

        MethodSpecCrossValidator.validate(specs, availableKnowledge);

        Map<String, String> canonicalJsons = new LinkedHashMap<>();
        for (Map<String, Object> spec : specs) {
            String canonical = MethodSpecCanonicalizer.canonicalJsonWithDigest(spec);
            String methodId = String.valueOf(spec.get("methodId"));
            String digest = extractDigest(canonical);
            spec.put("specDigest", digest);
            canonicalJsons.put(methodId, canonical);
        }

        Map<String, String> knowledgeIndex;
        String combinedKnowledge;
        if (knowledgeDirectory == null || !Files.exists(knowledgeDirectory)) {
            knowledgeIndex = Collections.emptyMap();
            combinedKnowledge = null;
        } else {
            knowledgeIndex = MethodSpecCrossValidator.buildKnowledgeIndex(availableKnowledge, specs);
            combinedKnowledge = combineKnowledgeDocs(knowledgeDirectory, specs);
        }

        writeOutputs(outputDirectory, specs, canonicalJsons, knowledgeIndex, combinedKnowledge);

        return new CompileResult(specs, canonicalJsons, knowledgeIndex, combinedKnowledge);
    }

    private static JsonSchema loadSchema(Path schemaPath) throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .pathType(PathType.JSON_POINTER)
                .build();
        // The committed schema uses a bare filename as $id, which is not a valid absolute URI.
        // We remove $id before loading so the source file content remains unchanged.
        String schemaJson = Files.readString(schemaPath, StandardCharsets.UTF_8);
        JsonNode schemaNode = VALIDATION_MAPPER.readTree(schemaJson);
        if (schemaNode instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode) {
            objectNode.remove("$id");
        }
        return factory.getSchema(schemaNode, config);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper VALIDATION_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static void validateAgainstSchema(JsonSchema schema, Map<String, Object> spec, String fileName) throws MethodSpecBuildException {
        JsonNode node = VALIDATION_MAPPER.convertValue(spec, JsonNode.class);
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            throw new MethodSpecBuildException("Schema validation failed for " + fileName + ": " + errors);
        }
    }

    private static List<Path> listYamlFiles(Path directory) throws IOException, MethodSpecBuildException {
        if (!Files.exists(directory)) {
            throw new MethodSpecBuildException("Method-spec directory does not exist: " + directory);
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path file) throws MethodSpecBuildException {
        try (InputStream is = Files.newInputStream(file)) {
            Object loaded = YAML.load(is);
            if (!(loaded instanceof Map)) {
                throw new MethodSpecBuildException("YAML root must be a map: " + file);
            }
            return (Map<String, Object>) loaded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> loadKnowledgeHeaders(Path knowledgeDirectory) throws IOException, MethodSpecBuildException {
        if (!Files.exists(knowledgeDirectory)) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        try (Stream<Path> stream = Files.list(knowledgeDirectory)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path file : files) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                KnowledgeHeader header = parseKnowledgeHeader(content, file.getFileName().toString());
                if (header != null) {
                    result.put(header.methodId() + "@" + header.version(), header.specDigest());
                }
            }
        }
        return result;
    }

    private static KnowledgeHeader parseKnowledgeHeader(String content, String fileName) throws MethodSpecBuildException {
        if (!content.startsWith("---")) {
            return null;
        }
        int end = content.indexOf("---", 3);
        if (end < 0) {
            throw new MethodSpecBuildException("Unclosed YAML frontmatter in " + fileName);
        }
        String frontMatter = content.substring(3, end).trim();
        if (frontMatter.isEmpty()) {
            return null;
        }
        Map<String, Object> map = YAML.load(frontMatter);
        if (map == null) {
            return null;
        }
        String methodId = String.valueOf(map.get("methodId"));
        String version = String.valueOf(map.get("version"));
        String specDigest = String.valueOf(map.get("specDigest"));
        if (methodId == null || version == null || specDigest == null
                || "null".equals(methodId) || "null".equals(version) || "null".equals(specDigest)) {
            throw new MethodSpecBuildException("Knowledge header missing methodId/version/specDigest in " + fileName);
        }
        return new KnowledgeHeader(methodId, version, specDigest);
    }

    private record KnowledgeHeader(String methodId, String version, String specDigest) {
    }

    private static String combineKnowledgeDocs(Path knowledgeDirectory, List<Map<String, Object>> specs) throws IOException, MethodSpecBuildException {
        Map<String, Map<String, Object>> specById = new LinkedHashMap<>();
        for (Map<String, Object> spec : specs) {
            specById.put(String.valueOf(spec.get("methodId")), spec);
        }

        StringBuilder combined = new StringBuilder();
        try (Stream<Path> stream = Files.list(knowledgeDirectory)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path file : files) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                KnowledgeHeader header = parseKnowledgeHeader(content, file.getFileName().toString());
                if (header == null) {
                    continue;
                }
                Map<String, Object> spec = specById.get(header.methodId());
                if (spec == null) {
                    throw new MethodSpecBuildException("Knowledge doc " + file.getFileName()
                            + " references unknown method " + header.methodId());
                }
                String expectedVersion = String.valueOf(spec.get("version"));
                String expectedDigest = String.valueOf(spec.get("specDigest"));
                if (!expectedVersion.equals(header.version())) {
                    throw new MethodSpecBuildException("Knowledge version mismatch for " + header.methodId()
                            + ": expected " + expectedVersion + ", found " + header.version());
                }
                if (!expectedDigest.equals(header.specDigest())) {
                    throw new MethodSpecBuildException("Knowledge digest mismatch for " + header.methodId()
                            + ": expected " + expectedDigest + ", found " + header.specDigest());
                }

                String body = content.substring(content.indexOf("---", 3) + 3).stripLeading();
                String anchor = header.methodId().replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
                combined.append("## ").append(header.methodId()).append("\n\n");
                combined.append("<a id=\"").append(anchor).append("\"></a>\n\n");
                combined.append(body).append("\n\n");
            }
        }
        return combined.toString();
    }

    private static void writeOutputs(
            Path outputDirectory,
            List<Map<String, Object>> specs,
            Map<String, String> canonicalJsons,
            Map<String, String> knowledgeIndex,
            String combinedKnowledge
    ) throws IOException {

        Path specsV1Dir = outputDirectory.resolve("finance/method-specs/v1");
        Path knowledgeV1Dir = outputDirectory.resolve("finance/method-knowledge/v1");
        Path guidesDir = outputDirectory.resolve("agent_guides");

        Files.createDirectories(specsV1Dir);
        Files.createDirectories(knowledgeV1Dir);
        Files.createDirectories(guidesDir);

        List<Map<String, Object>> indexEntries = new ArrayList<>();
        List<Map<String, Object>> resolverCatalog = new ArrayList<>();
        List<Map<String, Object>> knowledgeEntries = new ArrayList<>();

        for (Map<String, Object> spec : specs) {
            String methodId = String.valueOf(spec.get("methodId"));
            String version = String.valueOf(spec.get("version"));
            String resourcePath = "finance/method-specs/v1/" + baseName(methodId) + ".json";
            String canonical = canonicalJsons.get(methodId);

            Path jsonFile = specsV1Dir.resolve(baseName(methodId) + ".json");
            Files.writeString(jsonFile, canonical, StandardCharsets.UTF_8);

            Map<String, Object> entry = new TreeMap<>();
            entry.put("methodId", methodId);
            entry.put("version", version);
            String digest = extractDigest(canonical);
            entry.put("specDigest", digest);
            entry.put("resourcePath", resourcePath);
            indexEntries.add(entry);

            Map<String, Object> resolverEntry = new TreeMap<>();
            resolverEntry.put("methodId", methodId);
            resolverEntry.put("version", version);
            resolverEntry.put("displayName", spec.get("displayName"));
            Map<String, Object> hints = (Map<String, Object>) spec.get("resolverHints");
            if (hints != null) {
                resolverEntry.put("aliases", hints.getOrDefault("aliases", Collections.emptyList()));
                resolverEntry.put("commonPhrases", hints.getOrDefault("commonPhrases", Collections.emptyList()));
                resolverEntry.put("clarificationDimensions", hints.getOrDefault("clarificationDimensions", Collections.emptyList()));
            } else {
                resolverEntry.put("aliases", Collections.emptyList());
                resolverEntry.put("commonPhrases", Collections.emptyList());
                resolverEntry.put("clarificationDimensions", Collections.emptyList());
            }
            resolverCatalog.add(resolverEntry);

            if (knowledgeIndex.containsKey(methodId + "@" + version)) {
                Map<String, Object> knowledgeEntry = new TreeMap<>();
                knowledgeEntry.put("methodId", methodId);
                knowledgeEntry.put("version", version);
                knowledgeEntry.put("specDigest", digest);
                knowledgeEntry.put("document", "agent_guides/finance_method_knowledge.md");
                knowledgeEntry.put("section", headerAnchor(methodId));
                knowledgeEntries.add(knowledgeEntry);
            }
        }

        Files.writeString(specsV1Dir.resolve("index.json"),
                serializePretty(indexEntries), StandardCharsets.UTF_8);
        Files.writeString(specsV1Dir.resolve("resolver-catalog.json"),
                serializePretty(resolverCatalog), StandardCharsets.UTF_8);
        Files.writeString(knowledgeV1Dir.resolve("index.json"),
                serializePretty(knowledgeEntries), StandardCharsets.UTF_8);

        if (combinedKnowledge != null) {
            Files.writeString(guidesDir.resolve("finance_method_knowledge.md"),
                    combinedKnowledge, StandardCharsets.UTF_8);
        }
    }

    private static String extractDigest(String canonicalJson) {
        int idx = canonicalJson.indexOf("\"specDigest\":\"");
        if (idx < 0) {
            throw new IllegalStateException("Canonical JSON missing specDigest");
        }
        int start = idx + "\"specDigest\":\"".length();
        int end = canonicalJson.indexOf('"', start);
        return canonicalJson.substring(start, end);
    }

    private static String baseName(String methodId) {
        return methodId.substring(methodId.lastIndexOf('.') + 1);
    }

    private static String headerAnchor(String methodId) {
        return "#" + methodId.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
    }

    private static String serializePretty(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
