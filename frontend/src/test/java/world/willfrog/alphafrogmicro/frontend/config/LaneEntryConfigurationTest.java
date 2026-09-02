package world.willfrog.alphafrogmicro.frontend.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.frontend.filter.FetchAccessFilter;
import world.willfrog.alphafrogmicro.frontend.filter.JwtAuthFilter;
import world.willfrog.alphafrogmicro.frontend.filter.LaneWebFilter;

class LaneEntryConfigurationTest {

    @Test
    void allSecurityChainFiltersHaveServletRegistrationDisabled() {
        LaneEntryConfiguration configuration = new LaneEntryConfiguration();

        assertFalse(configuration.jwtAuthFilterRegistration(mock(JwtAuthFilter.class)).isEnabled());
        assertFalse(configuration.laneWebFilterRegistration(mock(LaneWebFilter.class)).isEnabled());
        assertFalse(configuration.fetchAccessFilterRegistration(mock(FetchAccessFilter.class)).isEnabled());
    }
}
