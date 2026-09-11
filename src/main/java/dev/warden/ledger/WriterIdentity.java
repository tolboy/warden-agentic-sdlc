package dev.warden.ledger;

import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One writer process, so two JVMs never append the same segment.
 *
 * Host, pid and process start are the coordinates that survive a pid being reused after a
 * crash. The id is a filename, so it is restricted to a conservative character set.
 */
public final class WriterIdentity {

    private final String id;
    private final String host;
    private final long pid;
    private final Instant startedAt;

    private WriterIdentity(String id, String host, long pid, Instant startedAt) {
        this.id = id;
        this.host = host;
        this.pid = pid;
        this.startedAt = startedAt;
    }

    public static WriterIdentity current() {
        String host = "unknown";
        try {
            String name = InetAddress.getLocalHost().getHostName();
            if (name != null && !name.isBlank()) host = name;
        } catch (Exception ignored) {
            // A missing hostname is not a reason to refuse a measurement.
        }
        long pid = ProcessHandle.current().pid();
        Instant started = ProcessHandle.current().info().startInstant().orElse(Instant.EPOCH);
        String id = sanitize(host) + "-" + pid + "-" + started.toEpochMilli();
        return new WriterIdentity(id, host, pid, started);
    }

    public String id() { return id; }
    public String host() { return host; }
    public long pid() { return pid; }
    public Instant startedAt() { return startedAt; }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("writer_id", id);
        value.put("host", host);
        value.put("pid", pid);
        value.put("started_at", startedAt.toString());
        return value;
    }

    static String sanitize(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        String cleaned = out.toString();
        return cleaned.isBlank() ? "host" : cleaned;
    }
}
