package world.willfrog.agent.platform.finance;

import java.util.List;

/** Presence-aware projection of proto field 11. A null instance means absent. */
public record FinanceEnvironmentFact(
        String environmentId,
        String imageDigest,
        String librarySetDigest,
        List<PackageApi> packageApis,
        boolean inventoryComplete) {

    public FinanceEnvironmentFact {
        environmentId = normalize(environmentId);
        imageDigest = normalize(imageDigest);
        librarySetDigest = normalize(librarySetDigest);
        packageApis = packageApis == null ? List.of() : List.copyOf(packageApis);
    }

    public PackageApi findPackage(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return packageApis.stream()
                .filter(item -> name.equals(item.name()))
                .findFirst()
                .orElse(null);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record PackageApi(String name, String version, String apiVersion) {
        public PackageApi {
            name = normalize(name);
            version = normalize(version);
            apiVersion = normalize(apiVersion);
        }
    }
}
