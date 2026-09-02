package world.willfrog.alphafrogmicro.common.gray;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * 业务代码使用灰度规则的唯一判断入口。
 *
 * <p>业务模块只调用 {@link #isEnabled(String, String)}，不能在服务内部另写百分比、名单或过期
 * 判断。默认关闭时自动装配仍会提供这个类型的 Bean，但所有判断恒为 {@code false}。</p>
 */
public final class GrayDecider {

    private static final BigInteger ONE_HUNDRED = BigInteger.valueOf(100);

    private final GrayRuleStore store;
    private final Clock clock;

    public GrayDecider(GrayRuleStore store) {
        this(Objects.requireNonNull(store, "store"), Clock.systemUTC());
    }

    GrayDecider(GrayRuleStore store, Clock clock) {
        this.store = store;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private GrayDecider() {
        this.store = null;
        this.clock = Clock.systemUTC();
    }

    public static GrayDecider disabled() {
        return new GrayDecider();
    }

    public boolean isEnabled(String ruleId, String userId) {
        if (store == null || ruleId == null) {
            return false;
        }

        GrayRuleStore.Snapshot current = store.currentSnapshot();
        GrayRuleDefinition rule = current.rules().get(ruleId);
        if (rule == null || !rule.isEnabled()) {
            return false;
        }

        Instant now = clock.instant();
        if (!now.isBefore(rule.expiresAtInstant())) {
            return false;
        }
        if (userId == null || userId.isEmpty()) {
            return false;
        }
        if (rule.containsUser(userId)) {
            return true;
        }
        return stableBucket(rule.getRuleId(), current.bucketSalt(), userId) < rule.getPercent();
    }

    static int stableBucket(String ruleId, String bucketSalt, String userId) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(bucketSalt, "bucketSalt");
        Objects.requireNonNull(userId, "userId");
        String input = ruleId + ":" + bucketSalt + ":" + userId;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        byte[] firstEightBytes = Arrays.copyOf(digest, Long.BYTES);
        return new BigInteger(1, firstEightBytes).mod(ONE_HUNDRED).intValue();
    }
}
