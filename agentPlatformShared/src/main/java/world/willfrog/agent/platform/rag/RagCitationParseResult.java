package world.willfrog.agent.platform.rag;

import java.util.List;

public record RagCitationParseResult(
        List<String> citedRefs,
        List<String> invalidCitedRefs,
        int invalidCitationsIgnored
) {
}
