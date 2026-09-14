package world.willfrog.agent.platform.rag;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses explicit RAG citation markers without touching legacy URL / [N] citation handling.
 */
public class RagCitationParser {

    private static final Pattern RAG_CITE_MARKER = Pattern.compile(
            "<rag-cite\\b(?=[^>]*\\bref\\s*=\\s*\"([^\"]*)\")[^>]*/>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RAG_REF_ID = Pattern.compile("rag_ref_\\d{3}");

    public RagCitationParseResult parse(String finalAnswerText, Set<String> visibleRefRegistry) {
        LinkedHashSet<String> citedRefs = new LinkedHashSet<>();
        LinkedHashSet<String> invalidCitedRefs = new LinkedHashSet<>();
        int invalidCitationsIgnored = 0;
        if (finalAnswerText == null || finalAnswerText.isBlank()) {
            return new RagCitationParseResult(List.copyOf(citedRefs), List.copyOf(invalidCitedRefs), 0);
        }
        Set<String> registry = visibleRefRegistry == null ? Set.of() : visibleRefRegistry;
        Matcher matcher = RAG_CITE_MARKER.matcher(finalAnswerText);
        while (matcher.find()) {
            String ref = matcher.group(1);
            if (ref == null || !RAG_REF_ID.matcher(ref).matches()) {
                invalidCitationsIgnored++;
                continue;
            }
            if (registry.contains(ref)) {
                citedRefs.add(ref);
            } else {
                invalidCitedRefs.add(ref);
            }
        }
        return new RagCitationParseResult(
                List.copyOf(citedRefs),
                List.copyOf(invalidCitedRefs),
                invalidCitationsIgnored
        );
    }
}
