package dev.warden.execution;

import dev.warden.json.Json;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Incremental, optional reader of the direct adapter's existing raw spool. It never touches
 * the child pipes, process receipt, authoritative protocol parser or vendor lifecycle.
 * A slow/broken UI can only lose projection events. No provider text, arguments, paths,
 * stderr text, auth output or reasoning is copied to the UI.
 */
public final class LiveCliOutput implements AutoCloseable {
    public static final int MAX_RECORD_BYTES = 64 * 1024;
    private static final int MAX_READ_BYTES = 256 * 1024;
    private final Consumer<ExecutionActivity.Update> sink;
    private final Cursor stdout;
    private final Path stderr;
    private volatile boolean closed;
    private final Thread thread;

    public static LiveCliOutput follow(Path stdout, Path stderr, Consumer<ExecutionActivity.Update> sink) {
        return new LiveCliOutput(stdout, stderr, sink);
    }

    private LiveCliOutput(Path stdout, Path stderr, Consumer<ExecutionActivity.Update> sink) {
        this.sink = sink;
        this.stdout = new Cursor(stdout);
        this.stderr = stderr;
        Thread started = null;
        if (sink != null) {
            try { started = Thread.ofVirtual().name("warden-cli-projection").start(this::follow); }
            catch (RuntimeException unavailable) { /* No observer is better than refusing the writer. */ }
        }
        thread = started;
    }

    private void follow() {
        long previousOut = 0, previousErr = 0;
        try {
            while (!closed) {
                long out = size(stdout.path), err = size(stderr);
                if (out != previousOut || err != previousErr) {
                    emit(new ExecutionActivity.Update(ExecutionActivity.Kind.OUTPUT, null, null, out, err));
                    stdout.read(out, err);
                    previousOut = out;
                    previousErr = err;
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException finished) {
            // Closing the projection never interrupts the writer.
        } catch (Exception | LinkageError failure) {
            emit(ExecutionActivity.Update.of(ExecutionActivity.Kind.OBSERVER_UNAVAILABLE));
        }
    }

    private static long size(Path file) throws java.io.IOException {
        return Files.exists(file) ? Files.size(file) : 0;
    }

    private void emit(ExecutionActivity.Update update) {
        if (closed) return;
        try { sink.accept(update); }
        catch (RuntimeException | Error failure) { closed = true; }
    }

    @Override public void close() {
        closed = true;
        if (thread == null) return;
        thread.interrupt();
        // No join: a callback blocked in a UI transport must not hold a paid result back.
    }

    private final class Cursor {
        final Path path;
        long offset;
        final ByteArrayOutputStream record = new ByteArrayOutputStream();
        boolean discard;
        Cursor(Path path) { this.path = path; }

        void read(long length, long err) throws java.io.IOException {
            if (length <= offset) return;
            if (length - offset > MAX_READ_BYTES) {
                // Explicitly lossy: jump to a bounded tail, discarding the partial record.
                offset = length - MAX_READ_BYTES;
                record.reset();
                discard = true;
            }
            try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
                file.position(offset);
                ByteBuffer chunk = ByteBuffer.allocate(8192);
                int remaining = MAX_READ_BYTES;
                while (remaining > 0 && !closed) {
                    chunk.clear();
                    chunk.limit(Math.min(chunk.capacity(), remaining));
                    int count = file.read(chunk);
                    if (count <= 0) return;
                    offset += count;
                    remaining -= count;
                    for (int index = 0; index < count; index++) {
                        byte value = chunk.array()[index];
                        if (value == '\n') {
                            if (!discard) parse(record.toString(StandardCharsets.UTF_8), length, err);
                            record.reset();
                            discard = false;
                        } else if (!discard) {
                            if (record.size() < MAX_RECORD_BYTES) record.write(value);
                            else { record.reset(); discard = true; }
                        }
                    }
                }
            }
        }
    }

    private void parse(String line, long out, long err) {
        if (!line.startsWith("{") || !shallow(line)) return;
        try {
            Map<String, Object> event = Json.parseObject(line);
            String type = string(event.get("type"));
            if ("error".equals(type) || "turn.failed".equals(type)) {
                emit(new ExecutionActivity.Update(ExecutionActivity.Kind.PROVIDER_ERROR, null, null, out, err));
            } else if ("item.started".equals(type) || "item.completed".equals(type)) {
                if (!(event.get("item") instanceof Map<?, ?> item)) return;
                if ("agent_message".equals(item.get("type")) && "item.completed".equals(type)) {
                    emit(new ExecutionActivity.Update(ExecutionActivity.Kind.MESSAGE, null, null, out, err));
                    return;
                }
                String tool = tool(string(item.get("type")));
                if (tool == null) return;
                Integer exit = item.get("exit_code") instanceof Number n ? n.intValue() : null;
                emit(new ExecutionActivity.Update("item.started".equals(type)
                        ? ExecutionActivity.Kind.TOOL_STARTED : ExecutionActivity.Kind.TOOL_FINISHED, tool, exit, out, err));
            } else if ("assistant".equals(type) && event.get("message") instanceof Map<?, ?> message
                    && message.get("content") instanceof java.util.List<?> content) {
                for (Object part : content) {
                    if (!(part instanceof Map<?, ?> item)) continue;
                    if ("tool_use".equals(item.get("type"))) {
                        emit(new ExecutionActivity.Update(ExecutionActivity.Kind.TOOL_STARTED,
                                toolOrOther(string(item.get("name"))), null, out, err));
                    } else if ("text".equals(item.get("type"))) {
                        emit(new ExecutionActivity.Update(ExecutionActivity.Kind.MESSAGE, null, null, out, err));
                    }
                }
            } else if ("tool_use".equals(type) || "tool_result".equals(type)) {
                emit(new ExecutionActivity.Update("tool_use".equals(type) ? ExecutionActivity.Kind.TOOL_STARTED
                        : ExecutionActivity.Kind.TOOL_FINISHED,
                        toolOrOther(string(event.get("tool_name"))), null, out, err));
            }
        } catch (Json.JsonException ignored) { /* Unknown/malformed output remains byte progress. */ }
    }

    private static String string(Object value) { return value instanceof String text ? text : ""; }
    private static String toolOrOther(String name) { String known = tool(name); return known == null ? "other" : known; }
    private static String tool(String name) {
        return switch (name) {
            case "command_execution", "Bash", "shell", "run_shell_command", "exec_command" -> "shell";
            case "file_change", "Edit", "Write", "write_file", "apply_patch" -> "file";
            case "Read", "read_file", "read_many_files" -> "read";
            case "Grep", "Glob", "grep_search", "list_directory" -> "search";
            case "web_search", "WebSearch", "WebFetch", "google_web_search" -> "web";
            case "mcp_tool_call" -> "mcp";
            default -> null;
        };
    }

    /** Bound parser recursion before calling the shared JSON parser on untrusted output. */
    private static boolean shallow(String text) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 24) return false; }
            else if (c == '}' || c == ']') depth--;
        }
        return true;
    }
}
