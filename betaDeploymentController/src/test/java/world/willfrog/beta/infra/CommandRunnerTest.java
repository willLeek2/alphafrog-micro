package world.willfrog.beta.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandRunnerTest {
    @Test
    void drainsCommandOutputWhileTheProcessIsStillRunning() {
        String output = new CommandRunner().run(
                List.of("/bin/sh", "-c", "yes x | head -c 200000"), Map.of(), Duration.ofSeconds(5));
        assertEquals(200_000, output.length());
    }
}
