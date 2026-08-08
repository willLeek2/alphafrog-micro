package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Compares record-declared, execution-actual, and resolver-target environment facts. */
@Component
public class FinanceEnvironmentVerifier {

    public static final String FINANCE_PACKAGE = "alphafrog_finance";

    public Verification verify(
            JsonNode record,
            FinanceEnvironmentFact actual,
            FinanceEnvironmentFact resolverTarget) {
        FinanceEvidenceLevel declared = parseEvidence(record == null ? null : record.path("evidence").asText(null));
        FinanceEvidenceLevel effective = declared;
        List<String> reasons = new ArrayList<>();

        String declaredEnvironmentId = text(record, "environmentId");
        if (actual == null || actual.environmentId().isBlank()) {
            reasons.add("ACTUAL_ENVIRONMENT_MISSING");
        } else if (!declaredEnvironmentId.isBlank()
                && !declaredEnvironmentId.equals(actual.environmentId())) {
            reasons.add("DECLARED_ACTUAL_ENVIRONMENT_MISMATCH");
        }

        boolean crossEnvironment = false;
        if (resolverTarget != null && !resolverTarget.environmentId().isBlank()
                && actual != null && !actual.environmentId().isBlank()
                && !resolverTarget.environmentId().equals(actual.environmentId())) {
            crossEnvironment = true;
            reasons.add("FINANCE_CROSS_ENVIRONMENT");
        }

        boolean packageCompatible = verifyPackage(actual, resolverTarget, declared, reasons);
        if (!reasons.isEmpty()) {
            effective = FinanceEvidenceLevel.CUSTOM_UNVERIFIED;
        }
        return new Verification(declared, effective, crossEnvironment, packageCompatible, reasons);
    }

    private static boolean verifyPackage(
            FinanceEnvironmentFact actual,
            FinanceEnvironmentFact resolverTarget,
            FinanceEvidenceLevel declared,
            List<String> reasons) {
        FinanceEnvironmentFact.PackageApi expected = resolverTarget == null
                ? null : resolverTarget.findPackage(FINANCE_PACKAGE);
        boolean packageRequired = declared == FinanceEvidenceLevel.LIBRARY_CALL_DECLARED || expected != null;
        if (!packageRequired) {
            return true;
        }
        if (actual == null || !actual.inventoryComplete()) {
            reasons.add("ACTUAL_PACKAGE_INVENTORY_INCOMPLETE");
            return false;
        }
        FinanceEnvironmentFact.PackageApi actualPackage = actual.findPackage(FINANCE_PACKAGE);
        if (actualPackage == null || actualPackage.apiVersion().isBlank()) {
            reasons.add("ACTUAL_PACKAGE_API_MISSING");
            return false;
        }
        if (expected != null && !expected.apiVersion().isBlank()
                && !expected.apiVersion().equals(actualPackage.apiVersion())) {
            reasons.add("ACTUAL_PACKAGE_API_INCOMPATIBLE");
            return false;
        }
        return true;
    }

    private static FinanceEvidenceLevel parseEvidence(String value) {
        if (value == null || value.isBlank()) {
            return FinanceEvidenceLevel.CUSTOM_UNVERIFIED;
        }
        try {
            return FinanceEvidenceLevel.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return FinanceEvidenceLevel.CUSTOM_UNVERIFIED;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || !node.get(field).isTextual()) {
            return "";
        }
        return node.get(field).asText().trim();
    }

    public record Verification(
            FinanceEvidenceLevel declaredEvidence,
            FinanceEvidenceLevel effectiveEvidence,
            boolean crossEnvironment,
            boolean packageCompatible,
            List<String> reasons) {
        public Verification {
            reasons = List.copyOf(reasons);
        }
    }
}
