package world.willfrog.beta.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.beta.core.ControllerException;
import world.willfrog.beta.core.ServiceRegistry;

class NacosServiceRegistryTest {
    private NamingService naming;
    private NacosServiceRegistry registry;
    private JsonNode manifest;
    private JsonNode service;

    @BeforeEach
    void setUp() throws Exception {
        naming = mock(NamingService.class);
        registry = new NacosServiceRegistry(naming, "public");
        ObjectMapper mapper = new ObjectMapper();
        manifest = mapper.readTree("{\"deploymentId\":\"beta-main-001\",\"trafficScopeId\":\"main-beta\"}");
        service = mapper.readTree("""
                {"releaseId":"release-1","dubboServiceKey":"langchain/com.alphafrog.AgentService",
                 "registration":{"serviceName":"providers:com.alphafrog.AgentService::langchain",
                 "groupName":"alphafrog-beta","namespaceId":"public","clusterName":"DEFAULT",
                 "applicationName":"agent-langchain-service"}}
                """);
    }

    @Test
    void registersAnExactDisabledInstanceThenConfirmsItsRemoval() throws Exception {
        Instance observed = instance(false);
        when(naming.getAllInstances(anyString(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of(observed), List.of());

        ServiceRegistry.Registration registration = registry.register(manifest, service, "i-one",
                "gen-" + "a".repeat(64), "10.0.0.8", 28080, false);
        registry.unregister(registration);

        assertEquals(false, registration.enabled());
        assertEquals(0, registration.weight());
        assertEquals("nacos-i-one", registration.nacosInstanceId());
        assertEquals("beta", registration.metadata().get("zone"));
        assertEquals("beta-main-001", registration.metadata().get("alphafrog.deployment-id"));
        assertEquals("tri", registration.metadata().get("protocol"));
        assertEquals("com.alphafrog.AgentService", registration.metadata().get("path"));
        assertEquals("com.alphafrog.AgentService", registration.metadata().get("interface"));
        assertEquals("langchain", registration.metadata().get("group"));
        assertEquals("", registration.metadata().get("version"));
        assertEquals("agent-langchain-service", registration.metadata().get("application"));
        assertEquals("providers", registration.metadata().get("category"));
        assertEquals("provider", registration.metadata().get("side"));
        verify(naming).registerInstance(anyString(), anyString(), any(Instance.class));
        verify(naming).deregisterInstance(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(naming, times(2)).getAllInstances(anyString(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void rejectsNamespaceDriftAndDuplicateExactRegistrations() throws Exception {
        JsonNode wrongNamespace = new ObjectMapper().readTree("""
                {"releaseId":"release-1","registration":{"serviceName":"service","groupName":"alphafrog-beta",
                 "namespaceId":"other","clusterName":"DEFAULT"}}
                """);
        assertEquals("NACOS_NAMESPACE_MISMATCH", assertThrows(ControllerException.class,
                () -> registry.register(manifest, wrongNamespace, "i-one", "gen-" + "a".repeat(64),
                        "10.0.0.8", 28080, false)).code());

        when(naming.getAllInstances(anyString(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of(instance(false), instance(false)));
        assertEquals("NACOS_IDENTITY_UNCERTAIN", assertThrows(ControllerException.class,
                () -> registry.register(manifest, service, "i-one", "gen-" + "a".repeat(64),
                        "10.0.0.8", 28080, false)).code());
    }

    @Test
    void laneRegistrationCarriesTheOfficialProviderTagAndItsObservationMirror() throws Exception {
        manifest = new ObjectMapper().readTree(
                "{\"deploymentId\":\"beta-lane-a\",\"trafficScopeId\":\"lane-a\"}");
        Instance observed = instance(false);
        observed.getMetadata().put("alphafrog.deployment-id", "beta-lane-a");
        observed.getMetadata().put("alphafrog.traffic-scope-id", "lane-a");
        observed.getMetadata().put("tag", "lane-a");
        observed.getMetadata().put("dubbo.tag", "lane-a");
        when(naming.getAllInstances(anyString(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of(observed));

        ServiceRegistry.Registration registration = registry.register(manifest, service, "i-one",
                "gen-" + "a".repeat(64), "10.0.0.8", 28080, false);

        assertEquals("lane-a", registration.metadata().get("tag"));
        assertEquals("lane-a", registration.metadata().get("dubbo.tag"));
    }

    @Test
    void distinguishesAnAbsentRegistrationFromAConflictingIdentity() throws Exception {
        ServiceRegistry.Registration expected = new ServiceRegistry.Registration(
                "providers:com.alphafrog.AgentService::langchain", "alphafrog-beta", "public", "DEFAULT",
                "10.0.0.8", 28080, "nacos-i-one", false, true, 0, true, instance(false).getMetadata());
        when(naming.getAllInstances(anyString(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of());
        assertTrue(registry.find(expected).isEmpty());

        Instance conflicting = instance(false);
        conflicting.getMetadata().put("alphafrog.release-id", "release-other");
        when(naming.getAllInstances(anyString(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of(conflicting));
        assertEquals("NACOS_IDENTITY_UNCERTAIN", assertThrows(ControllerException.class,
                () -> registry.find(expected)).code());
    }

    private Instance instance(boolean selectable) {
        Instance value = new Instance();
        value.setIp("10.0.0.8");
        value.setPort(28080);
        value.setClusterName("DEFAULT");
        value.setInstanceId("nacos-i-one");
        value.setEnabled(selectable);
        value.setHealthy(true);
        value.setWeight(selectable ? 1 : 0);
        value.setEphemeral(true);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("alphafrog.deployment-id", "beta-main-001");
        metadata.put("alphafrog.traffic-scope-id", "main-beta");
        metadata.put("alphafrog.release-id", "release-1");
        metadata.put("alphafrog.deployment-generation-id", "gen-" + "a".repeat(64));
        metadata.put("alphafrog.instance-id", "i-one");
        metadata.put("zone", "beta");
        metadata.put("application", "agent-langchain-service");
        metadata.put("category", "providers");
        metadata.put("dynamic", "true");
        metadata.put("group", "langchain");
        metadata.put("interface", "com.alphafrog.AgentService");
        metadata.put("path", "com.alphafrog.AgentService");
        metadata.put("protocol", "tri");
        metadata.put("side", "provider");
        metadata.put("version", "");
        value.setMetadata(metadata);
        return value;
    }
}
