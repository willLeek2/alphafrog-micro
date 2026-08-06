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

    /**
     * 覆盖「事务开始时 ledger 不存在 → 拿 user 行锁 → 锁后 ledger 已被并发线程插入」这一窗口期：
     * 二次 check 命中后必须放弃扣款、也不重复 insert，避免双扣。
     */
    @Test
    void debitAndWriteLedger_ledgerInsertedByConcurrentTxnAfterLock_skipsDebitAndInsert() {
        User user = new User();
        user.setUserId(42L);
        user.setCredit(new BigDecimal("10.000000"));
        when(userDao.getUserByIdForUpdate(42L)).thenReturn(user);

        // 第一次查 ledger = null（fast-path 命中失败，进入 lock 路径）
        // 第二次查 ledger = existing（模拟锁等待期间被另一事务插入）
        when(ledgerDao.findByBizSourceIdempotency(
                eq("RUN_SETTLEMENT_IMMEDIATE"), eq("run-x"), eq("run-x:immediate")))
                .thenReturn(null)                              // fast-path miss
                .thenReturn(new AgentCreditLedger());          // post-lock hit

        AgentCreditLedger ledger = baseLedger("42", "run-x", "run-x:immediate");
        operator.debitAndWriteLedger(ledger, new BigDecimal("1.500000"), false, 42L);

        // 关键断言：拿锁后命中 → 不能扣款、也不能 insert。
        verify(userDao, never()).decreaseCreditByUserIdDecimal(any(), any());
        verify(ledgerDao, never()).insertIgnoreDuplicate(any());

        // 顺序：必须先 fast-path miss，再拿锁，再 post-lock hit。
        InOrder order = inOrder(ledgerDao, userDao);
        order.verify(ledgerDao).findByBizSourceIdempotency(
                "RUN_SETTLEMENT_IMMEDIATE", "run-x", "run-x:immediate");
        order.verify(userDao).getUserByIdForUpdate(42L);
        order.verify(ledgerDao).findByBizSourceIdempotency(
                "RUN_SETTLEMENT_IMMEDIATE", "run-x", "run-x:immediate");
    }

    /**
     * ON CONFLICT DO NOTHING 在并发场景下可能返回 0（被另一个事务的同 idem 抢插），
     * 此时必须抛 RuntimeException，让 @Transactional 回滚刚才的 decreaseCreditByUserIdDecimal，
     * 避免「余额被扣但 ledger 没落地」的双账。
     */
    @Test
    void debitAndWriteLedger_insertReturnsZero_throwsToRollbackDebit() {
        User user = new User();
        user.setUserId(42L);
        user.setCredit(new BigDecimal("10.000000"));
        when(userDao.getUserByIdForUpdate(42L)).thenReturn(user);
        when(userDao.decreaseCreditByUserIdDecimal(eq(42L), eq(new BigDecimal("1.500000"))))
                .thenReturn(1);
        when(ledgerDao.findByBizSourceIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(null);
        when(ledgerDao.insertIgnoreDuplicate(any())).thenReturn(0);  // 并发抢插

        AgentCreditLedger ledger = baseLedger("42", "run-x", "run-x:immediate");

        assertThrows(IllegalStateException.class,
                () -> operator.debitAndWriteLedger(ledger, new BigDecimal("1.500000"), false, 42L));

        // 抛异常时，debit 已经被调用过了（由 @Transactional 触发回滚），无需额外断言。
        // 关键是 insert 返回 0 → 必须抛异常，不允许吞掉这个事件。
        verify(ledgerDao).insertIgnoreDuplicate(any());
    }

    /**
     * admin 路径（delta=0）也要检查 insert 返回值：避免 audit 审计落空却没被发现。
     */
    @Test
    void debitAndWriteLedger_adminInsertReturnsZero_throws() {
        when(ledgerDao.findByBizSourceIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(null);
        when(ledgerDao.insertIgnoreDuplicate(any())).thenReturn(0);

        AgentCreditLedger ledger = baseLedger("1127", "run-admin", "run-admin:immediate");

        assertThrows(IllegalStateException.class,
                () -> operator.debitAndWriteLedger(ledger, new BigDecimal("2.000000"), true, 1127L));

        verify(userDao, never()).getUserByIdForUpdate(any());
        verify(userDao, never()).decreaseCreditByUserIdDecimal(any(), any());
        verify(ledgerDao).insertIgnoreDuplicate(any());
    }

    @Test
    void debitAndWriteLedger_ordinaryUser_locksRowDebitsAndInsertsLedger() {
        // 已锁后的 user.credit = 10，要扣 1.5，期望余额变 8.5，写一条 delta=-1.5 的 ledger。
        User user = new User();
        user.setUserId(42L);
        user.setCredit(new BigDecimal("10.000000"));
        when(userDao.getUserByIdForUpdate(42L)).thenReturn(user);
        when(userDao.decreaseCreditByUserIdDecimal(eq(42L), eq(new BigDecimal("1.500000"))))
                .thenReturn(1);
        when(ledgerDao.findByBizSourceIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(null);

        AgentCreditLedger ledger = baseLedger("42", "run-x", "run-x:immediate");
        operator.debitAndWriteLedger(ledger, new BigDecimal("1.500000"), false, 42L);

        verify(userDao).getUserByIdForUpdate(42L);
        verify(userDao).decreaseCreditByUserIdDecimal(eq(42L), eq(new BigDecimal("1.500000")));
        ArgumentCaptor<AgentCreditLedger> captor = ArgumentCaptor.forClass(AgentCreditLedger.class);
        verify(ledgerDao).insertIgnoreDuplicate(captor.capture());
        AgentCreditLedger written = captor.getValue();
        assertNotNull(written.getLedgerId());
        assertEquals(new BigDecimal("-1.500000"), written.getDelta());
        assertEquals(new BigDecimal("10.000000"), written.getBalanceBefore());
        assertEquals(new BigDecimal("8.500000"), written.getBalanceAfter());
        assertEquals("RUN_SETTLEMENT_IMMEDIATE", written.getBizType());
        assertEquals("run-x", written.getSourceId());
        assertEquals("run-x:immediate", written.getIdempotencyKey());
    }

    @Test
    void debitAndWriteLedger_idempotencyHit_skipsDebitAndLedger() {
        // 同一 (bizType, sourceId, idempotencyKey) 的 ledger 已存在：必须既不扣余额也不重复插入 ledger。
        AgentCreditLedger existing = new AgentCreditLedger();
        existing.setLedgerId("prev-ledger");
        when(ledgerDao.findByBizSourceIdempotency(
                eq("RUN_SETTLEMENT_IMMEDIATE"), eq("run-x"), eq("run-x:immediate")))
                .thenReturn(existing);

        AgentCreditLedger ledger = baseLedger("42", "run-x", "run-x:immediate");
        operator.debitAndWriteLedger(ledger, new BigDecimal("1.500000"), false, 42L);

        verify(userDao, never()).getUserByIdForUpdate(any());
        verify(userDao, never()).decreaseCreditByUserIdDecimal(any(), any());
        verify(ledgerDao, never()).insertIgnoreDuplicate(any());
    }

    @Test
    void debitAndWriteLedger_admin_doesNotDebitOnlyAudits() {
        // admin 不锁、不扣余额，只写一条 delta=0 / balance=0 的审计 ledger。
        when(ledgerDao.findByBizSourceIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(null);

        AgentCreditLedger ledger = baseLedger("1127", "run-admin", "run-admin:immediate");
        operator.debitAndWriteLedger(ledger, new BigDecimal("2.000000"), true, 1127L);

        verify(userDao, never()).getUserByIdForUpdate(any());
        verify(userDao, never()).decreaseCreditByUserIdDecimal(any(), any());
        ArgumentCaptor<AgentCreditLedger> captor = ArgumentCaptor.forClass(AgentCreditLedger.class);
        verify(ledgerDao).insertIgnoreDuplicate(captor.capture());
        AgentCreditLedger written = captor.getValue();
        assertEquals(BigDecimal.ZERO.setScale(6), written.getDelta().setScale(6));
        assertEquals(BigDecimal.ZERO.setScale(6), written.getBalanceBefore().setScale(6));
        assertEquals(BigDecimal.ZERO.setScale(6), written.getBalanceAfter().setScale(6));
    }

    @Test
    void debitAndWriteLedger_invalidUserId_skipsDebitWritesAuditLedger() {
        // 解析不到 userIdLong（null）：不能扣余额，但仍写一条 delta=0 的审计 ledger 保留事件痕迹。
        when(ledgerDao.findByBizSourceIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(null);

        AgentCreditLedger ledger = baseLedger("invalid", "run-y", "run-y:immediate");
        operator.debitAndWriteLedger(ledger, new BigDecimal("0.500000"), false, null);

        verify(userDao, never()).getUserByIdForUpdate(any());
        verify(userDao, never()).decreaseCreditByUserIdDecimal(any(), any());
        ArgumentCaptor<AgentCreditLedger> captor = ArgumentCaptor.forClass(AgentCreditLedger.class);
        verify(ledgerDao).insertIgnoreDuplicate(captor.capture());
        assertEquals(BigDecimal.ZERO.setScale(6), captor.getValue().getDelta().setScale(6));
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
