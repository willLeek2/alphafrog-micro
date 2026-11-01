package world.willfrog.alphafrogmicro.frontend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.HttpClientErrorException;
import world.willfrog.alphafrogmicro.frontend.service.AdminUserAccessService;
import world.willfrog.alphafrogmicro.frontend.service.debug.DomesticMarketSampleClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMarketSampleControllerTest {

    @Mock
    private AdminUserAccessService adminUserAccessService;
    @Mock
    private DomesticMarketSampleClient marketSampleClient;

    private AdminMarketSampleController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminMarketSampleController(adminUserAccessService, marketSampleClient);
    }









    private Authentication authentication(String username) {
        return mock(Authentication.class, username);
    }

}
