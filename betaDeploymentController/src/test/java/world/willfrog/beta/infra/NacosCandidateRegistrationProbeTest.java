package world.willfrog.beta.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.beta.core.ControllerException;

class NacosCandidateRegistrationProbeTest {
    private NamingService naming;
    private NacosCandidateRegistrationProbe probe;
    private JsonNode service;

    @BeforeEach
    void setUp() throws Exception {
        naming = mock(NamingService.class);
        probe = new NacosCandidateRegistrationProbe(naming, "public");
        service = new ObjectMapper().readTree("""
                {"registration":{"serviceName":"providers:com.alphafrog.AgentService::langchain",
                 "groupName":"alphafrog-beta","namespaceId":"public","clusterName":"DEFAULT"}}
                """);
    }

    @Test
    void reportsOnlyAHealthySelectableRegistrationAtTheExpectedEndpoint() throws Exception {
        when(naming.getAllInstances(anyString(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of(instance("10.0.0.8", 28080, true, true, 1.0d)));

        assertTrue(probe.isVisible(service, "10.0.0.8", 28080));

        verify(naming).getAllInstances("providers:com.alphafrog.AgentService::langchain",
                "alphafrog-beta", List.of("DEFAULT"), false);
        verifyNoMoreInteractions(naming);
    }

    @Test
    void rejectsWrongEndpointOrARegistrationThatCannotReceiveTraffic() throws Exception {
        when(naming.getAllInstances(anyString(), anyString(), anyList(), anyBoolean()))
                .thenReturn(List.of(
                        instance("10.0.0.9", 28080, true, true, 1.0d),
                        instance("10.0.0.8", 28080, false, true, 1.0d),
                        instance("10.0.0.8", 28080, true, false, 1.0d),
                        instance("10.0.0.8", 28080, true, true, 0.0d)));

        assertFalse(probe.isVisible(service, "10.0.0.8", 28080));
    }

    @Test
    void failsClosedWhenNamespaceOrNacosQueryCannotBeVerified() throws Exception {
        JsonNode wrongNamespace = new ObjectMapper().readTree("""
                {"registration":{"serviceName":"service","groupName":"alphafrog-beta",
                 "namespaceId":"other","clusterName":"DEFAULT"}}
                """);
        assertEquals("NACOS_NAMESPACE_MISMATCH", assertThrows(ControllerException.class,
                () -> probe.isVisible(wrongNamespace, "10.0.0.8", 28080)).code());

        when(naming.getAllInstances(anyString(), anyString(), anyList(), anyBoolean()))
                .thenThrow(new NacosException(500, "query failed"));
        assertEquals("NACOS_QUERY_FAILED", assertThrows(ControllerException.class,
                () -> probe.isVisible(service, "10.0.0.8", 28080)).code());
    }

    private static Instance instance(String address, int port, boolean enabled, boolean healthy, double weight) {
        Instance value = new Instance();
        value.setIp(address);
        value.setPort(port);
        value.setEnabled(enabled);
        value.setHealthy(healthy);
        value.setWeight(weight);
        return value;
    }
}
