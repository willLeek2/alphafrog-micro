package world.willfrog.alphafrogmicro.common.gray;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一条灰度规则的不可变定义。
 *
 * <p>字段形状由 {@code deploy/gray/gray-rules.schema.json} 固定。调用方不能自行补默认值、
 * 改变大小写或清理空格，否则 Java 与其他语言会对同一份规则产生不同判断。</p>
 */
public final class GrayRuleDefinition {

    private final String ruleId;
    private final boolean enabled;
    private final int percent;
    private final List<String> userFilter;
    private final Set<String> userFilterIndex;
    private final String owner;
    private final String expiresAt;
    private final Instant expiresAtInstant;

    @JsonCreator
    public GrayRuleDefinition(
            @JsonProperty("ruleId") String ruleId,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("percent") int percent,
            @JsonProperty("userFilter") List<String> userFilter,
            @JsonProperty("owner") String owner,
            @JsonProperty("expiresAt") String expiresAt) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.enabled = enabled;
        this.percent = percent;
        this.userFilter = List.copyOf(Objects.requireNonNull(userFilter, "userFilter"));
        this.userFilterIndex = Set.copyOf(new HashSet<>(this.userFilter));
        this.owner = Objects.requireNonNull(owner, "owner");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.expiresAtInstant = OffsetDateTime.parse(expiresAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    }

    public String getRuleId() {
        return ruleId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPercent() {
        return percent;
    }

    public List<String> getUserFilter() {
        return userFilter;
    }

    public String getOwner() {
        return owner;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public Instant expiresAtInstant() {
        return expiresAtInstant;
    }

    boolean containsUser(String userId) {
        return userFilterIndex.contains(userId);
    }
}
