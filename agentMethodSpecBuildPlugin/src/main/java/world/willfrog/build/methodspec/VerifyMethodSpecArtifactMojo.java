package world.willfrog.build.methodspec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo(name = "verify-artifact", defaultPhase = LifecyclePhase.VERIFY)
public class VerifyMethodSpecArtifactMojo extends AbstractMojo {

    private static final Pattern CANONICAL_JSON = Pattern.compile("finance/method-specs/v1/(?!index\\.json$|resolver-catalog\\.json$)([a-z_]+)\\.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}.jar")
    private String artifactJar;

    @Parameter
    private String previousReleaseJar;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path jarPath = Paths.get(artifactJar);
        if (!Files.exists(jarPath)) {
            throw new MojoFailureException("Artifact jar not found: " + jarPath);
        }

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            verifyNoSourceArtifacts(jarFile);
            Map<String, JsonNode> specs = loadCanonicalSpecs(jarFile);
            verifyDigests(specs);
            verifyIndex(jarFile, specs);

            if (previousReleaseJar != null && !previousReleaseJar.isBlank()) {
                Path previousPath = Paths.get(previousReleaseJar);
                if (Files.exists(previousPath)) {
                    verifyPreviousRelease(jarFile, previousPath);
                } else {
                    getLog().warn("Previous release jar not found, skipping historical check: " + previousPath);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to verify artifact", e);
        }

        getLog().info("MethodSpec artifact verification passed for " + jarPath);
    }

    private void verifyNoSourceArtifacts(JarFile jarFile) throws MojoFailureException {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("finance/method-specs/") && name.endsWith(".yaml")) {
                throw new MojoFailureException("Runtime jar must not contain YAML source: " + name);
            }
            if (name.startsWith("finance/method-specs/schema/")) {
                throw new MojoFailureException("Runtime jar must not contain JSON schema: " + name);
            }
        }
    }

    private Map<String, JsonNode> loadCanonicalSpecs(JarFile jarFile) throws MojoFailureException {
        Map<String, JsonNode> specs = new HashMap<>();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            Matcher matcher = CANONICAL_JSON.matcher(entry.getName());
            if (matcher.matches()) {
                try (InputStream is = jarFile.getInputStream(entry)) {
                    JsonNode node = MAPPER.readTree(is);
                    specs.put(node.path("methodId").asText(), node);
                } catch (IOException e) {
                    throw new MojoFailureException("Failed to read " + entry.getName() + ": " + e.getMessage());
                }
            }
        }
        if (specs.isEmpty()) {
            throw new MojoFailureException("No canonical method specs found in jar");
        }
        return specs;
    }

    private void verifyIndex(JarFile jarFile, Map<String, JsonNode> specs) throws MojoFailureException {
        JarEntry indexEntry = jarFile.getJarEntry("finance/method-specs/v1/index.json");
        if (indexEntry == null) {
            throw new MojoFailureException("Missing index.json in jar");
        }
        try (InputStream is = jarFile.getInputStream(indexEntry)) {
            JsonNode index = MAPPER.readTree(is);
            if (!index.isArray()) {
                throw new MojoFailureException("index.json must be an array");
            }
            Set<String> indexedIds = new HashSet<>();
            for (JsonNode entry : index) {
                String methodId = entry.path("methodId").asText(null);
                String version = entry.path("version").asText(null);
                String digest = entry.path("specDigest").asText(null);
                String resourcePath = entry.path("resourcePath").asText(null);
                if (methodId == null || version == null || digest == null || resourcePath == null) {
                    throw new MojoFailureException("index.json entry missing required field: " + entry);
                }
                JsonNode spec = specs.get(methodId);
                if (spec == null) {
                    throw new MojoFailureException("index.json references missing spec: " + methodId);
                }
                if (!version.equals(spec.path("version").asText(null))) {
                    throw new MojoFailureException("index.json version mismatch for " + methodId);
                }
                if (!digest.equals(spec.path("specDigest").asText(null))) {
                    throw new MojoFailureException("index.json digest mismatch for " + methodId);
                }
                if (!resourcePath.equals("finance/method-specs/v1/" + methodId.substring(methodId.lastIndexOf('.') + 1) + ".json")) {
                    throw new MojoFailureException("index.json resourcePath mismatch for " + methodId);
                }
                indexedIds.add(methodId);
            }
            if (!indexedIds.equals(specs.keySet())) {
                throw new MojoFailureException("index.json does not cover all canonical specs; indexed="
                        + indexedIds + ", specs=" + specs.keySet());
            }
        } catch (IOException e) {
            throw new MojoFailureException("Failed to read index.json: " + e.getMessage());
        }
    }

    private void verifyDigests(Map<String, JsonNode> specs) throws MojoFailureException {
        for (Map.Entry<String, JsonNode> entry : specs.entrySet()) {
            String methodId = entry.getKey();
            JsonNode spec = entry.getValue();
            String embeddedDigest = spec.path("specDigest").asText(null);
            if (embeddedDigest == null || !embeddedDigest.startsWith("sha256:")) {
                throw new MojoFailureException("Invalid or missing specDigest for " + methodId);
            }
            JsonNode copy = spec.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("specDigest");
            byte[] canonicalBytes;
            try {
                canonicalBytes = MethodSpecCanonicalizer.canonicalBytes(MAPPER.treeToValue(copy, Map.class));
            } catch (IOException e) {
                throw new MojoFailureException("Failed to canonicalize " + methodId + ": " + e.getMessage());
            }
            String recomputed = MethodSpecCanonicalizer.digestForCanonicalBytes(canonicalBytes);
            if (!recomputed.equals(embeddedDigest)) {
                throw new MojoFailureException("Digest mismatch for " + methodId
                        + ": embedded=" + embeddedDigest + ", recomputed=" + recomputed);
            }
        }
    }

    private void verifyPreviousRelease(JarFile currentJar, Path previousPath) throws IOException, MojoFailureException {
        try (JarFile previousJar = new JarFile(previousPath.toFile())) {
            Enumeration<JarEntry> entries = previousJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                Matcher matcher = CANONICAL_JSON.matcher(entry.getName());
                if (!matcher.matches()) {
                    continue;
                }
                try (InputStream is = previousJar.getInputStream(entry)) {
                    JsonNode previous = MAPPER.readTree(is);
                    String methodId = previous.path("methodId").asText();
                    String version = previous.path("version").asText();
                    JarEntry currentEntry = currentJar.getJarEntry(entry.getName());
                    if (currentEntry == null) {
                        continue;
                    }
                    try (InputStream currentIs = currentJar.getInputStream(currentEntry)) {
                        JsonNode current = MAPPER.readTree(currentIs);
                        if (methodId.equals(current.path("methodId").asText())
                                && version.equals(current.path("version").asText())) {
                            String previousDigest = previous.path("specDigest").asText();
                            String currentDigest = current.path("specDigest").asText();
                            if (!previousDigest.equals(currentDigest)) {
                                throw new MojoFailureException("Method " + methodId + "@" + version
                                        + " changed content compared to previous release");
                            }
                        }
                    }
                }
            }
        }
    }
}
