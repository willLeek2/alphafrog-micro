package world.willfrog.alphafrogmicro.adminservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

@Service
@Slf4j
public class AdminCreditExchangeRateService {

    private static final BigDecimal ONE = BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP);

    private final ObjectMapper objectMapper;

    @Value("${agent.llm.config-file:}")
    private String agentLlmConfigFile;

    @Value("${agent.credit.cny-to-usd-rate:0.14}")
    private String fallbackCnyToUsdRate;

    public AdminCreditExchangeRateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BigDecimal exchangeRateToUsd(String currency) {
        String normalized = currency == null ? "" : currency.trim().toUpperCase();
        if ("USD".equals(normalized)) {
            return ONE;
        }
        if (!"CNY".equals(normalized)) {
            throw new IllegalArgumentException("currency should be USD or CNY");
        }
        return configuredCnyToUsdRate();
    }

    private BigDecimal configuredCnyToUsdRate() {
        BigDecimal fromFile = readRateFromAgentLlmConfig();
        if (fromFile != null && fromFile.compareTo(BigDecimal.ZERO) > 0) {
            return fromFile;
        }
        BigDecimal fallback = parsePositiveDecimal(fallbackCnyToUsdRate);
        if (fallback == null) {
            return new BigDecimal("0.14").setScale(6, RoundingMode.HALF_UP);
        }
        return normalizeRate(fallback);
    }

    private BigDecimal readRateFromAgentLlmConfig() {
        if (agentLlmConfigFile == null || agentLlmConfigFile.isBlank()) {
            return null;
        }
        Path path = Path.of(agentLlmConfigFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            BigDecimal direct = firstPositiveDecimal(root,
                    "credit.cnyToUsdRate",
                    "credit.cny_to_usd_rate",
                    "credit.cnyUsdRate",
                    "credit.cny_usd_rate",
                    "billing.cnyToUsdRate",
                    "billing.cny_to_usd_rate",
                    "billing.cnyUsdRate",
                    "billing.cny_usd_rate");
            if (direct != null) {
                return normalizeRate(direct);
            }
            BigDecimal cnyPerUsd = firstPositiveDecimal(root,
                    "credit.cnyPerUsd",
                    "credit.cny_per_usd",
                    "billing.cnyPerUsd",
                    "billing.cny_per_usd");
            if (cnyPerUsd != null && cnyPerUsd.compareTo(BigDecimal.ZERO) > 0) {
                return BigDecimal.ONE.divide(cnyPerUsd, 6, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            log.warn("Failed to read agent llm credit exchange rate config: {}", path, e);
        }
        return null;
    }

    private BigDecimal firstPositiveDecimal(JsonNode root, String... paths) {
        for (String path : paths) {
            BigDecimal value = parsePositiveDecimal(nodeAt(root, path));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private JsonNode nodeAt(JsonNode root, String dottedPath) {
        JsonNode current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            current = fieldIgnoreCaseAndStyle(current, segment);
        }
        return current;
    }

    private JsonNode fieldIgnoreCaseAndStyle(JsonNode node, String expected) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode direct = node.get(expected);
        if (direct != null) {
            return direct;
        }
        String normalizedExpected = normalizeKey(expected);
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (normalizeKey(name).equals(normalizedExpected)) {
                return node.get(name);
            }
        }
        return null;
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.replace("_", "").replace("-", "").toLowerCase();
    }

    private BigDecimal parsePositiveDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber() || node.isTextual()) {
            return parsePositiveDecimal(node.asText());
        }
        return null;
    }

    private BigDecimal parsePositiveDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            return parsed.compareTo(BigDecimal.ZERO) > 0 ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal normalizeRate(BigDecimal raw) {
        if (raw.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE.divide(raw, 6, RoundingMode.HALF_UP);
        }
        return raw.setScale(6, RoundingMode.HALF_UP);
    }
}
