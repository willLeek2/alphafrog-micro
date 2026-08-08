package world.willfrog.build.methodspec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodSpecCompilerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path resource(String path) {
        return Path.of("src/test/resources").resolve(path);
    }

    @Test
    void validSpecsCompileToExpectedCanonicalJsonAndDigest() throws Exception {
        Path schema = resource("finance/method-specs/schema/method-spec-v1.schema.json");
        Path specsDir = resource("finance/method-specs/v1");

        MethodSpecCompiler.CompileResult result = MethodSpecCompiler.compile(
                schema, specsDir, null, tempDir);

        assertEquals(2, result.specs().size());

        Map<String, Object> cagr = result.specs().get(0);
        assertEquals("finance.growth.cagr", cagr.get("methodId"));

        byte[] canonicalBytes = MethodSpecCanonicalizer.canonicalBytes(cagr);
        String expectedDigest = MethodSpecCanonicalizer.digestForCanonicalBytes(canonicalBytes);
        String actualDigest = (String) cagr.get("specDigest");
        assertEquals(expectedDigest, actualDigest);
        assertTrue(actualDigest.startsWith("sha256:"));

        Path cagrJson = tempDir.resolve("finance/method-specs/v1/cagr.json");
        assertTrue(Files.exists(cagrJson));
        String written = Files.readString(cagrJson, StandardCharsets.UTF_8);
        assertTrue(written.contains("\"specDigest\":\"" + actualDigest + "\""));
        assertTrue(written.contains("\"复合年均增长率\""));

        Path index = tempDir.resolve("finance/method-specs/v1/index.json");
        assertTrue(Files.exists(index));
        var indexEntries = MAPPER.readTree(index.toFile());
        assertEquals(2, indexEntries.size());

        Path catalog = tempDir.resolve("finance/method-specs/v1/resolver-catalog.json");
        assertTrue(Files.exists(catalog));
        var catalogEntries = MAPPER.readTree(catalog.toFile());
        assertEquals(2, catalogEntries.size());
        assertTrue(catalogEntries.get(0).has("aliases"));
        assertTrue(catalogEntries.get(0).has("commonPhrases"));
        assertTrue(catalogEntries.get(0).has("clarificationDimensions"));
        assertTrue(catalogEntries.get(0).has("specDigest"));
        assertTrue(!catalogEntries.get(0).has("definition"));
        assertTrue(!catalogEntries.get(0).has("parameters"));
        assertTrue(!catalogEntries.get(0).has("sources"));
        assertTrue(!catalogEntries.get(0).has("library"));

        for (int i = 0; i < indexEntries.size(); i++) {
            assertEquals(indexEntries.get(i).get("methodId").asText(), catalogEntries.get(i).get("methodId").asText());
            assertEquals(indexEntries.get(i).get("version").asText(), catalogEntries.get(i).get("version").asText());
            assertEquals(indexEntries.get(i).get("specDigest").asText(), catalogEntries.get(i).get("specDigest").asText());
        }
    }

    @Test
    void unknownFieldFailsSchemaValidation() {
        Path schema = resource("finance/method-specs/schema/method-spec-v1.schema.json");
        Path specsDir = resource("bad-specs/unknown-field");

        MethodSpecBuildException ex = assertThrows(MethodSpecBuildException.class,
                () -> MethodSpecCompiler.compile(schema, specsDir, null, tempDir));
        assertTrue(ex.getMessage().contains("Schema validation failed"));
    }

    @Test
    void duplicateAliasAcrossMethodsFails() {
        Path schema = resource("finance/method-specs/schema/method-spec-v1.schema.json");
        Path specsDir = resource("bad-specs/alias-conflict");

        MethodSpecBuildException ex = assertThrows(MethodSpecBuildException.class,
                () -> MethodSpecCompiler.compile(schema, specsDir, null, tempDir));
        assertTrue(ex.getMessage().contains("Duplicate resolver alias"));
    }

    @Test
    void missingKnowledgeReferenceFails() throws Exception {
        Path schema = resource("finance/method-specs/schema/method-spec-v1.schema.json");
        Path specsDir = resource("bad-specs/missing-knowledge");
        Path knowledgeDir = tempDir.resolve("empty-knowledge");
        Files.createDirectories(knowledgeDir);

        MethodSpecBuildException ex = assertThrows(MethodSpecBuildException.class,
                () -> MethodSpecCompiler.compile(schema, specsDir, knowledgeDir, tempDir.resolve("out")));
        assertTrue(ex.getMessage().contains("Missing knowledge document"));
    }

    @Test
    void knowledgeHeaderDigestMismatchFails() throws Exception {
        Path schema = resource("finance/method-specs/schema/method-spec-v1.schema.json");
        Path specsDir = resource("finance/method-specs/v1");
        Path knowledgeDir = tempDir.resolve("knowledge");
        Files.createDirectories(knowledgeDir);

        // Compute actual digests so we can corrupt only CAGR and keep annualized valid.
        MethodSpecCompiler.CompileResult precompile = MethodSpecCompiler.compile(
                schema, specsDir, null, tempDir.resolve("pre"));
        Map<String, Object> cagr = precompile.specs().stream()
                .filter(s -> "finance.growth.cagr".equals(s.get("methodId")))
                .findFirst().orElseThrow();
        Map<String, Object> vol = precompile.specs().stream()
                .filter(s -> "finance.risk.annualized_volatility".equals(s.get("methodId")))
                .findFirst().orElseThrow();
        String cagrDigest = (String) cagr.get("specDigest");
        String volDigest = (String) vol.get("specDigest");

        Files.writeString(knowledgeDir.resolve("cagr.md"),
                "---\nmethodId: finance.growth.cagr\nversion: 1.0.0\nspecDigest: "
                        + cagrDigest + "-tampered\n---\n", StandardCharsets.UTF_8);
        Files.writeString(knowledgeDir.resolve("annualized_volatility.md"),
                "---\nmethodId: finance.risk.annualized_volatility\nversion: 1.0.0\nspecDigest: "
                        + volDigest + "\n---\n", StandardCharsets.UTF_8);

        MethodSpecBuildException ex = assertThrows(MethodSpecBuildException.class,
                () -> MethodSpecCompiler.compile(schema, specsDir, knowledgeDir, tempDir.resolve("out")));
        assertTrue(ex.getMessage().contains("digest mismatch"));
    }

    @Test
    void duplicateBaseNameAcrossMethodsFails() {
        Path schema = resource("finance/method-specs/schema/method-spec-v1.schema.json");
        Path specsDir = resource("bad-specs/basename-collision");

        MethodSpecBuildException ex = assertThrows(MethodSpecBuildException.class,
                () -> MethodSpecCompiler.compile(schema, specsDir, null, tempDir));
        assertTrue(ex.getMessage().contains("resourcePath collision"));
        assertTrue(ex.getMessage().contains("finance.growth.cagr"));
        assertTrue(ex.getMessage().contains("finance.risk.cagr"));
        assertTrue(ex.getMessage().contains("finance/method-specs/v1/cagr.json"));
    }
}
