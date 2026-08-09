package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.util.PromptFileLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLlmLocalConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void realLocalExample_shouldResolveEveryConfiguredPromptProjection() throws Exception {
        Path root = repositoryRoot();
        Path example = root.resolve("agentLangchainService/config/agent-llm.local.example.json");
        ObjectMapper mapper = new ObjectMapper();
        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(mapper);
        ReflectionTestUtils.setField(loader, "configFile", example.toString());

        loader.load();

        AgentLlmProperties.Prompts prompts = loader.current().orElseThrow().getPrompts();
        var configured = mapper.readTree(Files.readString(example)).path("prompts");
        configured.fieldNames().forEachRemaining(field -> {
            try {
                if ("pythonRefineRequirementsFile".equals(field)) {
                    assertFalse(prompts.getPythonRefineRequirements().isEmpty());
                    return;
                }
                if ("datasetFieldSpecsFile".equals(field)) {
                    assertFalse(prompts.getDatasetFieldSpecs().isEmpty());
                    return;
                }
                String bodyField = field.endsWith("File")
                        ? field.substring(0, field.length() - "File".length())
                        : field;
                String getter = "get" + Character.toUpperCase(bodyField.charAt(0)) + bodyField.substring(1);
                Object value = AgentLlmProperties.Prompts.class.getMethod(getter).invoke(prompts);
                assertTrue(value instanceof String && !((String) value).isBlank(),
                        "示例字段未解析为正文: " + field);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("示例字段没有对应正文 getter: " + field, e);
            }
        });
        PromptAuthority.shared().validateProjection(prompts, "real local example");
        assertEquals(0L, loader.promptReloadFailureCount());
    }

    @Test
    void refresh_shouldRetainLastSnapshotWhenFollowUpSummaryProjectionDrifts() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("agent");
        Files.createDirectories(promptsDir);
        Path summaryPromptFile = promptsDir.resolve("follow_up_summary_system.txt");
        String authorityText = authorityText("prompts/agent/follow_up_summary_system.txt");
        Files.writeString(summaryPromptFile, authorityText, StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "prompts": {
                    "followUpSummarySystemPrompt": "file:prompts/agent/follow_up_summary_system.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();
        assertEquals(authorityText,
                loader.current().orElseThrow().getPrompts().getFollowUpSummarySystemPrompt());

        Thread.sleep(5L);
        Files.writeString(summaryPromptFile, "divergent follow-up summary prompt", StandardCharsets.UTF_8);
        loader.refresh();

        assertEquals(authorityText,
                loader.current().orElseThrow().getPrompts().getFollowUpSummarySystemPrompt());
        assertEquals(1L, loader.promptReloadFailureCount());
    }

    @Test
    void load_shouldParseNewExecutionJudgeAndSemanticPromptFields() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("judge");
        Files.createDirectories(promptsDir);
        Path semanticPromptFile = promptsDir.resolve("semantic_judge_system.txt");
        String authorityText = authorityText("prompts/judge/semantic_judge_system.txt");
        Files.writeString(semanticPromptFile, authorityText, StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "runtime": {
                    "execution": {
                      "staticPrecheckEnabled": true,
                      "maxStaticRecoveryRetries": 2,
                      "maxRuntimeRecoveryRetries": 3,
                      "maxSemanticRecoveryRetries": 1,
                      "maxTotalRecoveryRetries": 4,
                      "staticFixEndpoint": "openrouter",
                      "staticFixModel": "openai/gpt-5.2",
                      "staticFixTemperature": 0.0
                    },
                    "judge": {
                      "semanticEnabled": true,
                      "maxAttempts": 3,
                      "failOpen": false,
                      "blockOnInsufficientEvidence": true,
                      "routes": [
                        {
                          "endpointName": "openrouter",
                          "models": ["openai/gpt-5.2"]
                        }
                      ]
                    }
                  },
                  "prompts": {
                    "semanticJudgeSystemPromptTemplate": "file:prompts/judge/semantic_judge_system.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        var execution = local.getRuntime().getExecution();
        var judge = local.getRuntime().getJudge();

        assertTrue(Boolean.TRUE.equals(execution.getStaticPrecheckEnabled()));
        assertEquals(2, execution.getMaxStaticRecoveryRetries());
        assertEquals(3, execution.getMaxRuntimeRecoveryRetries());
        assertEquals(1, execution.getMaxSemanticRecoveryRetries());
        assertEquals(4, execution.getMaxTotalRecoveryRetries());
        assertEquals("openrouter", execution.getStaticFixEndpoint());
        assertEquals("openai/gpt-5.2", execution.getStaticFixModel());
        assertEquals(0.0D, execution.getStaticFixTemperature());

        assertTrue(Boolean.TRUE.equals(judge.getSemanticEnabled()));
        assertEquals(3, judge.getMaxAttempts());
        assertFalse(Boolean.TRUE.equals(judge.getFailOpen()));
        assertTrue(Boolean.TRUE.equals(judge.getBlockOnInsufficientEvidence()));
        assertEquals(1, judge.getRoutes().size());
        assertEquals("openrouter", judge.getRoutes().get(0).getEndpointName());
        assertEquals("openai/gpt-5.2", judge.getRoutes().get(0).getModels().get(0));

        assertEquals(authorityText, local.getPrompts().getSemanticJudgeSystemPromptTemplate());
    }

    @Test
    void refresh_shouldRetainLastSnapshotWhenSemanticProjectionDrifts() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("judge");
        Files.createDirectories(promptsDir);
        Path semanticPromptFile = promptsDir.resolve("semantic_judge_system.txt");
        String authorityText = authorityText("prompts/judge/semantic_judge_system.txt");
        Files.writeString(semanticPromptFile, authorityText, StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "runtime": {
                    "execution": { "staticPrecheckEnabled": true },
                    "judge": { "semanticEnabled": true }
                  },
                  "prompts": {
                    "semanticJudgeSystemPromptTemplate": "file:prompts/judge/semantic_judge_system.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();
        assertEquals(authorityText, loader.current().orElseThrow().getPrompts().getSemanticJudgeSystemPromptTemplate());

        Thread.sleep(5L);
        Files.writeString(semanticPromptFile, "divergent semantic prompt", StandardCharsets.UTF_8);
        loader.refresh();

        assertEquals(authorityText, loader.current().orElseThrow().getPrompts().getSemanticJudgeSystemPromptTemplate());
        assertEquals(1L, loader.promptReloadFailureCount());
    }

    @Test
    void load_shouldParseFinanceMethodResolverBoundsFromTopLevel() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "financeMethodResolver": {
                    "defaultRoute": {
                      "enabled": true,
                      "endpointName": "openrouter",
                      "modelName": "openai/gpt-5.2",
                      "maxAttempts": 3
                    },
                    "catalogPromptMaxBytes": 4096,
                    "catalogPromptMaxTokens": 1024,
                    "requestMaxBytes": 6000,
                    "responseMaxBytes": 12000,
                    "maxCandidates": 5
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var resolver = loader.current().orElseThrow().getFinanceMethodResolver();
        assertEquals(4096, resolver.getCatalogPromptMaxBytes());
        assertEquals(1024, resolver.getCatalogPromptMaxTokens());
        assertEquals(6000, resolver.getRequestMaxBytes());
        assertEquals(12000, resolver.getResponseMaxBytes());
        assertEquals(5, resolver.getMaxCandidates());
        assertTrue(Boolean.TRUE.equals(resolver.getDefaultRoute().getEnabled()));
        assertEquals(3, resolver.getDefaultRoute().getMaxAttempts());
    }

    @Test
    void load_shouldApplyFinanceMethodResolverBoundsDefaultsWhenOmitted() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "financeMethodResolver": {
                    "defaultRoute": { "enabled": false }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var resolver = loader.current().orElseThrow().getFinanceMethodResolver();
        assertEquals(8192, resolver.getCatalogPromptMaxBytes());
        assertEquals(2048, resolver.getCatalogPromptMaxTokens());
        assertEquals(8192, resolver.getRequestMaxBytes());
        assertEquals(16384, resolver.getResponseMaxBytes());
        assertEquals(8, resolver.getMaxCandidates());
    }

    @Test
    void load_shouldResolveFinanceMethodResolverSystemPromptFile() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("finance");
        Files.createDirectories(promptsDir);
        Path resolverPromptFile = promptsDir.resolve("finance_method_resolver_system.txt");
        String authorityText = authorityText("prompts/finance/finance_method_resolver_system.txt");
        Files.writeString(resolverPromptFile, authorityText, StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "prompts": {
                    "financeMethodResolverSystemPromptFile": "file:prompts/finance/finance_method_resolver_system.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var prompts = loader.current().orElseThrow().getPrompts();
        assertEquals("file:prompts/finance/finance_method_resolver_system.txt",
                prompts.getFinanceMethodResolverSystemPromptFile());
        assertEquals(authorityText, prompts.getFinanceMethodResolverSystemPrompt());
    }

    @Test
    void refresh_shouldRetainLastSnapshotWhenFinanceProjectionDrifts() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("finance");
        Files.createDirectories(promptsDir);
        Path resolverPromptFile = promptsDir.resolve("finance_method_resolver_system.txt");
        String authorityText = authorityText("prompts/finance/finance_method_resolver_system.txt");
        Files.writeString(resolverPromptFile, authorityText, StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "prompts": {
                    "financeMethodResolverSystemPromptFile": "file:prompts/finance/finance_method_resolver_system.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();
        assertEquals(authorityText,
                loader.current().orElseThrow().getPrompts().getFinanceMethodResolverSystemPrompt());

        Thread.sleep(5L);
        Files.writeString(resolverPromptFile, "divergent resolver prompt", StandardCharsets.UTF_8);
        loader.refresh();

        assertEquals(authorityText,
                loader.current().orElseThrow().getPrompts().getFinanceMethodResolverSystemPrompt());
        assertEquals(1L, loader.promptReloadFailureCount());
    }

    @Test
    void load_shouldKeepInlinePlanningBodyWhenFileFieldIsBlank() throws Exception {
        String authorityText = authorityText("prompts/todo/planning_strategy_stage.txt");
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        var promptsNode = root.putObject("prompts");
        promptsNode.put("planningStrategyStage", authorityText);
        promptsNode.put("planningStrategyStageFile", "   ");
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, mapper.writeValueAsString(root), StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(mapper);
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var prompts = loader.current().orElseThrow().getPrompts();
        assertEquals(authorityText, prompts.getPlanningStrategyStage());
        assertTrue(prompts.getPlanningStrategyStageFile().isBlank());
    }

    @Test
    void load_shouldUsePlanningFileWhenInlineBodyIsBlank() throws Exception {
        String authorityText = authorityText("prompts/todo/planning_strategy_stage.txt");
        Path promptFile = tempDir.resolve("planning_strategy_stage.txt");
        Files.writeString(promptFile, authorityText, StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        var promptsNode = root.putObject("prompts");
        promptsNode.put("planningStrategyStage", "   ");
        promptsNode.put("planningStrategyStageFile", promptFile.toString());
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, mapper.writeValueAsString(root), StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(mapper);
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var prompts = loader.current().orElseThrow().getPrompts();
        assertEquals(authorityText, prompts.getPlanningStrategyStage());
        assertEquals(promptFile.toString(), prompts.getPlanningStrategyStageFile());
    }

    @Test
    void load_shouldRejectBlankFileFieldWhenItIsTheOnlyConfiguredSource() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {"prompts":{"planningStrategyStageFile":"   "}}
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        assertTrue(loader.current().isEmpty());
        assertEquals(1L, loader.promptReloadFailureCount());
    }

    @Test
    void load_shouldRejectAmbiguousBodyAndFileAndExposeMetric() throws Exception {
        String authorityText = authorityText("prompts/todo/planning_strategy_stage.txt");
        Path promptFile = tempDir.resolve("planning_strategy_stage.txt");
        Files.writeString(promptFile, authorityText, StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        var promptsNode = root.putObject("prompts");
        promptsNode.put("planningStrategyStage", authorityText);
        promptsNode.put("planningStrategyStageFile", promptFile.toString());
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, mapper.writeValueAsString(root), StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(mapper);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        ReflectionTestUtils.setField(loader, "meterRegistry", meterRegistry);
        loader.load();

        assertTrue(loader.current().isEmpty(), "歧义候选不能成为当前快照");
        assertEquals(1L, loader.promptReloadFailureCount());
        assertEquals(1.0D, meterRegistry.counter(
                "agent.prompt.config.reload.failures", "reason", "ambiguous_body_and_file").count());
    }

    @Test
    void load_shouldResolveFileReferencePlacedInFinanceBodyField() throws Exception {
        String authorityText = authorityText("prompts/finance/finance_method_resolver_system.txt");
        Path promptFile = tempDir.resolve("finance_method_resolver_system.txt");
        Files.writeString(promptFile, authorityText, StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.putObject("prompts")
                .put("financeMethodResolverSystemPrompt", "file:" + promptFile);
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, mapper.writeValueAsString(root), StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(mapper);
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var prompts = loader.current().orElseThrow().getPrompts();
        assertEquals(authorityText, prompts.getFinanceMethodResolverSystemPrompt());
        assertNull(prompts.getFinanceMethodResolverSystemPromptFile());
    }

    @Test
    void load_shouldRejectMissingPromptFileInsteadOfSubmittingBlankSnapshot() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {"prompts":{"dagReactSystemPrompt":"file:missing/dag.txt"}}
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        assertTrue(loader.current().isEmpty());
        assertEquals(1L, loader.promptReloadFailureCount());
    }

    @Test
    void refresh_shouldRetainLastSnapshotWhenConfigFileTemporarilyDisappears() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {"runtime":{"request":{"maxRetries":7}}}
                """, StandardCharsets.UTF_8);
        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();
        assertEquals(7, loader.current().orElseThrow().getRuntime().getRequest().getMaxRetries());

        Files.delete(configFile);
        loader.refresh();

        assertEquals(7, loader.current().orElseThrow().getRuntime().getRequest().getMaxRetries());
        assertEquals(1L, loader.promptReloadFailureCount());
    }

    @Test
    void load_emptyPrompts_shouldNotTurnDefaultPathIntoPlanningBody() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, "{\"prompts\":{}}", StandardCharsets.UTF_8);
        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var prompts = loader.current().orElseThrow().getPrompts();
        assertNull(prompts.getPlanningStrategyStage());
        assertNull(prompts.getPlanningStrategyStageFile());
        assertNull(prompts.getPlanningTodosStage());
        assertNull(prompts.getPlanningTodosStageFile());
    }

    @Test
    void localProjection_shouldDriveActualSystemPromptWithoutCreatingSecondTruth() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("finance");
        Files.createDirectories(promptsDir);
        Path resolverPromptFile = promptsDir.resolve("finance_method_resolver_system.txt");
        String localTemplate = authorityText("prompts/finance/finance_method_resolver_system.txt");
        Files.writeString(resolverPromptFile, localTemplate, StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "prompts": {
                    "financeMethodResolverSystemPromptFile": "file:prompts/finance/finance_method_resolver_system.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        AgentPromptService promptService = new AgentPromptService(new AgentLlmProperties(), loader);
        String template = promptService.financeMethodResolverSystemPromptTemplate();
        assertEquals(localTemplate, template);

        String rendered = promptService.financeMethodResolverSystemPrompt("COMPACT-CATALOG");
        assertTrue(rendered.contains("金融方法建议器"));
        assertTrue(rendered.contains("COMPACT-CATALOG"));
        assertFalse(rendered.contains("{{RESOLVER_CATALOG}}"));
    }

    @Test
    void localProjection_shouldKeepResolverPromptVersionEqualToClasspathAuthority() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("finance");
        Files.createDirectories(promptsDir);
        Path resolverPromptFile = promptsDir.resolve("finance_method_resolver_system.txt");
        String localTemplate = authorityText("prompts/finance/finance_method_resolver_system.txt");
        Files.writeString(resolverPromptFile, localTemplate, StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "prompts": {
                    "financeMethodResolverSystemPromptFile": "file:prompts/finance/finance_method_resolver_system.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        AgentPromptService promptService = new AgentPromptService(new AgentLlmProperties(), loader);

        AgentAiServiceFactory aiServiceFactory = org.mockito.Mockito.mock(AgentAiServiceFactory.class);
        FinanceMethodResolverModelResolver modelResolver = org.mockito.Mockito.mock(FinanceMethodResolverModelResolver.class);
        AgentObservabilityService observability = org.mockito.Mockito.mock(AgentObservabilityService.class);
        dev.langchain4j.model.chat.ChatModel chatModel = org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);

        world.willfrog.agent.platform.config.StageLlmConfig stage = new world.willfrog.agent.platform.config.StageLlmConfig();
        stage.setEndpointName("e2e-endpoint");
        stage.setModelName("e2e-model");
        org.mockito.Mockito.when(modelResolver.resolveCandidates(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(
                new FinanceMethodResolverModelResolver.ResolvedStageModel(
                        stage, FinanceMethodResolverModelResolver.ModelSource.STAGE_CONFIG)));
        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "e2e-endpoint", "https://e2e.example.com/v1", "e2e-model", "key", "", java.util.List.of(), null);
        org.mockito.Mockito.when(aiServiceFactory.resolveLlm("e2e-endpoint", "e2e-model")).thenReturn(resolved);
        org.mockito.Mockito.when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(chatModel);
        org.mockito.Mockito.when(chatModel.chat(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(new dev.langchain4j.data.message.AiMessage("{\"status\":\"NO_ADVICE\",\"candidates\":[]}"))
                        .build());

        FinanceMethodResolverModelService service = new FinanceMethodResolverModelService(
                new ObjectMapper(), aiServiceFactory, modelResolver, promptService, observability);

        var result = service.resolve("query", null, "E2E-CATALOG");

        var ok = org.junit.jupiter.api.Assertions.assertInstanceOf(
                world.willfrog.agent.platform.finance.FinanceMethodResolverClient.Ok.class, result);
        String expectedDigest = "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(localTemplate.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expectedDigest, ok.resolverPromptVersion());

        byte[] classpathBytes;
        try (java.io.InputStream is = getClass().getResourceAsStream(
                "/prompts/finance/finance_method_resolver_system.txt")) {
            classpathBytes = is == null ? new byte[0] : is.readAllBytes();
        }
        String classpathDigest = "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(classpathBytes));
        assertEquals(classpathDigest, ok.resolverPromptVersion(),
                "外置投影不能产生不同于 classpath 权威正文的 promptVersion");

        org.mockito.ArgumentCaptor<java.util.List<dev.langchain4j.data.message.ChatMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(chatModel).chat(captor.capture());
        var systemMessage = (dev.langchain4j.data.message.SystemMessage) captor.getValue().get(0);
        assertEquals(localTemplate.replace("{{RESOLVER_CATALOG}}", "E2E-CATALOG"), systemMessage.text());
    }

    @Test
    void load_shouldDefaultRequiresAdjFactorEnabledToFalseWhenOmitted() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "runtime": {
                    "parallel": {
                      "toolWeightedLimit": {
                        "enabled": true,
                        "tools": {
                          "getStockDaily": { "weight": 2 },
                          "getEtfAdj": { "weight": 2, "requiresAdjFactorEnabled": true }
                        }
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var tools = loader.current().orElseThrow()
                .getRuntime().getParallel().getToolWeightedLimit().getTools();
        assertFalse(tools.get("getStockDaily").isRequiresAdjFactorEnabled());
        assertFalse(tools.get("getStockDaily").getRequiresAdjFactorEnabled());
        assertTrue(tools.get("getEtfAdj").isRequiresAdjFactorEnabled());
    }

    @Test
    void load_shouldParseRunBudgetFields() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "runtime": {
                    "runBudget": {
                      "maxWallClockMs": 120000,
                      "maxLlmCalls": 10,
                      "maxToolCalls": 60,
                      "maxTokens": 50000,
                      "maxHttpAttemptsPerLogicalCall": 3
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        AgentLlmProperties.RunBudget runBudget = loader.current().orElseThrow()
                .getRuntime().getRunBudget();
        assertEquals(120000L, runBudget.getMaxWallClockMs());
        assertEquals(10L, runBudget.getMaxLlmCalls());
        assertEquals(60L, runBudget.getMaxToolCalls());
        assertEquals(50000L, runBudget.getMaxTokens());
        assertEquals(3, runBudget.getMaxHttpAttemptsPerLogicalCall());
    }

    @Test
    void load_shouldParseDataFreshnessFields() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "dataFreshness": {
                    "startDate": "2020-01-01",
                    "endDate": "2026-05-25",
                    "asOfDate": "2026-05-25",
                    "description": "覆盖股票、指数、ETF、基金等本地已爬取数据"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        AgentLlmProperties.DataFreshness freshness = loader.current().orElseThrow().getDataFreshness();
        assertEquals("2020-01-01", freshness.getStartDate());
        assertEquals("2026-05-25", freshness.getEndDate());
        assertEquals("2026-05-25", freshness.getAsOfDate());
        assertEquals("覆盖股票、指数、ETF、基金等本地已爬取数据", freshness.getDescription());
    }

    @Test
    void load_shouldParseMarketDataToolFlags() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "agent": {
                    "call-raw-content": {
                      "ttl-seconds": 7200
                    }
                  },
                  "tools": {
                    "market-data": {
                      "dataset": {
                        "enabled": true
                      },
                      "batch": {
                        "emit-manifest": true
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        AgentLlmProperties.MarketData marketData = loader.current().orElseThrow()
                .getTools().getMarketData();
        assertTrue(Boolean.TRUE.equals(marketData.getDataset().getEnabled()));
        assertTrue(Boolean.TRUE.equals(marketData.getBatch().getEmitManifest()));
        assertEquals(7200L, loader.current().orElseThrow()
                .getAgent().getCallRawContent().getTtlSeconds());
    }

    @Test
    void load_shouldParseAdvancedMarketDataHotConfigAndTolerateInvalidParallelValue() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "runtime": {
                    "parallel": {
                      "maxParallelQueriesInAdvancedMode": "abc"
                    }
                  },
                  "tools": {
                    "market-data": {
                      "advanced": {
                        "preview-rows": 13
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        assertEquals(1, local.getRuntime().getParallel().getMaxParallelQueriesInAdvancedMode());
        assertEquals(13, local.getTools().getMarketData().getAdvanced().getPreviewRows());
    }

    @Test
    void load_shouldResolveDagModeGuidancePromptFile() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("todo");
        Files.createDirectories(promptsDir);
        Path guidanceFile = promptsDir.resolve("dag_mode_guidance.txt");
        String authorityText = authorityText("prompts/todo/dag_mode_guidance.txt");
        Files.writeString(guidanceFile, authorityText, StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "prompts": {
                    "dagModeGuidancePromptFile": "file:prompts/todo/dag_mode_guidance.txt"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        assertEquals("file:prompts/todo/dag_mode_guidance.txt",
                local.getPrompts().getDagModeGuidancePromptFile());
        assertEquals(authorityText, local.getPrompts().getDagModeGuidancePrompt());
    }

    @Test
    void load_shouldApplyT6Defaults() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, "{}", StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        assertEquals(12, local.getAgent().getWorkspace().getDump().getTtlHours());
        assertEquals(12, local.getAgent().getDataset().getTtlHours());
        assertEquals(2000, local.getTools().getResult().getMaxStringLength());
        assertEquals(3, local.getTools().getSummary().getMaxRetries());
        assertEquals(2000, local.getTools().getReread().getMaxLimit());
        assertEquals(12, local.getTools().getRawRef().getTtlHours());
        assertEquals(3, local.getRuntime().getRequest().getMaxRetries());
        assertEquals("exponential", local.getRuntime().getRequest().getRetry().getBackoffType());
        assertEquals(1000L, local.getRuntime().getRequest().getRetry().getBaseDelayMs());
        assertEquals(4000L, local.getRuntime().getRequest().getRetry().getMaxDelayMs());
        assertEquals(100L, local.getRuntime().getRequest().getRetry().getJitterMs());
    }

    @Test
    void load_shouldParseT6ConfigFields() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "agent": {
                    "workspace": { "dump": { "ttl-hours": 24 } },
                    "dataset": { "ttl-hours": 48 }
                  },
                  "tools": {
                    "result": {
                      "max-string-length": 3000,
                      "summary-model": "openai/gpt-5.2",
                      "summary-endpoint": "openrouter"
                    },
                    "summary": { "max-retries": 5 },
                    "reread": { "max-limit": 4000 },
                    "raw-ref": { "ttl-hours": 36 }
                  },
                  "runtime": {
                    "request": {
                      "max-retries": 7,
                      "retry": {
                        "backoff-type": "fixed",
                        "base-delay-ms": 500,
                        "max-delay-ms": 2000,
                        "jitter-ms": 50
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        assertEquals(24, local.getAgent().getWorkspace().getDump().getTtlHours());
        assertEquals(48, local.getAgent().getDataset().getTtlHours());
        assertEquals(3000, local.getTools().getResult().getMaxStringLength());
        assertEquals("openai/gpt-5.2", local.getTools().getResult().getSummaryModel());
        assertEquals("openrouter", local.getTools().getResult().getSummaryEndpoint());
        assertEquals(5, local.getTools().getSummary().getMaxRetries());
        assertEquals(4000, local.getTools().getReread().getMaxLimit());
        assertEquals(36, local.getTools().getRawRef().getTtlHours());
        assertEquals(7, local.getRuntime().getRequest().getMaxRetries());
        assertEquals("fixed", local.getRuntime().getRequest().getRetry().getBackoffType());
        assertEquals(500L, local.getRuntime().getRequest().getRetry().getBaseDelayMs());
        assertEquals(2000L, local.getRuntime().getRequest().getRetry().getMaxDelayMs());
        assertEquals(50L, local.getRuntime().getRequest().getRetry().getJitterMs());
    }

    @Test
    void load_shouldParseRagRawRefAndRereadHotConfigFields() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "tools": {
                    "raw-ref": { "ttl-seconds": 21600 },
                    "reread": {
                      "keyword-char-limit": 3000,
                      "range-max-limit": 6000,
                      "range-min-limit-without-keyword": 3000
                    },
                    "rag": {
                      "visible-chars": 3000,
                      "preview-chars": 500,
                      "snippet-cap-per-doc": 3,
                      "short-doc-full-threshold": 2000
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var tools = loader.current().orElseThrow().getTools();
        assertEquals(21600, tools.getRawRef().getTtlSeconds());
        assertEquals(3000, tools.getReread().getKeywordCharLimit());
        assertEquals(6000, tools.getReread().getRangeMaxLimit());
        assertEquals(3000, tools.getReread().getRangeMinLimitWithoutKeyword());
        assertEquals(3000, tools.getRag().getVisibleChars());
        assertEquals(500, tools.getRag().getPreviewChars());
        assertEquals(3, tools.getRag().getSnippetCapPerDoc());
        assertEquals(2000, tools.getRag().getShortDocFullThreshold());
    }

    @Test
    void load_shouldSupportFieldAliases() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "tools": {
                    "result": {
                      "max_string_length": 1500,
                      "summary_model": "moonshotai/kimi-k2.6",
                      "summary_endpoint": "openrouter"
                    },
                    "summary": { "max_retries": 2 },
                    "reread": { "max_limit": 1500 },
                    "raw_ref": { "ttl_hours": 6 }
                  },
                  "runtime": {
                    "request": {
                      "max_retries": 4,
                      "retry": {
                        "backoff_type": "exponential",
                        "base_delay_ms": 2000,
                        "max_delay_ms": 8000,
                        "jitter_ms": 200
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        assertEquals(1500, local.getTools().getResult().getMaxStringLength());
        assertEquals("moonshotai/kimi-k2.6", local.getTools().getResult().getSummaryModel());
        assertEquals("openrouter", local.getTools().getResult().getSummaryEndpoint());
        assertEquals(2, local.getTools().getSummary().getMaxRetries());
        assertEquals(1500, local.getTools().getReread().getMaxLimit());
        assertEquals(6, local.getTools().getRawRef().getTtlHours());
        assertEquals(4, local.getRuntime().getRequest().getMaxRetries());
        assertEquals("exponential", local.getRuntime().getRequest().getRetry().getBackoffType());
        assertEquals(2000L, local.getRuntime().getRequest().getRetry().getBaseDelayMs());
        assertEquals(8000L, local.getRuntime().getRequest().getRetry().getMaxDelayMs());
        assertEquals(200L, local.getRuntime().getRequest().getRetry().getJitterMs());
    }

    @Test
    void load_shouldMapToolAliasToTools() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "tool": {
                    "result": { "max-string-length": 999 }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        assertEquals(999, local.getTools().getResult().getMaxStringLength());
    }

    @Test
    void load_shouldMapAgentLlmRequestToRuntimeRequest() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "agent": {
                    "llm": {
                      "request": {
                        "max-retries": 9,
                        "retry": { "base-delay-ms": 3000 }
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        assertEquals(9, local.getRuntime().getRequest().getMaxRetries());
        assertEquals(3000L, local.getRuntime().getRequest().getRetry().getBaseDelayMs());
    }

    @Test
    void load_shouldPreferRuntimeRequestOverAgentLlmRequest() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "agent": { "llm": { "request": { "max-retries": 9 } } },
                  "runtime": { "request": { "max-retries": 2 } }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        assertEquals(2, loader.current().orElseThrow().getRuntime().getRequest().getMaxRetries());
    }

    @Test
    void refresh_shouldReloadT6Fields() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "tools": { "result": { "max-string-length": 1000 } }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();
        assertEquals(1000, loader.current().orElseThrow().getTools().getResult().getMaxStringLength());

        Thread.sleep(5L);
        Files.writeString(configFile, """
                {
                  "tools": { "result": { "max-string-length": 5000 } }
                }
                """, StandardCharsets.UTF_8);
        loader.refresh();

        assertEquals(5000, loader.current().orElseThrow().getTools().getResult().getMaxStringLength());
    }

    @Test
    void load_shouldTolerateMalformedJson() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, "{ invalid json }", StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        assertTrue(loader.current().isEmpty());
        assertEquals(1L, loader.promptReloadFailureCount(),
                "损坏的热加载文件必须产生可告警失败信号");
    }

    @Test
    void load_shouldTolerateMissingFile() {
        Path configFile = tempDir.resolve("agent-llm.local.json");

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        assertTrue(loader.current().isEmpty());
    }

    @Test
    void load_shouldApplyDefaultsOnExplicitNulls() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "tools": { "result": { "max-string-length": null } }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        var local = loader.current().orElseThrow();
        assertEquals(2000, local.getTools().getResult().getMaxStringLength());
    }

    @Test
    void refresh_shouldRemainConsistentUnderConcurrentRefresh() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "tools": { "result": { "max-string-length": 1000 } }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        int threads = 8;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Exception> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        loader.refresh();
                    }
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertNull(error.get());
        assertTrue(loader.current().isPresent());
        assertEquals(1000, loader.current().orElseThrow().getTools().getResult().getMaxStringLength());
    }

    /**
     * 真实 loader + 真实 resolver + 真实 ModelService（仅 mock 路由构建/ChatModel/prompt/observability）
     * 的 financeMethodResolver 消费链夹具。
     */
    private record RealResolverChain(
            AgentLlmLocalConfigLoader loader,
            FinanceMethodResolverModelResolver modelResolver,
            FinanceMethodResolverModelService service,
            dev.langchain4j.model.chat.ChatModel chatModel) {
    }

    private RealResolverChain realChain(Path configFile, AgentLlmProperties staticProperties) {
        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();

        FinanceMethodResolverModelResolver modelResolver =
                new FinanceMethodResolverModelResolver(staticProperties, loader);

        AgentAiServiceFactory aiServiceFactory = org.mockito.Mockito.mock(AgentAiServiceFactory.class);
        AgentPromptService promptService = org.mockito.Mockito.mock(AgentPromptService.class);
        AgentObservabilityService observability = org.mockito.Mockito.mock(AgentObservabilityService.class);
        dev.langchain4j.model.chat.ChatModel chatModel = org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        org.mockito.Mockito.when(promptService.financeMethodResolverSystemPromptTemplate())
                .thenReturn("resolver template {{RESOLVER_CATALOG}}");
        AgentLlmResolver.ResolvedLlm resolved = new AgentLlmResolver.ResolvedLlm(
                "local-endpoint", "https://local.example.com/v1", "local-model", "key", "", java.util.List.of(), null);
        org.mockito.Mockito.lenient().when(aiServiceFactory.resolveLlm(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(resolved);
        org.mockito.Mockito.lenient().when(aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(chatModel);
        org.mockito.Mockito.lenient().when(chatModel.chat(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(new dev.langchain4j.data.message.AiMessage("{\"status\":\"NO_ADVICE\",\"candidates\":[]}"))
                        .build());

        FinanceMethodResolverModelService service = new FinanceMethodResolverModelService(
                new ObjectMapper(), aiServiceFactory, modelResolver, promptService, observability);
        return new RealResolverChain(loader, modelResolver, service, chatModel);
    }

    @Test
    void localSection_shouldDriveDefaultRouteAndBoundsThroughRealChain() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "financeMethodResolver": {
                    "defaultRoute": {
                      "enabled": true,
                      "endpointName": "local-endpoint",
                      "modelName": "local-model"
                    },
                    "requestMaxBytes": 32
                  }
                }
                """, StandardCharsets.UTF_8);

        RealResolverChain chain = realChain(configFile, new AgentLlmProperties());

        // 路由消费：无 stage 时本地 default route 产生候选
        var candidates = chain.modelResolver().resolveCandidates();
        assertEquals(1, candidates.size());
        assertEquals(FinanceMethodResolverModelResolver.ModelSource.DEFAULT_ROUTE, candidates.get(0).source());
        assertEquals("local-endpoint", candidates.get(0).config().getEndpointName());

        // 边界消费：本地 requestMaxBytes=32 在 chat() 前触发
        var result = chain.service().resolve("query", null, "catalog");
        var error = org.junit.jupiter.api.Assertions.assertInstanceOf(
                world.willfrog.agent.platform.finance.FinanceMethodResolverClient.TechnicalError.class, result);
        assertEquals(world.willfrog.agent.platform.finance.FinanceMethodResolverClient.ErrorKind.REQUEST_TOO_LARGE,
                error.kind());
        org.mockito.Mockito.verify(chain.chatModel(), org.mockito.Mockito.never())
                .chat(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void localBoundsChange_shouldTakeEffectAfterRefresh() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "financeMethodResolver": {
                    "defaultRoute": { "enabled": true, "endpointName": "local-endpoint", "modelName": "local-model" },
                    "requestMaxBytes": 100000
                  }
                }
                """, StandardCharsets.UTF_8);

        RealResolverChain chain = realChain(configFile, new AgentLlmProperties());
        var first = chain.service().resolve("query", null, "catalog");
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                world.willfrog.agent.platform.finance.FinanceMethodResolverClient.Ok.class, first);

        Thread.sleep(5L);
        Files.writeString(configFile, """
                {
                  "financeMethodResolver": {
                    "defaultRoute": { "enabled": true, "endpointName": "local-endpoint", "modelName": "local-model" },
                    "requestMaxBytes": 32
                  }
                }
                """, StandardCharsets.UTF_8);
        chain.loader().refresh();

        var second = chain.service().resolve("query", null, "catalog");
        var error = org.junit.jupiter.api.Assertions.assertInstanceOf(
                world.willfrog.agent.platform.finance.FinanceMethodResolverClient.TechnicalError.class, second);
        assertEquals(world.willfrog.agent.platform.finance.FinanceMethodResolverClient.ErrorKind.REQUEST_TOO_LARGE,
                error.kind());
    }

    @Test
    void staticConfig_shouldApplyWhenLocalFileMissing() {
        Path configFile = tempDir.resolve("missing-agent-llm.local.json");
        AgentLlmProperties staticProperties = new AgentLlmProperties();
        staticProperties.getFinanceMethodResolver().getDefaultRoute().setEnabled(true);
        staticProperties.getFinanceMethodResolver().getDefaultRoute().setEndpointName("static-endpoint");
        staticProperties.getFinanceMethodResolver().getDefaultRoute().setModelName("static-model");
        staticProperties.getFinanceMethodResolver().setRequestMaxBytes(32);

        RealResolverChain chain = realChain(configFile, staticProperties);

        var candidates = chain.modelResolver().resolveCandidates();
        assertEquals(1, candidates.size());
        assertEquals("static-endpoint", candidates.get(0).config().getEndpointName());

        var result = chain.service().resolve("query", null, "catalog");
        var error = org.junit.jupiter.api.Assertions.assertInstanceOf(
                world.willfrog.agent.platform.finance.FinanceMethodResolverClient.TechnicalError.class, result);
        assertEquals(world.willfrog.agent.platform.finance.FinanceMethodResolverClient.ErrorKind.REQUEST_TOO_LARGE,
                error.kind());
    }

    @Test
    void localResponseMaxBytesAndMaxAttempts_shouldTakeEffectThroughRealChain() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "financeMethodResolver": {
                    "defaultRoute": {
                      "enabled": true,
                      "endpointName": "local-endpoint",
                      "modelName": "local-model",
                      "maxAttempts": 1
                    },
                    "responseMaxBytes": 16
                  }
                }
                """, StandardCharsets.UTF_8);

        RealResolverChain chain = realChain(configFile, new AgentLlmProperties());

        var result = chain.service().resolve("query", null, "catalog");
        var error = org.junit.jupiter.api.Assertions.assertInstanceOf(
                world.willfrog.agent.platform.finance.FinanceMethodResolverClient.TechnicalError.class, result);
        assertEquals(world.willfrog.agent.platform.finance.FinanceMethodResolverClient.ErrorKind.BAD_JSON,
                error.kind());
        // maxAttempts=1 由真实本地配置驱动：响应超限后只调用 1 次 chat（默认 2 次则计数为 2）
        org.mockito.Mockito.verify(chain.chatModel(), org.mockito.Mockito.times(1))
                .chat(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void localMaxCandidates_shouldTakeEffectThroughRealChain() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "financeMethodResolver": {
                    "defaultRoute": { "enabled": true, "endpointName": "local-endpoint", "modelName": "local-model" },
                    "maxCandidates": 1
                  }
                }
                """, StandardCharsets.UTF_8);

        RealResolverChain chain = realChain(configFile, new AgentLlmProperties());
        String twoCandidates = "{\"status\":\"AMBIGUOUS\",\"candidates\":["
                + "{\"methodId\":\"a\",\"version\":\"1.0.0\",\"specDigest\":\"sha256:a\",\"matchReason\":\"x\"},"
                + "{\"methodId\":\"b\",\"version\":\"1.0.0\",\"specDigest\":\"sha256:b\",\"matchReason\":\"y\"}]}";
        org.mockito.Mockito.when(chain.chatModel().chat(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(new dev.langchain4j.data.message.AiMessage(twoCandidates))
                        .build());

        var result = chain.service().resolve("query", null, "catalog");
        var error = org.junit.jupiter.api.Assertions.assertInstanceOf(
                world.willfrog.agent.platform.finance.FinanceMethodResolverClient.TechnicalError.class, result);
        assertEquals(world.willfrog.agent.platform.finance.FinanceMethodResolverClient.ErrorKind.BAD_JSON,
                error.kind());
        assertTrue(error.message().contains("candidate count"));
    }

    private static String authorityText(String path) {
        String text = PromptFileLoader.load(path);
        assertFalse(text.isBlank(), "测试所需权威 Prompt 必须存在: " + path);
        return text;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("agentPlatformShared"))
                    && Files.isDirectory(current.resolve("agentLangchainService"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
