package world.willfrog.agentlangchain.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

class NacosDeploymentGenerationLivenessProbeTest {

    @Test
    void matchesBothDeploymentAndGenerationMetadata() throws Exception {
        NamingService naming = mock(NamingService.class);
        Instance matching = new Instance();
        matching.setMetadata(Map.of(
                "alphafrog.deployment-id", "beta-a",
                "alphafrog.deployment-generation-id", "gen-" + "a".repeat(64)));
        when(naming.getServicesOfServer(1, 100, "alphafrog-beta"))
                .thenReturn(services(1, "providers:Agent:v1:g"));
        when(naming.getAllInstances("providers:Agent:v1:g", "alphafrog-beta", false))
                .thenReturn(List.of(matching));
        NacosDeploymentGenerationLivenessProbe probe = new NacosDeploymentGenerationLivenessProbe(
                naming, "alphafrog-beta", 100);

        assertThat(probe.hasLiveInstance(new DeploymentIdentity(
                "beta-a", "gen-" + "a".repeat(64)))).isTrue();
        assertThat(probe.hasLiveInstance(new DeploymentIdentity(
                "beta-a", "gen-" + "b".repeat(64)))).isFalse();
    }

    @Test
    void registryFailureCannotBeInterpretedAsGenerationAbsence() throws Exception {
        NamingService naming = mock(NamingService.class);
        when(naming.getServicesOfServer(1, 100, "alphafrog-beta"))
                .thenThrow(new NacosException(500, "unavailable"));
        NacosDeploymentGenerationLivenessProbe probe = new NacosDeploymentGenerationLivenessProbe(
                naming, "alphafrog-beta", 100);

        assertThatThrownBy(() -> probe.hasLiveInstance(new DeploymentIdentity(
                "beta-a", "gen-" + "a".repeat(64))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nacos");
    }

    @Test
    void searchesEveryServiceAndEveryClusterInTheBetaGroup() throws Exception {
        NamingService naming = mock(NamingService.class);
        Instance matching = new Instance();
        matching.setClusterName("old-cluster");
        matching.setMetadata(Map.of(
                "alphafrog.deployment-id", "beta-a",
                "alphafrog.deployment-generation-id", "gen-" + "a".repeat(64)));
        when(naming.getServicesOfServer(1, 1, "alphafrog-beta"))
                .thenReturn(services(2, "old-service"));
        when(naming.getServicesOfServer(2, 1, "alphafrog-beta"))
                .thenReturn(services(2, "new-service"));
        when(naming.getAllInstances("old-service", "alphafrog-beta", false))
                .thenReturn(List.of());
        when(naming.getAllInstances("new-service", "alphafrog-beta", false))
                .thenReturn(List.of(matching));
        NacosDeploymentGenerationLivenessProbe probe = new NacosDeploymentGenerationLivenessProbe(
                naming, "alphafrog-beta", 1);

        assertThat(probe.hasLiveInstance(new DeploymentIdentity(
                "beta-a", "gen-" + "a".repeat(64)))).isTrue();
    }

    private static ListView<String> services(int count, String... names) {
        ListView<String> view = new ListView<>();
        view.setCount(count);
        view.setData(List.of(names));
        return view;
    }
}
