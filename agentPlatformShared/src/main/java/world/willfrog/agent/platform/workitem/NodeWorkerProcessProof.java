package world.willfrog.agent.platform.workitem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** 根据同容器单 JVM 重启事实核对旧执行者是否退出；兼容旧版宿主进程身份。 */
public final class NodeWorkerProcessProof {
    private static final String LEGACY_PREFIX = "dual-pool-node:v3@";
    private static final String CONTAINER_PREFIX = "dual-pool-node:v4@";
    private static final String JVM_BOOT = UUID.randomUUID().toString().replace("-", "");
    private static final Path HOST_MACHINE_ID = Path.of("/run/alphafrog/host-machine-id");
    private static final Path HOST_BOOT_ID = Path.of("/proc/sys/kernel/random/boot_id");

    private NodeWorkerProcessProof() { }

    /**
     * 一次节点领取只需要不重复的 JVM 身份。Docker 容器身份与 JVM 启动身份一起落库；
     * 同一容器重启后，单 Java 主进程的旧调用栈已经退出。容器被替换时不能据此推断。
     */
    public static String claimant(String processIdentity) {
        if (processIdentity == null || processIdentity.isBlank()) {
            throw new IllegalArgumentException("节点领取缺少进程身份");
        }
        // 无容器身份时也能执行正常节点；旧进程退出无法证实，就保留旧额度等待人工核验。
        String container = containerId();
        String instance = instanceId();
        String claimant = CONTAINER_PREFIX + (instance == null ? "local" : instance) + "@"
                + (container == null ? "unknown" : container) + "@" + JVM_BOOT;
        if (claimant.length() > 128) {
            throw new IllegalStateException("节点领取进程证明超过数据库列上限");
        }
        return claimant;
    }

    public static boolean definitelyExited(String claimant) {
        ContainerOwner containerOwner = parseContainer(claimant);
        if (containerOwner != null) {
            return exitedByContainerObservation(claimant, instanceId(), containerId(), JVM_BOOT);
        }
        Owner owner = parse(claimant);
        if (owner == null) return false;
        String machine = hostMachineId();
        String boot = bootId();
        if (machine == null || boot == null || !owner.machine().equals(machine)) return false;
        // 同一宿主已重新启动内核时，旧内核里的线程必然已经退出。跨宿主不能推断。
        if (!owner.boot().equals(boot)) return true;
        if (!owner.namespace().equals(pidNamespace())) return false;
        try {
            var process = ProcessHandle.of(owner.pid());
            if (process.isEmpty()) return true;
            Instant observedStart = process.get().info().startInstant().orElse(null);
            return observedStart != null && observedStart.toEpochMilli() != owner.startedMillis();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** 部署环境是否能在同一容器重启后自动核实旧 JVM 退出。 */
    public static boolean canConfirmSameContainerRestart() {
        return instanceId() != null && containerId() != null;
    }

    public static boolean isCurrentProcess(String claimant) {
        ContainerOwner containerOwner = parseContainer(claimant);
        if (containerOwner != null) {
            return containerOwner.boot().equals(JVM_BOOT)
                    && containerOwner.instance().equals(currentInstanceField())
                    && containerOwner.container().equals(currentContainerField());
        }
        Owner owner = parse(claimant);
        if (owner == null || !owner.machine().equals(hostMachineId())
                || !owner.boot().equals(bootId())
                || !owner.namespace().equals(pidNamespace())
                || owner.pid() != ProcessHandle.current().pid()) return false;
        Instant currentStart = currentStart();
        return currentStart != null && currentStart.toEpochMilli() == owner.startedMillis();
    }

    /** 核对领取者属于当前容器；旧版 v3 仍按同一宿主识别。 */
    public static boolean recognizesClaimant(String claimant) {
        ContainerOwner containerOwner = parseContainer(claimant);
        if (containerOwner != null) {
            return containerOwner.instance().equals(currentInstanceField())
                    && containerOwner.container().equals(currentContainerField());
        }
        return recognizesByObservation(claimant, hostMachineId());
    }

    /** 只有原容器仍是这个容器，且新 JVM 身份不同，才能确认原调用栈已退出。 */
    static boolean exitedByContainerObservation(String claimant, String instance,
                                                String container, String currentBoot) {
        ContainerOwner owner = parseContainer(claimant);
        return owner != null && instance != null && container != null
                && owner.instance().equals(instance) && owner.container().equals(container)
                && currentBoot != null && !owner.boot().equals(currentBoot);
    }

    static boolean recognizesByObservation(String claimant, String machine) {
        Owner owner = parse(claimant);
        return owner != null && machine != null && owner.machine().equals(machine);
    }

    /** 测试进程重建、PID 复用及跨宿主时替换 OS 观察；生产路径只从内核读取。 */
    static boolean exitedByObservation(String claimant, String machine, String boot,
                                       String namespace, boolean pidPresent, Instant observedStart) {
        Owner owner = parse(claimant);
        if (owner == null || machine == null || boot == null
                || !owner.machine().equals(machine)) return false;
        if (!owner.boot().equals(boot)) return true;
        if (!owner.namespace().equals(namespace)) return false;
        if (!pidPresent) return true;
        return observedStart != null && observedStart.toEpochMilli() != owner.startedMillis();
    }

    private static Owner parse(String claimant) {
        if (claimant == null || !claimant.startsWith(LEGACY_PREFIX)) return null;
        String[] fields = claimant.substring(LEGACY_PREFIX.length()).split("@", -1);
        if (fields.length != 5 || !hex32(fields[0]) || !hex32(fields[1])
                || !fields[4].matches("[0-9a-z]{1,13}")) return null;
        try {
            long pid = Long.parseLong(fields[2]);
            long startedMillis = Long.parseLong(fields[3]);
            return pid > 0 && startedMillis > 0
                    ? new Owner(fields[0], fields[1], pid, startedMillis, fields[4]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ContainerOwner parseContainer(String claimant) {
        if (claimant == null || !claimant.startsWith(CONTAINER_PREFIX)) return null;
        String[] fields = claimant.substring(CONTAINER_PREFIX.length()).split("@", -1);
        if (fields.length != 3 || fields[0].isBlank() || fields[1].isBlank()
                || !hex32(fields[2])) return null;
        return new ContainerOwner(fields[0], fields[1], fields[2]);
    }

    private static String instanceId() {
        String value = System.getenv("AF_AGENT_INSTANCE_ID");
        if (value != null) {
            if (!value.matches("[A-Za-z0-9_-]{1,56}")) {
                throw new IllegalStateException("Agent 容器实例编号格式无效");
            }
            return value;
        }
        // 旧版部署控制器尚未注入实例编号时，Docker 默认 hostname 仍绑定同一容器。
        return containerId();
    }

    private static String containerId() {
        try {
            if (!Files.exists(Path.of("/.dockerenv"))) return null;
            String value = Files.readString(Path.of("/etc/hostname")).trim().toLowerCase(Locale.ROOT);
            // 仓库部署不自定义 hostname；Docker 默认值是容器 ID 的前 12 位。
            // 不符合这个格式的运行环境不提供同容器重启证明。
            return value.matches("[0-9a-f]{12}") ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String currentInstanceField() {
        String instance = instanceId();
        return instance == null ? "local" : instance;
    }

    private static String currentContainerField() {
        String container = containerId();
        return container == null ? "unknown" : container;
    }

    private static String hostMachineId() {
        return readHex32(HOST_MACHINE_ID);
    }

    private static String bootId() {
        return readHex32(HOST_BOOT_ID);
    }

    private static String readHex32(Path path) {
        try {
            String value = Files.readString(path).trim().toLowerCase(Locale.ROOT).replace("-", "");
            return hex32(value) ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hex32(String value) {
        return value != null && value.matches("[0-9a-f]{32}");
    }

    private static String pidNamespace() {
        try {
            String link = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString();
            if (!link.startsWith("pid:[") || !link.endsWith("]")) return null;
            String decimal = link.substring(5, link.length() - 1);
            return Long.toUnsignedString(Long.parseUnsignedLong(decimal), 36);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant currentStart() {
        try {
            return ProcessHandle.current().info().startInstant().orElse(null);
        } catch (SecurityException e) {
            return null;
        }
    }

    private record Owner(String machine, String boot, long pid, long startedMillis,
                         String namespace) { }

    private record ContainerOwner(String instance, String container, String boot) { }
}
