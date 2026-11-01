package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspacePathResolverTest {

    @TempDir
    Path tempRoot;

    WorkspacePathResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WorkspacePathResolver(
                tempRoot.resolve("workspace").toString(),
                tempRoot.resolve("datasets").toString()
        );
    }













}
