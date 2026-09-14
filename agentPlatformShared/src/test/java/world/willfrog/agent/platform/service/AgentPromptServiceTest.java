package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.agent.platform.util.PromptFileLoader;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AgentPromptService} dataFreshness snapshot behavior.
 */
@ExtendWith(MockitoExtension.class)
class AgentPromptServiceTest {

    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    @Mock
    private AgentLlmProperties properties;

    private AgentPromptService service;
    private Method composeSystemPrompt;

    @BeforeEach
    void setUp() throws Exception {
        AgentLlmProperties.Prompts prompts = new AgentLlmProperties.Prompts();
        lenient().when(properties.getPrompts()).thenReturn(prompts);
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        lenient().when(properties.getRuntime()).thenReturn(runtime);
        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());

        service = new AgentPromptService(properties, localConfigLoader);
        composeSystemPrompt = AgentPromptService.class.getDeclaredMethod("composeSystemPrompt", String.class);
        composeSystemPrompt.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void shouldUseAgentContextSnapshotWhenSet() throws Exception {
        // snapshot in AgentContext — live config should NOT be consulted
        AgentLlmProperties.DataFreshness snap = freshness("2020-01-01", "2026-06-24-snap", "2026-06-24", "frozen");
        AgentContext.setDataFreshness(snap);

        // properties.getDataFreshness() is NOT stubbed → if code reaches it, test correctly fails
        String composed = (String) composeSystemPrompt.invoke(service, "阶段指令");
        assertTrue(composed.contains("2026-06-24-snap"),
                "should use frozen snapshot endDate, got: " + composed);
    }

    @Test
    void shouldFallbackToLiveConfigWhenAgentContextNull() throws Exception {
        AgentContext.setDataFreshness(null);

        when(properties.getDataFreshness()).thenReturn(
                freshness("2020-01-01", "2026-12-31-fallback", "2026-12-31", "live"));

        String composed = (String) composeSystemPrompt.invoke(service, "阶段指令");
        assertTrue(composed.contains("2026-12-31-fallback"),
                "should fallback to live config endDate, got: " + composed);
    }

    @Test
    void shouldNotInjectFreshnessParagraphWhenNoConfigAtAll() throws Exception {
        AgentContext.setDataFreshness(null);
        when(properties.getDataFreshness()).thenReturn(null);

        String composed = (String) composeSystemPrompt.invoke(service, "阶段指令");
        // must still contain the global prompt even without data freshness
        assertTrue(composed.contains("专业金融分析代理"),
                "should still contain global instructions, got: " + composed);
    }

    @Test
    void snapshotDataFreshness_shouldMergeLocalOverridesBase() {
        AgentLlmProperties.DataFreshness base = freshness("2018-01-01", "2025-12-31-base", "2025-12-31", "base");
        AgentLlmProperties.DataFreshness local = freshness(null, "2026-06-24-local", null, null);
        AgentLlmProperties full = new AgentLlmProperties();
        full.setDataFreshness(local);

        when(properties.getDataFreshness()).thenReturn(base);
        when(localConfigLoader.current()).thenReturn(Optional.of(full));

        AgentLlmProperties.DataFreshness snap = service.snapshotDataFreshness();
        assertNotNull(snap);
        assertEquals("2026-06-24-local", snap.getEndDate()); // from local (Nacos)
        assertEquals("2018-01-01", snap.getStartDate()); // from base (static)
    }

    @Test
    void snapshotPromptSelection_shouldFreezeDefaultVersionAndDigests() {
        PromptRunSelection selection = service.snapshotPromptSelection("run-1", "user-1", "{}");

        assertEquals(PromptRunSelection.SCHEMA_VERSION, selection.schemaVersion());
        assertEquals("default-v1", selection.bundleVersion());
        assertEquals("control", selection.variant());
        assertFalse(selection.bundleDigest().isBlank());
        assertFalse(selection.capabilityCatalogDigest().isBlank());
        assertEquals(LocalDate.now(), selection.referenceDate());
        assertDoesNotThrow(() -> service.validatePromptSelection(selection));
    }

    @Test
    void bundleDigest_shouldCoverFollowUpSummaryAuthority() {
        String resource = "prompts/agent/follow_up_summary_system.txt";
        PromptAuthority baseline = PromptAuthority.forTesting(PromptFileLoader::load);
        PromptAuthority changed = PromptAuthority.forTesting(path -> resource.equals(path)
                ? PromptFileLoader.load(path) + "\n测试漂移"
                : PromptFileLoader.load(path));

        assertNotEquals(baseline.bundleDigest(), changed.bundleDigest(),
                "follow-up 摘要正文变化必须改变 Prompt bundle digest");
    }

    @Test
    void promptRendering_shouldUseFrozenRunReferenceDate() {
        PromptRunSelection current = service.snapshotPromptSelection("run-1", "user-1", "{}");
        AgentContext.setPromptRunSelection(new PromptRunSelection(
                current.schemaVersion(), current.bundleVersion(), current.variant(),
                current.bundleDigest(), current.capabilityCatalogDigest(), LocalDate.of(2025, 2, 3)));

        assertEquals("今天是2025年02月03日。", service.dynamicContextPrefix());
        assertTrue(service.reactSystemPrompt().startsWith("当前时间：2025年02月03日"));
    }

    @Test
    void promptRendering_shouldFailClosedOnFrozenDigestMismatch() {
        PromptRunSelection current = service.snapshotPromptSelection("run-1", "user-1", "{}");
        AgentContext.setPromptRunSelection(new PromptRunSelection(
                current.schemaVersion(), current.bundleVersion(), current.variant(),
                "sha256:mismatch", current.capabilityCatalogDigest(), current.referenceDate()));

        PromptConfigurationException error = assertThrows(
                PromptConfigurationException.class, service::reactSystemPrompt);
        assertTrue(error.getMessage().contains("prompt_selection_mismatch"));
    }

    private static AgentLlmProperties.DataFreshness freshness(String start, String end, String asOf, String desc) {
        AgentLlmProperties.DataFreshness f = new AgentLlmProperties.DataFreshness();
        f.setStartDate(start);
        f.setEndDate(end);
        f.setAsOfDate(asOf);
        f.setDescription(desc);
        return f;
    }

    @Test
    void financeMethodResolverTemplate_shouldRejectDivergentStaticProjection() {
        AgentLlmProperties.Prompts prompts = properties.getPrompts();
        prompts.setFinanceMethodResolverSystemPrompt("direct resolver template {{RESOLVER_CATALOG}}");

        PromptConfigurationException error = assertThrows(
                PromptConfigurationException.class, service::financeMethodResolverSystemPromptTemplate);
        assertTrue(error.getMessage().contains("projection_mismatch"));
    }

    @Test
    void financeMethodResolverTemplate_shouldIgnorePathMetadataAndUseAuthority() {
        AgentLlmProperties.Prompts prompts = properties.getPrompts();
        prompts.setFinanceMethodResolverSystemPromptFile("file:prompts/finance/finance_method_resolver_system.txt");

        String template = service.financeMethodResolverSystemPromptTemplate();

        assertEquals(PromptFileLoader.load("prompts/finance/finance_method_resolver_system.txt"), template);
    }

    @Test
    void financeMethodResolverTemplate_shouldUseClasspathAuthorityWhenUnset() {
        String template = service.financeMethodResolverSystemPromptTemplate();

        assertFalse(template.isBlank());
        assertTrue(template.contains("{{RESOLVER_CATALOG}}"));
    }

    @Test
    void agentRunSystemPrompt_shouldFailBeforeTimePrefixWhenAuthorityMissing() {
        PromptAuthority missingGlobal = PromptAuthority.forTesting(path ->
                "prompts/agent/agent_run_system.txt".equals(path) ? "" : PromptFileLoader.load(path));
        AgentPromptService isolated = new AgentPromptService(properties, localConfigLoader, missingGlobal);

        PromptConfigurationException error = assertThrows(
                PromptConfigurationException.class, isolated::agentRunSystemPrompt);

        assertTrue(error.getMessage().startsWith(
                "PROMPT_CONFIGURATION_INVALID[authority_missing_or_blank]"));
        assertFalse(error.getMessage().contains("当前时间"), "空正文不能被时间前缀掩盖");
    }

    @Test
    void agentRunSystemPrompt_shouldRejectUnresolvedFileReferenceInAuthority() {
        PromptAuthority unresolvedGlobal = PromptAuthority.forTesting(path ->
                "prompts/agent/agent_run_system.txt".equals(path)
                        ? "file:prompts/agent/agent_run_system.txt"
                        : PromptFileLoader.load(path));
        AgentPromptService isolated = new AgentPromptService(properties, localConfigLoader, unresolvedGlobal);

        PromptConfigurationException error = assertThrows(
                PromptConfigurationException.class, isolated::agentRunSystemPrompt);

        assertTrue(error.getMessage().startsWith(
                "PROMPT_CONFIGURATION_INVALID[authority_unresolved_file_reference]"));
    }
}
