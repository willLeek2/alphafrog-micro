package world.willfrog.agent.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditLedgerDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditLedger;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 独立 @Service：把「锁余额 + 扣减 + 写 ledger」放在同一个 Spring 事务里。
 *
 * 必须独立于 {@link AgentRunCreditSettlementService}，因为同 bean 内 self-invocation
 * 不会经过 Spring AOP 代理，@Transactional 注解会失效。通过依赖注入这一独立服务，
 * 调用 {@link #debitAndWriteLedger} 时事务才真正生效。
 *
 * 事务内先按 (bizType, sourceId, idempotencyKey) 查 ledger 是否已存在：已存在则不扣款、
 * 也不重复 insert，保证「重复结算」不会出现「ledger 唯一冲突但余额被多扣」的竞态。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentCreditDebitOperator {

    private final UserDao userDao;
    private final AgentCreditLedgerDao ledgerDao;

    /**
     * 原子执行（防御「先 check → 拿锁 → insert」窗口期的并发双扣）：
     * <ol>
     *   <li>事务开始先查一次 (bizType, sourceId, idempotencyKey) 作 fast-path 幂等：已存在则直接返回。</li>
     *   <li>普通用户且 userIdLong 有效：FOR UPDATE 拿 user 行锁，<b>锁后</b>再查一次 ledger。
     *       如果锁后被并发线程插入了同 idem 的 ledger（race），放弃扣款 + 不重复 insert。</li>
     *   <li>二次查仍不存在才走 debit：decreaseCreditByUserIdDecimal → balanceBefore/After 来自锁后真实值。</li>
     *   <li>admin: 不锁、不扣减，写一条 delta=0 / balance=0 的审计 ledger。</li>
     *   <li>普通用户但 userIdLong 无法解析: 仅写一条 delta=0 的 audit ledger 并记 warn 日志。</li>
     *   <li><b>insert 返回值必须 = 1</b>，否则代表唯一约束被并发覆盖/DB 异常，抛 RuntimeException 触发事务回滚，
     *       避免「余额被扣但 ledger 写入失败」导致的双账。</li>
     * </ol>
     *
     * @param ledger     已预填 ledgerId / bizType / sourceType / sourceId / idempotencyKey /
     *                   reason / ext / userId 等字段；本方法按需覆盖 delta / balanceBefore /
     *                   balanceAfter 三个字段。
     * @param amount     正数扣款金额（&gt;0）。
     * @param isAdmin    是否管理员（admin 不扣余额，仅审计）。
     * @param userIdLong 解析后的数字 userId；null 表示解析失败，不会扣余额。
     */
    @Transactional
    public void debitAndWriteLedger(AgentCreditLedger ledger, BigDecimal amount,
                                    boolean isAdmin, Long userIdLong) {
        if (ledger == null || amount == null || amount.signum() <= 0) {
            return;
        }
        // ---- Fast-path idempotency check (事务开始，未持锁) ----
        if (ledgerDao.findByBizSourceIdempotency(
                ledger.getBizType(), ledger.getSourceId(), ledger.getIdempotencyKey()) != null) {
            log.info("Ledger already exists (fast-path), skip debit: bizType={} sourceId={} idem={}",
                    ledger.getBizType(), ledger.getSourceId(), ledger.getIdempotencyKey());
            return;
        }

        BigDecimal balanceBefore = BigDecimal.ZERO;
        BigDecimal balanceAfter = BigDecimal.ZERO;
        BigDecimal signedDelta = BigDecimal.ZERO;

        if (isAdmin) {
            log.info("Admin settlement audit-only ledger: sourceId={} amount={}",
                    ledger.getSourceId(), amount);
        } else if (userIdLong != null) {
            // ---- Critical section: 拿 user 行锁后必须再查一次 ledger ----
            // 在我们拿锁的窗口里，另一个线程可能也持过锁并插入了同 idem 的 ledger；
            // 这里通过「锁后再查」避免重复扣款。
            User user = userDao.getUserByIdForUpdate(userIdLong);
            if (user != null && user.getCredit() != null) {
                balanceBefore = user.getCredit().max(BigDecimal.ZERO);
            }
            if (ledgerDao.findByBizSourceIdempotency(
                    ledger.getBizType(), ledger.getSourceId(), ledger.getIdempotencyKey()) != null) {
                log.warn("Ledger inserted by concurrent transaction after lock, skip debit: " +
                                "bizType={} sourceId={} idem={}",
                        ledger.getBizType(), ledger.getSourceId(), ledger.getIdempotencyKey());
                return;
            }
            int affected = userDao.decreaseCreditByUserIdDecimal(userIdLong, amount);
            if (affected <= 0) {
                throw new IllegalStateException("Failed to decrease user credit: userId="
                        + userIdLong + " amount=" + amount);
            }
            balanceAfter = balanceBefore.subtract(amount).max(BigDecimal.ZERO);
            signedDelta = amount.negate();
        } else {
            log.warn("Skip debit, invalid userId in ledger.userId={} sourceId={}",
                    ledger.getUserId(), ledger.getSourceId());
        }

        ledger.setDelta(signedDelta);
        ledger.setBalanceBefore(balanceBefore.setScale(6, RoundingMode.HALF_UP));
        ledger.setBalanceAfter(balanceAfter.setScale(6, RoundingMode.HALF_UP));
        // ---- 严格检查 insert 返回值 ----
        // ON CONFLICT DO NOTHING 在并发场景下可能返回 0（被另一个事务的同 idem 抢插），
        // 这种情况我们必须抛异常，让 @Transactional 回滚刚才的 decreaseCreditByUserIdDecimal，
        // 避免「余额被扣但 ledger 没落地」的双账。
        int inserted = ledgerDao.insertIgnoreDuplicate(ledger);
        if (inserted != 1) {
            throw new IllegalStateException("Ledger insert returned " + inserted
                    + " (expected 1) for bizType=" + ledger.getBizType()
                    + " sourceId=" + ledger.getSourceId()
                    + " idem=" + ledger.getIdempotencyKey());
        }
    }
}
