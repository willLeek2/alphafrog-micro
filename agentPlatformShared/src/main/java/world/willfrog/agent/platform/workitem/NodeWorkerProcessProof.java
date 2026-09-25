package world.willfrog.agent.platform.workitem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/** 用宿主内核与进程身份补回崩溃时丢失的节点线程退出回执。 */
public final class NodeWorkerProcessProof {
    private static final String PREFIX = "dual-pool-node:v3@";
    private static final Path HOST_MACHINE_ID = Path.of("/run/alphafrog/host-machine-id");
    private static final Path HOST_BOOT_ID = Path.of("/proc/sys/kernel/random/boot_id");

    private NodeWorkerProcessProof() { }

    /**
     * 记录宿主 machine-id、启动代际、宿主 PID、进程启动时间及 PID 命名空间。
     * 容器自身的 hostname/PID=1 无法跨 Docker 重建证明旧 JVM 已经退出。
     */
    public static String claimant(String processIdentity) {
        if (processIdentity == null || processIdentity.isBlank()) {
            throw new IllegalArgumentException("节点领取缺少进程身份");
        }
        String[] identity = processIdentity.split("@", -1);
        if (identity.length != 4 || identity[1].isBlank()
                || identity[2].isBlank() || identity[3].isBlank()) {
            throw new IllegalArgumentException("节点领取进程身份格式无效");
        }
        long pid;
        try {
            pid = Long.parseLong(identity[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("节点领取进程号无效", e);
        }
        if (pid <= 0 || pid != ProcessHandle.current().pid()) {
            throw new IllegalArgumentException("节点领取进程号与当前 JVM 不一致");
        }
        String machine = hostMachineId();
        String boot = bootId();
        String namespace = pidNamespace();
        Instant started = currentStart();
        if (machine == null || boot == null || namespace == null || started == null) {
            throw new IllegalStateException("节点领取缺少宿主机器、内核启动或进程身份证明");
        }
        String claimant = PREFIX + machine + "@" + boot + "@" + pid + "@"
                + started.toEpochMilli() + "@" + namespace;
        if (claimant.length() > 128) {
            throw new IllegalStateException("节点领取进程证明超过数据库列上限");
        }
        return claimant;
    }

    public static boolean definitelyExited(String claimant) {
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

    public static boolean isCurrentProcess(String claimant) {
        Owner owner = parse(claimant);
        if (owner == null || !owner.machine().equals(hostMachineId())
                || !owner.boot().equals(bootId())
                || !owner.namespace().equals(pidNamespace())
                || owner.pid() != ProcessHandle.current().pid()) return false;
        Instant currentStart = currentStart();
        return currentStart != null && currentStart.toEpochMilli() == owner.startedMillis();
    }

    /**
     * 只核验 v3 证明来自同一宿主，不据此推断旧进程已退出。
     * 同一领取代际的 ACTIVE_NODE 已归还时，调用方可另据该持久事实确认线程退出。
     */
    public static boolean recognizesClaimant(String claimant) {
        return recognizesByObservation(claimant, hostMachineId());
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
        if (claimant == null || !claimant.startsWith(PREFIX)) return null;
        String[] fields = claimant.substring(PREFIX.length()).split("@", -1);
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
}
