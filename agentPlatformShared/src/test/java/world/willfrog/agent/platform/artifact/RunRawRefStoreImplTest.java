package world.willfrog.agent.platform.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RunRawRefStoreImpl against the local-disk backend (260814 scheduler-03).
 * No Redis is involved: the previous Redis-counter / hash-mapping tests were
 * replaced by equivalent behavioral tests over a @TempDir root.
 */
class RunRawRefStoreImplTest {

    @TempDir
    Path tempDir;

    private RunRawRefLocalStore localStore;
    private RunRawRefStoreImpl store;

    @BeforeEach
    void setUp() {
        localStore = new RunRawRefLocalStore(tempDir.resolve("raw-ref").toString(),
                8_388_608L, 512, 536_870_912L);
        store = new RunRawRefStoreImpl(localStore);
    }












}
