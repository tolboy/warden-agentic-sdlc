package dev.warden.gate;

import dev.warden.config.TaskSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The candidate running where a person can use it, for the decision they are about to make.
 *
 * Screenshots show what the harness chose to press. An operator asked, on the first all-Orca
 * run, what the page does on a second press, and had no way to find out short of starting the
 * server by hand. This starts it with the task's own `visual_qa.start` — the command the
 * harness already used on the same tree — opens its URL where the operator is, and stops it
 * when the decision page closes. It refuses to start a second server on a port somebody else
 * is answering, for the same reason the harness does.
 */
public final class CandidatePreview implements AutoCloseable {

    /** Shows a URL where the operator is; true when it was shown. */
    @FunctionalInterface
    public interface Opener {
        boolean open(String url) throws Exception;
    }

    private final Path root;
    private final String taskId;
    private final Path log;
    private final Opener opener;
    private Process server;
    private String url;

    public CandidatePreview(Path root, String taskId, Path log, Opener opener) {
        this.root = root;
        this.taskId = taskId;
        this.log = log;
        this.opener = opener;
    }

    /** Starts the preview if it is not running yet, opens it, and says what happened. */
    public synchronized String open() throws Exception {
        if (url == null) {
            TaskSpec.VisualQa visual = contract();
            if (visual == null) {
                return "у задачи нет visual_qa: Warden не знает, как запустить кандидата";
            }
            String address = visual.url() != null && !visual.url().isBlank()
                    ? visual.url() : "http://127.0.0.1:4173/";
            boolean declared = visual.start() != null && !visual.start().isBlank();
            String start = declared ? visual.start() : VisualQaRunner.defaultStart(root);
            if (!VisualQaRunner.httpOk(address)) {
                if (start == null) {
                    return "по адресу " + address + " никто не отвечает, а команда запуска не задана";
                }
                server = VisualQaRunner.startServer(root, start, log);
                if (!VisualQaRunner.waitForHttp(address, Duration.ofSeconds(45))) {
                    close();
                    return "запустил `" + start + "`, но " + address + " так и не ответил; вывод в " + log;
                }
            } else if (declared) {
                // The harness refuses this case; so does the preview. A stranger's app on the
                // port would be tried in place of the candidate.
                return "по адресу " + address + " уже отвечает какой-то сервер, а задача запускает свой: "
                        + "остановите чужой сервер, и кнопка запустит кандидата";
            }
            url = address;
        }
        boolean shown = opener.open(url);
        return shown ? "кандидат открыт во вкладке браузера Orca: " + url
                : "кандидат запущен: откройте " + url;
    }

    private TaskSpec.VisualQa contract() {
        if (taskId == null || !dev.warden.config.RepoPath.isSlug(taskId)) return null;
        Path file = root.resolve(".warden/tasks").resolve(taskId + ".yaml");
        try {
            return TaskSpec.parse(Files.readString(file), file.toString()).visualQa();
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Stops the server this preview started, with everything it spawned. */
    @Override
    public synchronized void close() {
        if (server == null) return;
        server.descendants().forEach(ProcessHandle::destroyForcibly);
        server.destroyForcibly();
        server = null;
        url = null;
    }
}
