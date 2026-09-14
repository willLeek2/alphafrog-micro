package world.willfrog.build.methodspec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodSpecArtifactVerifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path resource(String path) {
        return Path.of("src/test/resources").resolve(path);
    }

    private Map<String, Object> compileOneSpec() throws Exception {
        Path schema = resource("finance/method-specs/schema/method-spec-v1.schema.json");
        Path specsDir = resource("finance/method-specs/v1");
        Path outDir = tempDir.resolve("compile");
        MethodSpecCompiler.CompileResult result = MethodSpecCompiler.compile(schema, specsDir, null, outDir);
        return result.specs().stream()
                .filter(s -> "finance.growth.cagr".equals(s.get("methodId")))
                .findFirst().orElseThrow();
    }

    @Test
    void jarWithCorrectArtifactsPasses() throws Exception {
        compileOneSpec();
        Path jar = tempDir.resolve("good.jar");
        createJarFromCompileOutput(jar, tempDir.resolve("compile"), false, false);

        VerifyMethodSpecArtifactMojo mojo = new VerifyMethodSpecArtifactMojo();
        mojo.setLog(new SystemStreamLog());
        setField(mojo, "artifactJar", jar.toString());

        mojo.execute();
    }

    @Test
    void jarWithTamperedDigestFails() throws Exception {
        compileOneSpec();
        Path jar = tempDir.resolve("tampered.jar");
        createJarFromCompileOutput(jar, tempDir.resolve("compile"), true, false);

        VerifyMethodSpecArtifactMojo mojo = new VerifyMethodSpecArtifactMojo();
        mojo.setLog(new SystemStreamLog());
        setField(mojo, "artifactJar", jar.toString());

        MojoFailureException ex = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("Digest mismatch"));
    }

    @Test
    void jarContainingYamlFails() throws Exception {
        compileOneSpec();
        Path jar = tempDir.resolve("with-yaml.jar");
        createJarFromCompileOutput(jar, tempDir.resolve("compile"), false, true);

        VerifyMethodSpecArtifactMojo mojo = new VerifyMethodSpecArtifactMojo();
        mojo.setLog(new SystemStreamLog());
        setField(mojo, "artifactJar", jar.toString());

        MojoFailureException ex = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("must not contain YAML source"));
    }

    private void createJarFromCompileOutput(Path jar, Path compileDir, boolean tamperDigest, boolean includeYaml) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            List<Path> files = new ArrayList<>();
            collectFiles(compileDir, files);
            for (Path file : files) {
                String entryName = compileDir.relativize(file).toString().replace('\\', '/');
                byte[] bytes = Files.readAllBytes(file);
                if (tamperDigest && entryName.endsWith("/cagr.json")) {
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    content = content.replace("\"specDigest\":\"sha256:", "\"specDigest\":\"sha256:tampered");
                    bytes = content.getBytes(StandardCharsets.UTF_8);
                }
                JarEntry entry = new JarEntry(entryName);
                jos.putNextEntry(entry);
                jos.write(bytes);
                jos.closeEntry();
            }
            if (includeYaml) {
                JarEntry yamlEntry = new JarEntry("finance/method-specs/v1/cagr.yaml");
                jos.putNextEntry(yamlEntry);
                jos.write("schemaVersion: \"1\"\n".getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
    }

    private void collectFiles(Path dir, List<Path> files) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = VerifyMethodSpecArtifactMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
