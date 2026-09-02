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
        manifest = mapper.readTree("{\"trafficScopeId\":\"main-beta\"}");
        service = mapper.readTree("""
                {"releaseId":"release-1","registration":{"serviceName":"com.alphafrog.AgentService:1.0@@providers",
                 "groupName":"DEFAULT_GROUP","namespaceId":"public","clusterName":"DEFAULT"}}
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
        verify(naming).registerInstance(anyString(), anyString(), any(Instance.class));
        verify(naming).deregisterInstance(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(naming, times(2)).getAllInstances(anyString(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void rejectsNamespaceDriftAndDuplicateExactRegistrations() throws Exception {
        JsonNode wrongNamespace = new ObjectMapper().readTree("""
                {"releaseId":"release-1","registration":{"serviceName":"service","groupName":"DEFAULT_GROUP",
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
    void distinguishesAnAbsentRegistrationFromAConflictingIdentity() throws Exception {
        ServiceRegistry.Registration expected = new ServiceRegistry.Registration(
                "com.alphafrog.AgentService:1.0@@providers", "DEFAULT_GROUP", "public", "DEFAULT",
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
        metadata.put("alphafrog.traffic-scope-id", "main-beta");
        metadata.put("alphafrog.release-id", "release-1");
        metadata.put("alphafrog.deployment-generation-id", "gen-" + "a".repeat(64));
        metadata.put("alphafrog.instance-id", "i-one");
        value.setMetadata(metadata);
        return value;
    }
}
