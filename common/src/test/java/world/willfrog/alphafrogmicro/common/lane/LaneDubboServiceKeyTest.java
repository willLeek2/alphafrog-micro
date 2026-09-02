package world.willfrog.alphafrogmicro.common.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.Test;

class LaneDubboServiceKeyTest {

    private static final String INTERFACE =
            "world.willfrog.alphafrogmicro.agent.idl.AgentDubboService";

    @Test
    void actualProjectUrlProducesAStableTargetKeyWithoutProtocolSuffix() {
        URL consumerUrl = consumerUrl(INTERFACE, "langchain", "");
        RpcInvocation invocation = realInvocation(consumerUrl);

        assertThat(consumerUrl.getProtocolServiceKey())
                .isEqualTo("langchain/" + INTERFACE + ":dubbo");
        assertThat(consumerUrl.getServiceKey())
                .isEqualTo("langchain/" + INTERFACE);
        assertThat(LaneDubboServiceKey.fromInvocation(consumerUrl, invocation))
                .isEqualTo(new LaneDubboServiceKey("langchain", INTERFACE, ""));
    }

    @Test
    void mapsOnlyTheCurrentNacosDefaultVersionWhenDubboVersionIsAbsent() {
        LaneDubboServiceKey key = new LaneDubboServiceKey("langchain", INTERFACE, "");

        assertThat(key.matchesNacosRegistration(INTERFACE + ":1.0@@providers")).isTrue();
        assertThat(key.matchesNacosRegistration(INTERFACE + ":2.0@@providers")).isFalse();
        assertThat(key.matchesNacosRegistration("another.Interface:1.0@@providers")).isFalse();
    }

    @Test
    void explicitVersionAndGroupRemainPartOfTheExactRequestIdentity() {
        LaneDubboServiceKey versionOne = new LaneDubboServiceKey("langchain", INTERFACE, "1.0");
        LaneDubboServiceKey versionTwo = new LaneDubboServiceKey("langchain", INTERFACE, "2.0");
        LaneDubboServiceKey anotherGroup = new LaneDubboServiceKey("experimental", INTERFACE, "1.0");

        assertThat(versionOne).isNotEqualTo(versionTwo);
        assertThat(versionOne).isNotEqualTo(anotherGroup);
        assertThat(versionTwo.matchesNacosRegistration(INTERFACE + ":2.0@@providers")).isTrue();
        assertThat(versionTwo.matchesNacosRegistration(INTERFACE + ":1.0@@providers")).isFalse();
    }

    @Test
    void rejectsAConfiguredTargetKeyThatDisagreesWithTheInvocationInterface() {
        URL consumerUrl = consumerUrl(INTERFACE, "langchain", "");
        RpcInvocation invocation = realInvocation(consumerUrl);
        invocation.setTargetServiceUniqueName("langchain/another.Interface");

        assertThatThrownBy(() -> LaneDubboServiceKey.fromInvocation(consumerUrl, invocation))
                .isInstanceOf(LaneRouteFactsUncertainException.class);
    }

    @SuppressWarnings("deprecation")
    private static RpcInvocation realInvocation(URL consumerUrl) {
        RpcInvocation invocation = new RpcInvocation(
                "invoke",
                consumerUrl.getServiceInterface(),
                consumerUrl.getProtocolServiceKey(),
                new Class<?>[0],
                new Object[0]);
        invocation.setTargetServiceUniqueName(consumerUrl.getServiceKey());
        return invocation;
    }

    private static URL consumerUrl(String interfaceName, String group, String version) {
        URL url = URL.valueOf("dubbo://127.0.0.1/" + interfaceName);
        if (group != null && !group.isBlank()) {
            url = url.addParameter("group", group);
        }
        if (version != null && !version.isBlank()) {
            url = url.addParameter("version", version);
        }
        return url;
    }
}
