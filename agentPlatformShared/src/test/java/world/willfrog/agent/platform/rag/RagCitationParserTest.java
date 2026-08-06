package world.willfrog.agent.platform.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagCitationParserTest {

    private final RagCitationParser parser = new RagCitationParser();

    @Test
    void parse_shouldReturnValidMarkersInRegistryOrder() {
        RagCitationParseResult result = parser.parse(
                "答案 <rag-cite ref=\"rag_ref_001\" /> 和 <rag-cite ref=\"rag_ref_002\" />",
                Set.of("rag_ref_001", "rag_ref_002")
        );

        assertEquals(List.of("rag_ref_001", "rag_ref_002"), result.citedRefs());
        assertEquals(List.of(), result.invalidCitedRefs());
        assertEquals(0, result.invalidCitationsIgnored());
    }

    @Test
    void parse_shouldIgnoreSquareBracketCitationPath() {
        RagCitationParseResult result = parser.parse(
                "答案 [rag_ref_001] [1]",
                Set.of("rag_ref_001")
        );

        assertEquals(List.of(), result.citedRefs());
        assertEquals(List.of(), result.invalidCitedRefs());
        assertEquals(0, result.invalidCitationsIgnored());
    }

    @Test
    void parse_shouldCountInvalidRefFormatAsIgnored() {
        RagCitationParseResult result = parser.parse(
                "答案 <rag-cite ref=\"title_001\" />",
                Set.of("rag_ref_001")
        );

        assertEquals(List.of(), result.citedRefs());
        assertEquals(List.of(), result.invalidCitedRefs());
        assertEquals(1, result.invalidCitationsIgnored());
    }

    @Test
    void parse_shouldReportFormatValidRefsMissingFromRegistry() {
        RagCitationParseResult result = parser.parse(
                "答案 <rag-cite ref=\"rag_ref_003\" />",
                Set.of("rag_ref_001")
        );

        assertEquals(List.of(), result.citedRefs());
        assertEquals(List.of("rag_ref_003"), result.invalidCitedRefs());
        assertEquals(0, result.invalidCitationsIgnored());
    }
}
