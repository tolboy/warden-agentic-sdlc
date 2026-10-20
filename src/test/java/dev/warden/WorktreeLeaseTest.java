package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.UserConfig;
import dev.warden.execution.WorktreeLease;
import dev.warden.execution.orca.OrcaLifecycle;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleRunner;
import dev.warden.run.DoCommand;
import dev.warden.run.TaskLoop;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real local processes exercise admission; no vendor, network, or Orca runtime is used. */
public final class WorktreeLeaseTest implements Suite {
    private final List<Child> children = new ArrayList<>();
    private final List<ProcessHandle> orphans = new ArrayList<>();
    private Path sandbox;
    private int nextChild;

    @Override public String name() { return "worktree lease"; }

    @Override public void run(Check check) throws Exception {
        sandbox = Files.createTempDirectory("warden-worktree-lease-");
        try {
            twoProcesses(check);
            sameJvm(check);
            linkedWorktrees(check);
            workflowAndRole(check);
            retainedOrcaWorker(check);
            conductorStyleHandoff(check);
            trackedProcess(check);
            unknownProcessFence(check);
            crashedController(check);
            daemonAndVendorChildren(check);
            shutdownController(check);
        } finally {
            for (Child child : children) stop(child.process());
            for (ProcessHandle orphan : orphans) stop(orphan);
            try (var paths = Files.walk(sandbox)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void twoProcesses(Check check) throws Exception {
        Path root = emptyCheckout("two-jvms");
        Path control = controls("two-jvms");
        Child owner = start(root, "hold", root.toString(), control.toString());
        await(control.resolve("entered"), owner);
        check.contains("another JVM is refused even with a different run id",
                finish(start(root, "try", root.toString())), "worktree_busy");
        Path subproject = Files.createDirectories(root.resolve("nested/project"));
        check.contains("a subdirectory project shares its checkout's lock",
                finish(start(root, "try", subproject.toString())), "worktree_busy");
        check.that("refusal does not reserve the contender's evidence",
                !Files.exists(root.resolve(".warden/runs")));
        write(control.resolve("release"), "release");
        check.contains("the owner releases normally", finish(owner), "released");
        check.contains("a later JVM can acquire after normal release",
                finish(start(root, "try", root.toString())), "acquired");
        check.that("the stable OS lock file is retained after release",
                Files.isRegularFile(root.resolve(".git/warden-execution.lock")));
    }

    private void sameJvm(Check check) throws Exception {
        Path root = emptyCheckout("threads");
        try (var outer = WorktreeLease.acquire(root, "outer")) {
            try (var inner = WorktreeLease.acquire(root.resolve(".").resolve("..")
                    .resolve(root.getFileName()), "inner")) {
                check.that("same-thread nesting resolves a checkout path alias", WorktreeLease.isHeld());
            }
            check.that("closing an inner scope retains the outer lease", WorktreeLease.isHeld());
            try (var executor = Executors.newSingleThreadExecutor()) {
                String code = executor.submit(() -> admission(root, "outer")).get(5, TimeUnit.SECONDS);
                check.eq("another thread cannot inherit ownership even with the same run id",
                        "worktree_busy", code);
            }
        }
        check.that("normal scope close clears thread ownership", !WorktreeLease.isHeld());
        check.eq("a later scope in the same JVM can acquire", "acquired", admission(root, "later"));
        try {
            try (var ignored = WorktreeLease.acquire(root, "exception")) {
                throw new IOException("synthetic controller failure");
            }
        } catch (IOException expected) {
            check.contains("the test exercised exceptional scope close", expected.getMessage(), "synthetic");
        }
        check.eq("an exception with no live children releases safely", "acquired", admission(root, "after"));
    }

    private void linkedWorktrees(Check check) throws Exception {
        Path root = project("linked-main");
        Path linked = sandbox.resolve("linked-other");
        command(root, "git", "worktree", "add", "-q", "--detach", linked.toString(), "HEAD");
        try (var ignored = WorktreeLease.acquire(root, "main-owner")) {
            check.contains("a linked worktree in the same repository can execute independently",
                    finish(start(linked, "try", linked.toString())), "acquired");
            check.contains("the main checkout remains fenced while the linked checkout runs",
                    finish(start(root, "try", root.toString())), "worktree_busy");
        }
        check.that("linked checkout really uses its own Git administration pointer",
                Files.isRegularFile(linked.resolve(".git")));
    }

    private void workflowAndRole(Check check) throws Exception {
        Path root = project("real-workflow");
        Path home = sandbox.resolve("workflow-home");
        Path control = controls("workflow-writer");
        Child workflow = start(root, "workflow", root.toString(), home.toString(),
                control.toString(), "workflow-owner");
        await(control.resolve("entered"), workflow);
        Child role = start(root, "role", root.toString(), home.toString(),
                control.toString(), "standalone-contender");
        check.contains("a real workflow's direct writer excludes a standalone role",
                finish(role), "worktree_busy");
        check.that("the refused standalone role writes no evidence directory",
                !Files.exists(root.resolve(".warden/runs/standalone-contender")));
        check.contains("a second workflow with another run id is also refused",
                finish(start(root, "workflow", root.toString(), home.toString(),
                        control.toString(), "workflow-contender")), "worktree_busy");
        check.that("the refused workflow reserves no run id",
                !Files.exists(root.resolve(".warden/runs/workflow-contender/run.json")));
        check.contains("a dry-run do cannot draft a contract beside an active writer",
                finish(start(root, "dry-do", root.toString(), home.toString(),
                        control.toString(), "dry-do-contender")), "worktree_busy");
        check.that("the refused dry-run leaves pinned task configuration untouched",
                !Files.exists(root.resolve(".warden/tasks/dry-do-contender.yaml")));
        write(control.resolve("release"), "release");
        check.contains("nested TaskLoop and RoleRunner leases complete without deadlock",
                finish(workflow), "ready_for_human");

        Path second = controls("standalone-writer");
        Child standalone = start(root, "role", root.toString(), home.toString(),
                second.toString(), "standalone-owner");
        await(second.resolve("entered"), standalone);
        check.contains("a standalone direct writer excludes a real workflow",
                finish(start(root, "workflow", root.toString(), home.toString(),
                        second.toString(), "workflow-after-role")), "worktree_busy");
        check.that("that workflow is refused before reservation too",
                !Files.exists(root.resolve(".warden/runs/workflow-after-role/run.json")));
        write(second.resolve("release"), "release");
        check.contains("the standalone role completes and releases", finish(standalone), "role_ok");
        check.eq("normal direct writer completion permits another controller", "acquired",
                admission(root, "after-workflow-and-role"));
    }

    private void trackedProcess(Check check) throws Exception {
        Path root = emptyCheckout("tracked-live");
        Path control = controls("tracked-live");
        Child child;
        try (var ignored = WorktreeLease.acquire(root, "owner-with-live-child")) {
            child = start(root, "idle", control.toString());
            WorktreeLease.track(child.process().toHandle());
            await(control.resolve("entered"), child);
            Map<String, Object> active = Json.parseObject(Files.readString(root.resolve(".git/warden-execution.json")));
            check.contains("vendor PID is durable before owner close", String.valueOf(active.get("processes")),
                    String.valueOf(child.process().pid()));
        }
        check.rejects("recovery refuses a known live vendor", "still alive",
                () -> WorktreeLease.recover(root, true));
        check.eq("a live tracked child keeps a recovery fence after orderly owner close",
                "worktree_recovery_required", admission(root, "contender"));
        Map<String, Object> state = Json.parseObject(Files.readString(root.resolve(".git/warden-execution.json")));
        check.eq("the persistent marker names recovery rather than safe release",
                "recovery_required", state.get("state"));
        check.contains("the recovery marker identifies the exact live child",
                String.valueOf(state.get("active_processes")), String.valueOf(child.process().pid()));
        stop(child.process());
        check.eq("a dead child alone never silently clears an unaccounted fence",
                "worktree_recovery_required", admission(root, "still-fenced"));
        check.eq("recovery proves all recorded vendor identities stopped", true,
                WorktreeLease.recover(root, false).get("ok"));
        check.eq("recovery admits a new controller", "acquired", admission(root, "recovered"));
    }

    private void retainedOrcaWorker(Check check) throws Exception {
        Path root = project("retained-orca");
        Path control = controls("retained-orca");
        // Unexpected admission must fail an assertion promptly, not wait out a barrier.
        write(control.resolve("release"), "release");
        UserConfig user = user(sandbox.resolve("retained-orca-home"), control);
        ConfigLoader.Loaded loaded = new ConfigLoader().load(root, "hello");
        OrcaLifecycle lifecycle = new OrcaLifecycle(root, "prior-orca");
        OrcaLifecycle.Worker worker = new OrcaLifecycle.Worker("implementer#prior-orca",
                "implementer", "orca-task", "orca-dispatch", "coordinator", "path:" + root,
                "worktree-id", "synthetic", null, null, OrcaLifecycle.State.ACTIVE,
                "human", "unanswered-question", Instant.now(), Instant.now());
        lifecycle.mutate(snapshot -> snapshot.withWorker(worker));

        check.eq("a retained Orca worker blocks a standalone direct role",
                "worktree_recovery_required", roleCode(loaded, user, "direct-beside-orca"));
        check.that("that standalone refusal never starts a vendor process",
                !Files.exists(control.resolve("entered")));
        check.eq("a retained Orca worker refuses a direct workflow at admission",
                "worktree_recovery_required", workflowCode(loaded, user, "workflow-beside-orca"));
        check.that("retained-worker refusal precedes workflow reservation and all gates",
                !Files.exists(root.resolve(".warden/runs/workflow-beside-orca")));
        check.that("that workflow refusal also starts no vendor process",
                !Files.exists(control.resolve("entered")));
        DoCommand.Options preview = new DoCommand.Options(root, "Draft another contract", "app", "low",
                "preview-beside-orca", "preview-beside-orca", "HEAD", true, true);
        check.eq("a retained Orca worker refuses even do's dry-run drafting phase",
                "worktree_recovery_required", new DoCommand(new ProcessRunner()).run(preview, user).code());
        check.that("Orca admission refusal leaves task configuration untouched",
                !Files.exists(root.resolve(".warden/tasks/preview-beside-orca.yaml")));

        Profile local = user.profiles().get("lease-writer");
        Profile orca = new Profile(local.name(), local.role(), local.vendor(), null, null,
                "synthetic", List.of(), false, 1, null, local.promptTemplate(), null, false,
                local.requiredArtifactFields(), List.of(), "argv", null, null, "orca", null, null, null, List.of(), true);
        UserConfig orcaUser = new UserConfig(user.home(), user.policy(), Map.of(orca.name(), orca), Map.of(), true);
        int[] attaches = {0};
        new TaskLoop(new ProcessRunner()).withOrcaGate(false).withAvailability(profile -> true)
                .withExecutors((profile, processes, git) -> request -> {
            attaches[0]++;
            return new dev.warden.execution.RoleExecutor.Result(false, "role_orca_lifecycle_unaccounted",
                    Duration.ZERO, "", null, Map.of("runner", "orca"));
        }).run(loaded, orcaUser, "orca-admission", false);
        check.eq("a workflow with an Orca route reaches its reconciliation adapter", 1, attaches[0]);

        lifecycle.mutate(snapshot -> snapshot.withWorker(worker.at(OrcaLifecycle.State.STOPPED,
                null, Instant.now())));
        check.eq("a proven stopped Orca worker permits a standalone direct role",
                "role_ok", roleCode(loaded, user, "direct-after-stop"));
        check.that("the admitted direct role really executes its local CLI",
                Files.exists(control.resolve("entered")));
        lifecycle.mutate(snapshot -> snapshot.withWorker(worker.at(OrcaLifecycle.State.SETTLED,
                null, Instant.now())));
        TaskLoop.Outcome settled = new TaskLoop(new ProcessRunner()).withOrcaGate(false)
                .run(loaded, user, "workflow-after-settlement", false);
        check.eq("a settled Orca worker permits a direct workflow", "ready_for_human", settled.reason());
        check.eq("the admitted workflow accounts for its one real local CLI call",
                1L, settled.summaryReport().get("role_runs"));

        Files.delete(control.resolve("entered"));
        write(lifecycle.file(), "{ unreadable lifecycle JSON");
        check.eq("malformed Orca lifecycle evidence fails closed for a standalone role",
                "worktree_recovery_required", roleCode(loaded, user, "direct-unreadable-orca"));
        check.eq("malformed lifecycle evidence refuses the workflow before execution",
                "worktree_recovery_required", workflowCode(loaded, user, "workflow-unreadable-orca"));
        check.that("unreadable evidence refuses before a workflow directory is created",
                !Files.exists(root.resolve(".warden/runs/workflow-unreadable-orca")));
        check.that("no CLI starts while Orca lifecycle evidence is unreadable",
                !Files.exists(control.resolve("entered")));

        Files.delete(lifecycle.file());
        Files.createDirectory(lifecycle.file());
        check.eq("a nonregular lifecycle path cannot be mistaken for an absent record",
                "worktree_recovery_required", roleCode(loaded, user, "direct-nonregular-orca"));
        check.eq("a lifecycle directory refuses a new workflow at admission",
                "worktree_recovery_required", workflowCode(loaded, user, "workflow-nonregular-orca"));
        check.that("nonregular lifecycle evidence creates no workflow reservation",
                !Files.exists(root.resolve(".warden/runs/workflow-nonregular-orca")));
        check.that("no CLI starts beside a nonregular Orca lifecycle record",
                !Files.exists(control.resolve("entered")));
    }

    private void unknownProcessFence(Check check) throws Exception {
        Path root = emptyCheckout("unknown-process-receipt");
        try (var execution = WorktreeLease.acquire(root, "owner-with-missing-receipt")) {
            try (var nested = WorktreeLease.acquire(root, "nested-stage")) {
                WorktreeLease.requireRecovery("synthetic process output did not settle");
            }
            check.that("marking recovery preserves the outer scope's ownership", WorktreeLease.isHeld());
        }
        Map<String, Object> state = Json.parseObject(Files.readString(root.resolve(".git/warden-execution.json")));
        check.eq("missing process receipts require recovery even with no known live handle",
                "recovery_required", state.get("state"));
        check.eq("the durable recovery fence retains its diagnostic reason",
                "synthetic process output did not settle", state.get("recovery_reason"));
        check.that("this test requires no sampled active process to retain the fence",
                !state.containsKey("active_processes"));
        check.contains("a new JVM cannot bypass an uncertain-process fence",
                finish(start(root, "try", root.toString())), "worktree_recovery_required");
        check.rejects("unknown process recovery requires operator inspection", "--confirm-stopped",
                () -> WorktreeLease.recover(root, false));
        check.eq("explicit inspection acknowledgment can recover missing receipts", true,
                WorktreeLease.recover(root, true).get("ok"));
    }

    private void conductorStyleHandoff(Check check) throws Exception {
        Path root = emptyCheckout("conductor-handoff");
        try (var execution = WorktreeLease.acquire(root, "prepared-run")) {
            // Same ownership pattern as DoCommand -> Preparation -> RoleRunner.
            try (var preparation = WorktreeLease.acquire(root, "prepared-run")) {
                try (var role = WorktreeLease.acquire(root, "planner-attempt")) {
                    check.that("nested preparation owns execution before handoff", WorktreeLease.isHeld());
                }
            }
            check.contains("completed preparation still leaves its outer controller fenced",
                    finish(start(root, "try", root.toString())), "worktree_busy");
            // DoCommand does this immediately before Conductor starts the inner JVM.
            execution.close();
            check.that("explicit handoff clears the outer thread's ownership", !WorktreeLease.isHeld());
            check.contains("the inner JVM can acquire after nested preparation handoff",
                    finish(start(root, "try", root.toString())), "acquired");
        }
        check.eq("implicit close after explicit handoff is harmless", "acquired",
                admission(root, "after-conductor-handoff"));
    }

    private static String roleCode(ConfigLoader.Loaded loaded, UserConfig user, String runId) throws Exception {
        try {
            RoleRunner.Outcome outcome = new RoleRunner(new ProcessRunner()).run(loaded, user,
                    "implementer", runId, null, null, false);
            return outcome.ok() ? "role_ok" : outcome.code();
        } catch (WorktreeLease.Refused refused) {
            return refused.code();
        }
    }

    private static String workflowCode(ConfigLoader.Loaded loaded, UserConfig user, String runId) throws Exception {
        try {
            return new TaskLoop(new ProcessRunner()).withOrcaGate(false).run(loaded, user, runId, false).reason();
        } catch (WorktreeLease.Refused refused) {
            return refused.code();
        }
    }

    private void crashedController(Check check) throws Exception {
        Path root = emptyCheckout("crashed-owner");
        Path control = controls("crashed-owner");
        Child controller = start(root, "crash-owner", root.toString(), control.toString());
        await(control.resolve("entered"), controller);
        long pid = Long.parseLong(Files.readString(control.resolve("entered")).strip());
        ProcessHandle child = ProcessHandle.of(pid).orElseThrow();
        orphans.add(child);
        stop(controller.process());
        check.that("the crash scenario leaves the exact local child alive", child.isAlive());
        check.contains("a new JVM refuses a released OS lock with an orphaned child",
                finish(start(root, "try", root.toString())), "worktree_recovery_required");
        check.that("the crash contender creates no workflow reservation",
                !Files.exists(root.resolve(".warden/runs")));
        stop(child);
        check.eq("controller death remains fenced after the child is stopped for inspection",
                "worktree_recovery_required", admission(root, "post-fence-inspection"));
        check.eq("crash recovery uses durable PIDs without deleting state files", true,
                WorktreeLease.recover(root, false).get("ok"));
        check.eq("a recovered crashed checkout is reusable", "acquired", admission(root, "post-recovery"));
    }

    private void daemonAndVendorChildren(Check check) throws Exception {
        for (boolean vendor : List.of(false, true)) {
            Path root = emptyCheckout(vendor ? "vendor-descendants" : "gate-daemon");
            Path control = controls(vendor ? "vendor-descendants" : "gate-daemon");
            try (var lease = WorktreeLease.acquire(root, "parent-with-child")) {
                List<String> command = javaCommand("daemon-parent", control.toString());
                ProcessRunner runner = new ProcessRunner();
                if (vendor) runner.runVendor(command, root, Duration.ofSeconds(10), 1024, null,
                        control.resolve("stdout"), control.resolve("stderr"), 1024 * 1024);
                else runner.run(command, root, Duration.ofSeconds(10));
            }
            long pid = Long.parseLong(Files.readString(control.resolve("entered")).strip());
            ProcessHandle child = ProcessHandle.of(pid).orElse(null);
            if (child != null) orphans.add(child);
            check.eq(vendor ? "vendor descendants settle before return" : "a gate daemon may outlive its gate",
                    !vendor, child != null && child.isAlive());
            check.eq("only vendor processes affect the next admission: " + vendor,
                    "acquired", admission(root, "after-child-parent"));
            if (child != null) stop(child);
        }
    }

    private void shutdownController(Check check) throws Exception {
        Path root = emptyCheckout("shutdown-owner");
        Path control = controls("shutdown-owner");
        Child controller = start(root, "shutdown-owner", root.toString(), control.toString());
        finish(controller);
        long pid = Long.parseLong(Files.readString(control.resolve("entered")).strip());
        ProcessHandle child = ProcessHandle.of(pid).orElse(null);
        if (child != null) orphans.add(child);
        check.that("JVM shutdown hook stops its known vendor child", child == null || !child.isAlive());
        check.eq("JVM shutdown hook releases proven stopped execution", "acquired", admission(root, "after-shutdown"));
    }

    private Path emptyCheckout(String name) throws IOException {
        Path root = sandbox.resolve(name);
        Files.createDirectories(root.resolve(".git"));
        return root;
    }

    private Path controls(String name) throws IOException {
        Path control = sandbox.resolve("controls").resolve(name);
        Files.createDirectories(control);
        return control;
    }

    private Path project(String name) throws Exception {
        Path root = sandbox.resolve(name);
        write(root.resolve(".gitignore"), ".warden/runs/\n");
        write(root.resolve("src/seed.txt"), "seed\n");
        write(root.resolve(".warden/project.yaml"), """
                version: 1
                project: lease-test
                base_ref: HEAD
                checks:
                  fast: ["echo lease-gate-ok"]
                scopes:
                  app: ["src"]
                defaults:
                  checks: fast
                  risk: low
                """);
        write(root.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Exercise local worktree admission
                risk: low
                scope: app
                authority:
                  workspace_write: true
                budgets:
                  max_role_runs: 2
                  max_cost_usd: 10.0
                max_fix_attempts: 0
                """);
        command(root, "git", "init", "-q", "-b", "main");
        command(root, "git", "add", ".");
        command(root, "git", "-c", "user.name=Lease fixture", "-c", "user.email=lease@example.invalid",
                "commit", "-q", "-m", "test: seed local worktree lease fixture");
        return root;
    }

    private record Child(Process process, Path output) { }

    private Child start(Path root, String... args) throws IOException {
        Path output = sandbox.resolve("child-" + (++nextChild) + ".log");
        Child child = new Child(new ProcessBuilder(javaCommand(args)).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start(), output);
        children.add(child);
        return child;
    }

    private void await(Path signal, Child child) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!Files.isRegularFile(signal)) {
            if (!child.process().isAlive() || System.nanoTime() >= deadline) {
                throw new IOException("local lease helper did not reach barrier: "
                        + Files.readString(child.output()));
            }
            Thread.sleep(20);
        }
        // Remember the exact synthetic vendor while its controller is still alive. If a
        // later assertion or helper fails, cleanup must also stop that vendor after its
        // parent exits; it must never rely on parent termination killing descendants.
        long pid = Long.parseLong(Files.readString(signal).strip());
        ProcessHandle.of(pid).ifPresent(orphans::add);
    }

    private static String finish(Child child) throws Exception {
        if (!child.process().waitFor(25, TimeUnit.SECONDS)) {
            throw new IOException("local lease helper did not settle: " + Files.readString(child.output()));
        }
        String output = Files.readString(child.output());
        if (child.process().exitValue() != 0) throw new IOException("local lease helper failed: " + output);
        return output;
    }

    private static void stop(Process process) throws Exception {
        if (!process.isAlive()) return;
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("cannot stop local helper " + process.pid());
    }

    private static void stop(ProcessHandle process) throws Exception {
        if (!process.isAlive()) return;
        process.destroyForcibly();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        if (process.isAlive()) throw new IOException("cannot stop exact local child " + process.pid());
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".lease-test-", ".tmp");
        try {
            Files.writeString(temporary, body);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void command(Path root, String... command) throws Exception {
        ProcessRunner.Result result = new ProcessRunner().run(List.of(command), root, Duration.ofSeconds(20));
        if (!result.ok()) throw new IOException("fixture command failed: " + result.stdout() + result.stderr());
    }

    private static String admission(Path root, String runId) throws Exception {
        try (var ignored = WorktreeLease.acquire(root, runId)) {
            return "acquired";
        } catch (WorktreeLease.Refused refused) {
            return refused.code();
        }
    }

    private static List<String> javaCommand(String... args) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        String classpath = String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString()).toList());
        List<String> result = new ArrayList<>(List.of(executable, "-cp", classpath,
                WorktreeLeaseTest.class.getName()));
        result.addAll(List.of(args));
        return result;
    }

    private static UserConfig user(Path home, Path control) throws Exception {
        write(home.resolve("prompt.md"), "Synthetic local writer. Complete the task and return JSON.");
        Policy policy = Policy.parse("""
                version: 1
                roles:
                  implementer: { profiles: [lease-writer], strategy: first, require_independent_vendor: false }
                review: { required_for_risk: [medium, high] }
                workflow:
                  stages:
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: stop }
                """, "local lease policy");
        List<String> command = javaCommand("vendor", control.toString());
        Profile profile = new Profile("lease-writer", "implementer", "local-test", null, null,
                command.get(0), command.subList(1, command.size()), false, 1, null, "prompt.md", null,
                false, List.of("status"), List.of(), "argv", null, null, "direct", null, null, null,
                List.of(), true);
        return new UserConfig(home, policy, Map.of(profile.name(), profile), Map.of(), true);
    }

    /** Local subprocess entrypoints also allow an isolated invocation of this suite. */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Check check = new Check();
            check.beginSuite("worktree lease");
            new WorktreeLeaseTest().run(check);
            System.out.println("passed=" + check.passed() + " failures=" + check.failures());
            if (!check.failures().isEmpty()) System.exit(1);
            return;
        }
        try {
            switch (args[0]) {
                case "try" -> System.out.println(admission(Path.of(args[1]), "different-controller"));
                case "hold" -> {
                    Path root = Path.of(args[1]);
                    Path control = Path.of(args[2]);
                    try (var ignored = WorktreeLease.acquire(root, "owner-controller")) {
                        waitAtBarrier(control);
                    }
                    System.out.println("released");
                }
                case "idle", "vendor" -> {
                    waitAtBarrier(Path.of(args[1]));
                    if (args[0].equals("vendor")) {
                        System.out.println("{\"status\":\"completed\",\"summary\":\"Local lease writer finished\"}");
                    }
                }
                case "daemon-parent" -> {
                    Path control = Path.of(args[1]);
                    new ProcessBuilder(javaCommand("idle", args[1])).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    while (!Files.exists(control.resolve("entered"))) Thread.sleep(20);
                    Thread.sleep(500); // give the controller a deterministic descendant observation window
                }
                case "shutdown-owner" -> {
                    Path root = Path.of(args[1]);
                    try (var lease = WorktreeLease.acquire(root, "shutdown")) {
                        Process child = new ProcessBuilder(javaCommand("idle", args[2]))
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                        WorktreeLease.track(child.toHandle());
                        while (!Files.exists(Path.of(args[2]).resolve("entered"))) Thread.sleep(20);
                        System.exit(0); // scopes do not unwind; only the production shutdown hook runs
                    }
                }
                case "crash-owner" -> {
                    Path root = Path.of(args[1]);
                    try (var ignored = WorktreeLease.acquire(root, "crash-owner")) {
                        new ProcessRunner().runVendor(javaCommand("idle", args[2]), root, Duration.ofSeconds(90),
                                1024, null, root.resolve("stdout"), root.resolve("stderr"), 1024 * 1024);
                    }
                }
                case "workflow", "role", "dry-do" -> {
                    Path root = Path.of(args[1]);
                    UserConfig user = user(Path.of(args[2]), Path.of(args[3]));
                    ConfigLoader.Loaded loaded = new ConfigLoader().load(root, "hello");
                    if (args[0].equals("dry-do")) {
                        DoCommand.Options options = new DoCommand.Options(root, "Draft a concurrent contract",
                                "app", "low", args[4], args[4], "HEAD", true, true);
                        System.out.println(new DoCommand(new ProcessRunner()).run(options, user).code());
                    } else if (args[0].equals("workflow")) {
                        TaskLoop.Outcome outcome = new TaskLoop(new ProcessRunner()).withOrcaGate(false)
                                .run(loaded, user, args[4], false);
                        System.out.println(outcome.reason());
                    } else {
                        RoleRunner.Outcome outcome = new RoleRunner(new ProcessRunner()).run(loaded, user,
                                "implementer", args[4], null, null, false);
                        System.out.println(outcome.ok() ? "role_ok" : outcome.code());
                    }
                }
                default -> throw new IllegalArgumentException("unknown lease helper mode " + args[0]);
            }
        } catch (WorktreeLease.Refused refused) {
            System.out.println(refused.code());
        }
    }

    private static void waitAtBarrier(Path control) throws Exception {
        write(control.resolve("entered"), Long.toString(ProcessHandle.current().pid()));
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!Files.exists(control.resolve("release"))) {
            if (System.nanoTime() >= deadline) throw new IOException("local test barrier timed out");
            Thread.sleep(20);
        }
    }
}
