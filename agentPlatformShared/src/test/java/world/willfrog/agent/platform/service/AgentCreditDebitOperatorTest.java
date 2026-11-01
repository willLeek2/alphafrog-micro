package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditLedgerDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditLedger;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentCreditDebitOperatorTest {

    @Mock
    private UserDao userDao;
    @Mock
    private AgentCreditLedgerDao ledgerDao;

    private AgentCreditDebitOperator operator;

    @BeforeEach
    void setUp() {
        operator = new AgentCreditDebitOperator(userDao, ledgerDao);
        // 默认 insert 成功（返回 1）。新加的 race/throw 测试会显式覆盖。
        org.mockito.Mockito.lenient()
                .when(ledgerDao.insertIgnoreDuplicate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
    }








    private AgentCreditLedger baseLedger(String userId, String runId, String idem) {
        AgentCreditLedger ledger = new AgentCreditLedger();
        ledger.setLedgerId("ledger-" + runId);
        ledger.setUserId(userId);
        ledger.setBizType("RUN_SETTLEMENT_IMMEDIATE");
        ledger.setSourceType("AGENT_RUN");
        ledger.setSourceId(runId);
        ledger.setIdempotencyKey(idem);
        ledger.setReason("test_reason");
        ledger.setExt("{}");
        return ledger;
    }
}
