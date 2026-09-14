package world.willfrog.beta.infra;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import world.willfrog.beta.core.ControllerException;

@Component
public class CommandRunner {
    public String run(List<String> arguments, Map<String, String> environment, Duration timeout) {
        if (arguments.isEmpty() || arguments.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0))
            throw new ControllerException("COMMAND_INVALID", "External command arguments are invalid");
        try {
            ProcessBuilder builder = new ProcessBuilder(arguments);
            builder.redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            AtomicReference<IOException> readFailure = new AtomicReference<>();
            Thread reader = new Thread(() -> drain(process, captured, readFailure), "beta-controller-command-output");
            reader.setDaemon(true);
            reader.start();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                reader.join(5_000);
                throw new ControllerException("COMMAND_TIMEOUT", "External command exceeded its deadline");
            }
            reader.join(5_000);
            if (reader.isAlive()) throw new ControllerException("COMMAND_OUTPUT_TIMEOUT", "External command output did not close");
            if (readFailure.get() != null)
                throw new ControllerException("COMMAND_OUTPUT_FAILED", "Unable to read external command output", readFailure.get());
            String output = captured.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0)
                throw new ControllerException("COMMAND_FAILED", "External command failed with exit code " + process.exitValue());
            return output;
        } catch (IOException exception) {
            throw new ControllerException("COMMAND_START_FAILED", "Unable to start external command", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ControllerException("COMMAND_INTERRUPTED", "External command was interrupted", exception);
        }
    }

    private void drain(Process process, ByteArrayOutputStream captured, AtomicReference<IOException> failure) {
        byte[] buffer = new byte[8192];
        int retained = 0;
        try (var input = process.getInputStream()) {
            for (int count; (count = input.read(buffer)) >= 0; ) {
                int writable = Math.min(count, Math.max(0, 4 * 1024 * 1024 - retained));
                if (writable > 0) {
                    captured.write(buffer, 0, writable);
                    retained += writable;
                }
            }
        } catch (IOException exception) {
            failure.set(exception);
        }
    }
}
