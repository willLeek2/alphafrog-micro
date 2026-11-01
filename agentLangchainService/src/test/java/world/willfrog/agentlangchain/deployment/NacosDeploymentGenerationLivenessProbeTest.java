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




    private static ListView<String> services(int count, String... names) {
        ListView<String> view = new ListView<>();
        view.setCount(count);
        view.setData(List.of(names));
        return view;
    }
}
