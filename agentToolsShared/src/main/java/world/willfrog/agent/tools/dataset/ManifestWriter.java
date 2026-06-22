package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 1 manifest 写侧。
 * 输入一批 atomic member，输出稳定 manifestId 并落盘 {@code {manifestId}.manifest.json} + .meta.json。
 * 哈希输入按 tsCode 排序，避免同一组资产只因输入顺序不同产生多个 manifest。
 *
 * 复用与 atomic 共用的 {@code agent.tools.market-data.dataset.path} 配置；不打散到独立根目录，
 * 方便 sandbox / workspace 在 dataset.path 下做统一扫描和回收。
 */
@Component
@Slf4j
public class ManifestWriter {

    @Value("${agent.tools.market-data.dataset.path:/data/agent_datasets}")
    private String datasetPath;

    @Value("${agent.tools.market-data.dataset.manifests-path:/data/manifests}")
    private String manifestsPath;

    @Value("${agent.tools.market-data.dataset.enabled:true}")
    private boolean enabled;

    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return resolveEnabled();
    }

    /**
     * 写盘一份 manifest，返回稳定 manifestId。
     *
     * <p>职责分离：A 块只认 {@code agent.tools.market-data.dataset.enabled}（与 {@link DatasetWriter}
     * 共享），关闭时返回 null，不写盘、不抛异常。B 块新增的 {@code agent.tools.market-data.batch.emit-manifest}
     * 属于 batch 工具层，调用本方法前自行判断，不要让两个 flag 在 A 块内部耦合。
     *
     * @param dataType     内部数据来源，例如 {@code stock_daily} / {@code index_daily} / {@code etf_daily}
     * @param startDate    形如 {@code 20240101}
     * @param endDate      形如 {@code 20240131}
     * @param members      准备收录的 atomic member 列表，调用方无需排序，writer 内部按 tsCode 升序
     * @param totalRowCount 所有 member 实际写入行数之和
     * @param columns      manifest 描述的列集合；用于 columnsSignature 与 queryKey
     * @return 稳定 manifestId；{@code dataset.enabled=false} 时返回 null
     */
    public String writeManifest(String dataType,
                                String startDate,
                                String endDate,
                                List<DatasetManifest.ManifestMember> members,
                                int totalRowCount,
                                List<String> columns) {
        if (!resolveEnabled()) {
            return null;
        }
        if (dataType == null || dataType.isEmpty()
                || startDate == null || startDate.isEmpty()
                || endDate == null || endDate.isEmpty()) {
            throw new IllegalArgumentException(
                    "dataType/startDate/endDate must be non-empty for manifest write");
        }
        if (members == null) {
            members = new ArrayList<>();
        }
        if (columns == null) {
            columns = new ArrayList<>();
        }

        List<DatasetManifest.ManifestMember> sortedMembers = members.stream()
                .sorted(Comparator.comparing(DatasetManifest.ManifestMember::getTsCode,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        String columnsSignature = String.join(",", columns);
        String sortedTsCodesSignature = sortedMembers.stream()
                .map(m -> m.getTsCode() == null ? "" : m.getTsCode())
                .collect(Collectors.joining(","));
        String queryKey = buildQueryKey(dataType, startDate, endDate, sortedTsCodesSignature, columnsSignature);
        String hash8 = sha256Hex(queryKey).substring(0, 8);
        String safeDataType = dataType.replaceAll("[^a-zA-Z0-9]", "_");
        String manifestId = String.format("manifest-%s-%s-%s-%s", safeDataType, startDate, endDate, hash8);

        int readyCount = (int) sortedMembers.stream()
                .filter(m -> DatasetManifest.ManifestMember.STATUS_READY.equals(m.getStatus()))
                .count();
        int failedCount = (int) sortedMembers.stream()
                .filter(m -> DatasetManifest.ManifestMember.STATUS_FAILED.equals(m.getStatus()))
                .count();
        int brokenCount = (int) sortedMembers.stream()
                .filter(m -> DatasetManifest.ManifestMember.STATUS_BROKEN.equals(m.getStatus()))
                .count();
        long createdAt = Instant.now().toEpochMilli();

        DatasetManifest manifest = DatasetManifest.builder()
                .manifestId(manifestId)
                .kind(DatasetManifest.KIND)
                .dataType(dataType)
                .startDate(startDate)
                .endDate(endDate)
                .memberCount(sortedMembers.size())
                .readyCount(readyCount)
                .failedCount(failedCount)
                .brokenCount(brokenCount)
                .totalRowCount(totalRowCount)
                .columns(new ArrayList<>(columns))
                .columnsSignature(columnsSignature)
                .members(sortedMembers)
                .createdAt(createdAt)
                .build();

        Path manifestDir = DatabaseFetchedPathStrategy.resolveManifestPath(
                Paths.get(manifestsPath), manifestId);
        try {
            Files.createDirectories(manifestDir);
        } catch (IOException e) {
            log.error("Failed to create manifest dir: {}", manifestDir, e);
            throw new RuntimeException("Failed to create manifest directory: " + manifestDir, e);
        }

        Path manifestJson = manifestDir.resolve("manifest.json");
        Path metaJson = manifestDir.resolve("meta.json");

        try {
            objectMapper.writeValue(manifestJson.toFile(), manifest);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize manifest JSON: {}", manifestId, e);
            throw new RuntimeException("Failed to write manifest JSON: " + manifestId, e);
        } catch (IOException e) {
            log.error("Failed to write manifest JSON: {}", manifestId, e);
            throw new RuntimeException("Failed to write manifest JSON: " + manifestId, e);
        }

        ManifestMeta meta = ManifestMeta.builder()
                .manifestId(manifestId)
                .queryKey(queryKey)
                .dataType(dataType)
                .startDate(startDate)
                .endDate(endDate)
                .memberCount(sortedMembers.size())
                .readyCount(readyCount)
                .failedCount(failedCount)
                .totalRowCount(totalRowCount)
                .path(manifestDir.toAbsolutePath().toString())
                .createdAt(createdAt)
                .build();
        try {
            objectMapper.writeValue(metaJson.toFile(), meta);
        } catch (IOException e) {
            log.warn("Failed to write manifest meta JSON: {}", manifestId, e);
        }

        log.info("Wrote manifest {} (members={}, ready={}, failed={}, broken={}, totalRows={})",
                manifestId, sortedMembers.size(), readyCount, failedCount, brokenCount, totalRowCount);
        return manifestId;
    }

    /**
     * 给定参数计算 manifest query key，便于测试和预生成 manifestId。
     * 输入会按 tsCode 排序，调用方传入顺序不影响结果。
     */
    public String buildQueryKeyForMembers(String dataType, String startDate, String endDate,
                                          List<String> tsCodes, List<String> columns) {
        List<String> codes = tsCodes == null ? new ArrayList<>() : new ArrayList<>(tsCodes);
        String sortedTsCodesSignature = codes.stream()
                .sorted()
                .collect(Collectors.joining(","));
        String columnsSignature = columns == null ? "" : String.join(",", columns);
        return buildQueryKey(dataType, startDate, endDate, sortedTsCodesSignature, columnsSignature);
    }

    private String buildQueryKey(String dataType, String startDate, String endDate,
                                 String sortedTsCodesSignature, String columnsSignature) {
        return "manifest|" + dataType + "|" + startDate + "|" + endDate
                + "|" + sortedTsCodesSignature + "|" + columnsSignature;
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private boolean resolveEnabled() {
        if (localConfigLoader == null) {
            return enabled;
        }
        return localConfigLoader.current()
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getMarketData)
                .map(AgentLlmProperties.MarketData::getDataset)
                .map(AgentLlmProperties.MarketDataDataset::getEnabled)
                .orElse(enabled);
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ManifestMeta {
        private String manifestId;
        private String queryKey;
        private String dataType;
        private String startDate;
        private String endDate;
        private int memberCount;
        private int readyCount;
        private int failedCount;
        private int totalRowCount;
        private String path;
        private long createdAt;
    }
}
