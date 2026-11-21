package com.yinyangeq.qudelixprobe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

final class ProbeLog {
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private static final Object FILE_LOCK = new Object();
    private final File current;
    private final File previous;
    private boolean incomplete;

    ProbeLog(File directory) {
        current = new File(directory, "probe-debug.log");
        previous = new File(directory, "probe-debug.previous.log");
    }

    void record(String message) {
        String limited = message.substring(0, Math.min(message.length(), 4096)).replace('\n', ' ');
        append(Instant.now() + " " + limited + "\n");
    }

    void timing(String operation, long elapsedMs, String outcome) {
        record("Action timing · " + operation + " · " + elapsedMs + " ms · " + outcome);
    }

    void recordCompatibilityReport(String report) {
        append(compatibilitySection(report));
    }

    private static String compatibilitySection(String report) {
        return "\n===== Firmware compatibility report =====\n\n"
                + report.trim()
                + "\n\n===== End firmware compatibility report =====\n\n";
    }

    private void append(String entry) {
        byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
        synchronized (FILE_LOCK) {
            try {
                if (current.length() + bytes.length > MAX_FILE_BYTES && current.exists()) {
                    Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                try (FileOutputStream output = new FileOutputStream(current, true)) {
                    output.write(bytes);
                }
            } catch (IOException error) {
                incomplete = true;
            }
        }
    }

    String snapshot(String lastCompatibilityReport) throws IOException {
        synchronized (FILE_LOCK) {
            String archive = previous.exists() ? new String(Files.readAllBytes(previous.toPath()), StandardCharsets.UTF_8) : "";
            String recent = current.exists() ? new String(Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8) : "";
            String log = archive + recent + (incomplete ? "Some diagnostic lines could not be saved.\n" : "");
            if (lastCompatibilityReport == null) return log;
            String report = compatibilitySection(lastCompatibilityReport);
            // Include reports from earlier app versions or from a test whose log has rotated out.
            return log.contains(report) ? log : log + report;
        }
    }
}
