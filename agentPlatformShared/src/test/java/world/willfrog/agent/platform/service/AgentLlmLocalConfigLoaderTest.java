package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;

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
    void load_shouldParseNewExecutionJudgeAndSemanticPromptFields() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("judge");
        Files.createDirectories(promptsDir);
        Path semanticPromptFile = promptsDir.resolve("semantic_judge_system.txt");
        Files.writeString(semanticPromptFile, "semantic prompt v1", StandardCharsets.UTF_8);

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

        assertEquals("semantic prompt v1", local.getPrompts().getSemanticJudgeSystemPromptTemplate());
    }

    @Test
    void refresh_shouldReloadWhenSemanticPromptFileChanges() throws Exception {
        Path promptsDir = tempDir.resolve("prompts").resolve("judge");
        Files.createDirectories(promptsDir);
        Path semanticPromptFile = promptsDir.resolve("semantic_judge_system.txt");
        Files.writeString(semanticPromptFile, "semantic prompt v1", StandardCharsets.UTF_8);

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
        assertEquals("semantic prompt v1", loader.current().orElseThrow().getPrompts().getSemanticJudgeSystemPromptTemplate());

        Thread.sleep(5L);
        Files.writeString(semanticPromptFile, "semantic prompt v2", StandardCharsets.UTF_8);
        loader.refresh();

        assertEquals("semantic prompt v2", loader.current().orElseThrow().getPrompts().getSemanticJudgeSystemPromptTemplate());
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
        Files.writeString(guidanceFile, "dag guidance v1", StandardCharsets.UTF_8);

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
        assertEquals("dag guidance v1", local.getPrompts().getDagModeGuidancePromptFile());
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
}
