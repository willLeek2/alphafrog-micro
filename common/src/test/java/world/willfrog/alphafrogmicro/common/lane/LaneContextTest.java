package world.willfrog.alphafrogmicro.common.lane;

import org.apache.dubbo.common.constants.CommonConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LaneContextTest {

    @AfterEach
    void reset() {
        LaneContext.clear();
    }

    @Test
    void dubboTagKey_shouldMatchOfficialConstant() {
        assertThat(LaneContext.DUBBO_TAG_KEY).isEqualTo(CommonConstants.TAG_KEY);
    }

    @Test
    void officialDubboTag_shouldOmitBlankAndMainBeta() {
        assertThat(LaneContext.toOfficialDubboTag(null)).isNull();
        assertThat(LaneContext.toOfficialDubboTag("")).isNull();
        assertThat(LaneContext.toOfficialDubboTag("   ")).isNull();
        assertThat(LaneContext.toOfficialDubboTag(LaneContext.MAIN_BETA_TRAFFIC_SCOPE_ID)).isNull();
        assertThat(LaneContext.toOfficialDubboTag("lane-test")).isEqualTo("lane-test");
    }

    @Test
    void officialDubboTag_shouldReadCurrentThreadScope() {
        LaneContext.setTrafficScopeId("lane-a");
        assertThat(LaneContext.officialDubboTag()).isEqualTo("lane-a");
        LaneContext.setTrafficScopeId(LaneContext.MAIN_BETA_TRAFFIC_SCOPE_ID);
        assertThat(LaneContext.officialDubboTag()).isNull();
        LaneContext.clear();
        assertThat(LaneContext.officialDubboTag()).isNull();
    }
}
