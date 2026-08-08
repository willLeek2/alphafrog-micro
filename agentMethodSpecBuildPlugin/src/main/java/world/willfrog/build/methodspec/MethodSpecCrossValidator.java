package world.willfrog.build.methodspec;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MethodSpecCrossValidator {

    private static final Pattern KNOWLEDGE_PATTERN = Pattern.compile("^method-knowledge:([^@]+)@(.+)$");

    private MethodSpecCrossValidator() {
    }

    public static void validate(List<Map<String, Object>> specs, Map<String, String> availableKnowledge) throws MethodSpecBuildException {
        Set<String> aliases = new HashSet<>();
        Set<String> seenIdentity = new HashSet<>();
        boolean enforceKnowledgeRefs = availableKnowledge != null;

        for (Map<String, Object> spec : specs) {
            String methodId = getString(spec, "methodId");
            String version = getString(spec, "version");
            if (methodId == null || version == null) {
                throw new MethodSpecBuildException("methodId and version are required");
            }

            String identity = methodId + "@" + version;
            if (!seenIdentity.add(identity)) {
                throw new MethodSpecBuildException("Duplicate methodId+version: " + identity);
            }

            Map<String, Object> resolverHints = (Map<String, Object>) spec.get("resolverHints");
            if (resolverHints != null) {
                List<Object> aliasList = (List<Object>) resolverHints.get("aliases");
                if (aliasList != null) {
                    for (Object aliasObj : aliasList) {
                        String alias = String.valueOf(aliasObj);
                        if (!aliases.add(alias)) {
                            throw new MethodSpecBuildException("Duplicate resolver alias across methods: " + alias);
                        }
                    }
                }
            }

            if (enforceKnowledgeRefs) {
                List<Object> sourceRefs = (List<Object>) spec.get("sourceRefs");
                if (sourceRefs != null) {
                    for (Object refObj : sourceRefs) {
                        String ref = String.valueOf(refObj);
                        Matcher matcher = KNOWLEDGE_PATTERN.matcher(ref);
                        if (matcher.matches()) {
                            String refMethodId = matcher.group(1);
                            String refVersion = matcher.group(2);
                            String expectedIdentity = refMethodId + "@" + refVersion;
                            if (!availableKnowledge.containsKey(expectedIdentity)) {
                                throw new MethodSpecBuildException("Missing knowledge document for " + ref
                                        + " (expected finance/method-knowledge/v1/" + refMethodId + ".md or similar)");
                            }
                        }
                    }
                }
            }
        }
    }

    public static Map<String, String> buildKnowledgeIndex(Map<String, String> availableKnowledge,
                                                          List<Map<String, Object>> specs) throws MethodSpecBuildException {
        Map<String, String> validated = new HashMap<>();
        for (Map<String, Object> spec : specs) {
            String methodId = getString(spec, "methodId");
            String version = getString(spec, "version");
            String digest = getString(spec, "specDigest");
            String identity = methodId + "@" + version;
            String knowledgeDigest = availableKnowledge.get(identity);
            if (knowledgeDigest != null) {
                if (digest == null || !digest.equals(knowledgeDigest)) {
                    throw new MethodSpecBuildException("Knowledge header digest mismatch for " + identity
                            + ": expected " + digest + ", found " + knowledgeDigest);
                }
                validated.put(identity, digest);
            }
        }
        return validated;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
