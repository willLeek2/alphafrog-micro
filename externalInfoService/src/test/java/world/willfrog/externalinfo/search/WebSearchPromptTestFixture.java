package world.willfrog.externalinfo.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.mockito.Mockito;
import world.willfrog.alphafrogmicro.common.racing.*;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.racing.idl.*;

/** 既有搜索测试使用真实正文客户端及保存客户端，仅替换平台RPC。 */
public final class WebSearchPromptTestFixture {
    public final PromptSelection selection;
    public final RuntimeCallContext context;
    public final SearchPromptPreparation preparation;
    public final List<StoreModelInputRequest> saved = new ArrayList<>();
    public boolean denyRead;
    public boolean denySave;
    public Consumer<StoreModelInputRequest> onSave = request -> { };

    public WebSearchPromptTestFixture(ObjectMapper mapper) throws Exception {
        String generation = "gen-" + "1".repeat(64);
        Map<String, String> settings = Map.of("RACING_ENVIRONMENT", "beta", "RACING_TARGET_CREATION_ID", "scope-racing",
                "RACING_TARGET_ROUTE_TAG", "racing-route", "RACING_TARGET_DEPLOYMENT_ID", "racing",
                "RACING_TARGET_GENERATION_ID", generation, "AF_DEPLOYMENT_GENERATION_ID", generation,
                "RACING_SERVICE_KEY_ID", "fixture-key", "RACING_SERVICE_TOKEN", "fixture-token");
        RootIdentity root = RootIdentity.newBuilder().setOwnerNamespace("alphafrog").setOwnerKind("agent_run")
                .setRootId("root-1").setOwnerScopeInstanceId("scope-alpha").setOwnerRouteTag("alpha-route").build();
        TargetIdentity target = new RacingClientSettings(settings::get).target();
        RuntimeIdentity actual = RuntimeIdentity.newBuilder().setEnvironment("beta").setScopeInstanceId("scope-racing")
                .setRouteTag("racing-route").setDeploymentId("racing").setActualTargetGenerationId(generation).build();
        context = RuntimeCallContext.newBuilder().setRoot(root).setTarget(target).setCurrentRunId("child-1")
                .setOperationId("search-1").setCallerGenerationId(generation).build();
        PromptScope scope = PromptScope.newBuilder().setEnvironment("beta").setScopeInstanceId("scope-racing").build();
        PromptSetVersionRef setRef = PromptSetVersionRef.newBuilder().setScope(scope).setPromptSetId("set-1").setVersion(1).build();
        selection = PromptSelection.newBuilder().setSelectionId("selection-1").setSelectionKey("agent")
                .setRoot(root).setTarget(target).setSelectedTarget(PromptTargetRef.newBuilder().setPromptSetVersion(setRef))
                .setOrigin(PromptSelectionOrigin.newBuilder().setSourceScope(scope).setBusinessName("agent")
                        .setDefaultRevision(1).setValueSource(ValueSource.newBuilder().setKind(ValueSourceKind.ORDINARY_DEFAULT))).build();
        Map<String, PromptVersion> versions = new LinkedHashMap<>();
        PromptSetVersion.Builder set = PromptSetVersion.newBuilder().setReference(setRef);
        for (String key : List.of("webSearchProfileHeader", "webSearchProfileTimezone", "webSearchProfileRegion",
                "webSearchProfileLanguage", "webSearchProfileStyle", "webSearchQueryRegion", "webSearchQueryLanguage")) {
            try (var stream = getClass().getResourceAsStream("/prompt-import/web-search/" + key + ".json")) {
                JsonNode json = mapper.readTree(stream);
                var version = PromptVersion.newBuilder().setReference(PromptVersionRef.newBuilder().setScope(scope)
                        .setPromptId(key).setVersion(1)).setPurpose(key).setBody(json.path("body").asText())
                        .setBodyType(json.path("bodyType").asText().equals("FIXED")
                                ? PromptBodyType.PROMPT_BODY_FIXED : PromptBodyType.PROMPT_BODY_TEMPLATE);
                for (JsonNode variable : json.path("variables")) {
                    version.addVariables(PromptVariableDeclaration.newBuilder().setName(variable.path("name").asText())
                            .setRequired(variable.path("required").asBoolean()).setAllowEmpty(variable.path("allowEmpty").asBoolean()));
                }
                versions.put(key, version.build());
                set.addMembers(PromptSetMember.newBuilder().setFragmentName(key).setExpectedPurpose(key)
                        .setVersion(version.getReference()));
            }
        }
        RacingService service = Mockito.mock(RacingService.class);
        RacingError denied = RacingError.newBuilder().setCode("FORBIDDEN")
                .putDetails("reason_code", "SOURCE_SCOPE_DENIED").putObjectLocator("selection_id", "selection-1").build();
        Mockito.when(service.getPromptSelection(Mockito.any())).thenAnswer(invocation -> denyRead
                ? PromptSelectionReply.newBuilder().setError(denied).build()
                : PromptSelectionReply.newBuilder().setSelection(selection).setActualTarget(actual).build());
        Mockito.when(service.readPromptSetVersion(Mockito.any())).thenReturn(
                PromptSetVersionReply.newBuilder().setVersion(set).setActualTarget(actual).build());
        Mockito.when(service.readPromptVersions(Mockito.any())).thenAnswer(invocation -> {
            ReadPromptVersionsRequest request = invocation.getArgument(0);
            return ReadPromptVersionsReply.newBuilder().setActualTarget(actual).setVersions(PromptVersionList.newBuilder()
                    .addAllVersions(request.getVersionsList().stream().map(v -> versions.get(v.getVersion().getPromptId())).toList())).build();
        });
        Mockito.when(service.storeModelInput(Mockito.any())).thenAnswer(invocation -> {
            StoreModelInputRequest request = invocation.getArgument(0);
            saved.add(request);
            onSave.accept(request);
            if (denySave) return StoreModelInputReply.newBuilder().setError(denied).build();
            return StoreModelInputReply.newBuilder().setActualTarget(actual).setStored(ModelInputReceipt.newBuilder()
                    .setModelInputId("saved-" + saved.size()).setLogicalCallId(request.getLogicalCallId())
                    .setAttemptUuid(request.getAttemptUuid()).setSha256(request.getSha256()).setUtf8Length(request.getUtf8Length())).build();
        });
        RacingTransport transport = new RacingTransport(service, new RacingClientSettings(settings::get));
        RacingPromptClient prompts = new RacingPromptClient(transport);
        preparation = new SearchPromptPreparation(prompts, new RacingPromptSetReader(prompts), new RacingInputClient(transport));
    }

    public WebSearchRequest request(String query) {
        return WebSearchRequest.newBuilder().setQuery(query).setRunId(context.getCurrentRunId())
                .setRuntimeCallContext(context.toByteString()).setPromptSelection(selection.toByteString()).build();
    }
}
