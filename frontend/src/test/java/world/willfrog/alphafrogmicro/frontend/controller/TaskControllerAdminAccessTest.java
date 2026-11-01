package world.willfrog.alphafrogmicro.frontend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import world.willfrog.alphafrogmicro.frontend.config.TaskProducerRabbitConfig;
import world.willfrog.alphafrogmicro.frontend.service.AdminUserAccessService;
import world.willfrog.alphafrogmicro.frontend.service.FetchTaskStatusService;
import world.willfrog.alphafrogmicro.frontend.service.RateLimitingService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskControllerAdminAccessTest {

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private RateLimitingService rateLimitingService;
    @Mock
    private FetchTaskStatusService fetchTaskStatusService;
    @Mock
    private AdminUserAccessService adminUserAccessService;
    @Mock
    private Authentication authentication;

    private TaskController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskController(
                rabbitTemplate,
                rateLimitingService,
                fetchTaskStatusService,
                adminUserAccessService);
        when(rateLimitingService.tryAcquire("task")).thenReturn(true);
    }



}
