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
