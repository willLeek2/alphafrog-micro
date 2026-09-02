package world.willfrog.beta.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.ControllerException;

@Component
public class AtomicJsonStore {
    private static final String STATE_FILE = "controller-state.json";
    private final ObjectMapper mapper;
    private final Path root;
    private final Clock clock;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    @Autowired
    public AtomicJsonStore(ObjectMapper mapper, BetaControllerProperties properties) {
        this(mapper, properties.getStateRoot(), Clock.systemUTC());
    }

    AtomicJsonStore(ObjectMapper mapper, Path root, Clock clock) {
        this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
    }

    public <T> T read(ReadAction<T> action) {
        lock.readLock().lock();
        try { return action.apply(readStateUnlocked()); }
        finally { lock.readLock().unlock(); }
    }

    public <T> T update(UpdateAction<T> action) {
        lock.writeLock().lock();
        try {
            ObjectNode state = readStateUnlocked();
            T result = action.apply(state);
            state.put("stateVersion", Math.addExact(state.path("stateVersion").asLong(), 1));
            state.put("updatedAt", Instant.now(clock).toString());
            writeJson(root.resolve(STATE_FILE), state);
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void writeManifest(String deploymentId, JsonNode manifest) {
        lock.writeLock().lock();
        try { writeJson(manifestPath(deploymentId), manifest); }
        finally { lock.writeLock().unlock(); }
    }

    public JsonNode readManifest(String deploymentId) {
        lock.readLock().lock();
        try { return readJson(manifestPath(deploymentId)); }
        finally { lock.readLock().unlock(); }
    }

    public boolean hasManifest(String deploymentId) {
        lock.readLock().lock();
        try { return Files.isRegularFile(manifestPath(deploymentId), LinkOption.NOFOLLOW_LINKS); }
        finally { lock.readLock().unlock(); }
    }

    public Set<String> manifestDeploymentIds() {
        lock.readLock().lock();
        try {
            Path deployments = root.resolve("deployments");
            rejectExistingSymlink(root);
            rejectExistingSymlink(deployments);
            if (!Files.exists(deployments, LinkOption.NOFOLLOW_LINKS)) return Set.of();
            Set<String> result = new LinkedHashSet<>();
            try (var children = Files.list(deployments)) {
                for (Path child : children.sorted().toList()) {
                    rejectSymbolicLink(child);
                    String deploymentId = child.getFileName().toString();
                    Path manifest = manifestPath(deploymentId);
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                        throw new ControllerException("STATE_INVALID", "Deployment state directory contains an unexpected entry");
                    if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                        try (var entries = Files.list(child)) {
                            if (entries.findAny().isEmpty()) continue;
                        }
                        throw new ControllerException("STATE_INVALID", "Deployment directory is missing its manifest");
                    }
                    if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
                        throw new ControllerException("STATE_INVALID", "Deployment manifest is not a regular file");
                    result.add(deploymentId);
                }
            }
            return Set.copyOf(result);
        } catch (IOException exception) {
            throw new ControllerException("STATE_READ_FAILED", "Unable to list deployment manifests", exception);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void deleteManifest(String deploymentId) {
        lock.writeLock().lock();
        try {
            Path manifest = manifestPath(deploymentId);
            Files.deleteIfExists(manifest);
            forceDirectory(manifest.getParent());
            try { Files.delete(manifest.getParent()); } catch (java.nio.file.DirectoryNotEmptyException ignored) { }
            forceDirectory(root.resolve("deployments"));
        } catch (IOException exception) {
            throw new ControllerException("STATE_WRITE_FAILED", "Unable to remove deployment manifest", exception);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ObjectNode snapshot() { return read(state -> state.deepCopy()); }

    private ObjectNode readStateUnlocked() {
        Path state = root.resolve(STATE_FILE);
        rejectExistingSymlink(root);
        if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode initial = mapper.createObjectNode();
            initial.put("schemaVersion", 1);
            initial.put("stateVersion", 0);
            initial.put("updatedAt", Instant.now(clock).toString());
            initial.set("deployments", mapper.createArrayNode());
            return initial;
        }
        JsonNode value = readJson(state);
        if (!value.isObject()) throw new ControllerException("STATE_INVALID", "Controller state must be an object");
        return (ObjectNode) value;
    }

    private JsonNode readJson(Path path) {
        rejectSymbolicLink(path);
        try { return mapper.readTree(Files.readAllBytes(path)); }
        catch (IOException exception) {
            throw new ControllerException("STATE_READ_FAILED", "Unable to read " + path.getFileName(), exception);
        }
    }

    private void writeJson(Path target, JsonNode value) {
        Path temporary = null;
        try {
            rejectPathSymlinks(target.getParent());
            Files.createDirectories(target.getParent());
            rejectPathSymlinks(target.getParent());
            rejectSymbolicLink(target);
            byte[] content = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(content));
                channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new ControllerException("ATOMIC_MOVE_UNSUPPORTED", "State filesystem does not support atomic replacement", exception);
            }
            forceDirectory(target.getParent());
        } catch (IOException exception) {
            throw new ControllerException("STATE_WRITE_FAILED", "Unable to atomically write " + target.getFileName(), exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { }
            }
        }
    }

    private Path manifestPath(String deploymentId) {
        if (deploymentId == null || !deploymentId.matches("(?!stable$)[a-z0-9][a-z0-9-]{1,62}[a-z0-9]")) {
            throw new ControllerException("DEPLOYMENT_ID_INVALID", "Deployment identifier is invalid");
        }
        Path resolved = root.resolve("deployments").resolve(deploymentId).resolve("manifest.json").normalize();
        if (!resolved.startsWith(root.resolve("deployments"))) {
            throw new ControllerException("DEPLOYMENT_PATH_INVALID", "Deployment path escaped the state root");
        }
        rejectExistingSymlink(root);
        rejectExistingSymlink(root.resolve("deployments"));
        rejectExistingSymlink(resolved.getParent());
        return resolved;
    }

    private static void rejectExistingSymlink(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) rejectSymbolicLink(path);
    }

    private void rejectPathSymlinks(Path directory) {
        Path current = root;
        rejectExistingSymlink(current);
        if (directory.equals(root)) return;
        Path relative = root.relativize(directory.toAbsolutePath().normalize());
        for (Path component : relative) {
            current = current.resolve(component);
            rejectExistingSymlink(current);
        }
    }

    private static void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new ControllerException("SYMLINK_REJECTED", "Runtime state path must not be a symbolic link");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    @FunctionalInterface public interface ReadAction<T> { T apply(ObjectNode state); }
    @FunctionalInterface public interface UpdateAction<T> { T apply(ObjectNode state); }
}
