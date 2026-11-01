package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceHealthVerifierTest {

    @TempDir
    Path tempRoot;

    WorkspacePathResolver pathResolver;
    WorkspaceHealthVerifier verifier;

    @BeforeEach
    void setUp() {
        pathResolver = new WorkspacePathResolver(
                tempRoot.resolve("workspace").toString(),
                tempRoot.resolve("datasets").toString()
        );
        verifier = new WorkspaceHealthVerifier(pathResolver);
    }



}
