package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditApplicationDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditLedgerDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentCreditServiceTest {

    @Mock
    private UserDao userDao;
    @Mock
    private AgentCreditApplicationDao creditApplicationDao;
    @Mock
    private AgentCreditLedgerDao creditLedgerDao;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunEventService eventService;
    @Mock
    private AgentModelCatalogService modelCatalogService;

    private AgentCreditService service;

    @BeforeEach
    void setUp() {
        service = new AgentCreditService(
                userDao,
                creditApplicationDao,
                creditLedgerDao,
                runMapper,
                eventService,
                modelCatalogService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "defaultToolCost", 1);
        ReflectionTestUtils.setField(service, "executePythonCost", 5);
    }



}
