package dev.warden;

import dev.warden.execution.Isolation;
import dev.warden.execution.orca.OrcaClient;
import dev.warden.execution.orca.OrcaIsolation;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code warden do --isolation orca} must join a worktree it already made for the same
 * name. Creating another is how a dry run followed by a live run left two cards with
 * the same display name.
 */
public final class OrcaIsolationTest implements Suite {

    @Override public String name() { return "orca-isolation"; }

    @Override public void run(Check check) throws Exception {
        joinsAMatchingWorktree(check);
        createsWhenNothingMatches(check);
    }

    private void joinsAMatchingWorktree(Check check) throws Exception {
        Path project = Files.createTempDirectory("warden-orca-iso-project-");
        Path existing = Files.createTempDirectory("warden-orca-iso-existing-");
        List<List<String>> calls = new ArrayList<>();
        OrcaClient client = new OrcaClient((directory, timeout, args) -> {
            calls.add(List.copyOf(args));
            return reply(directory, args, existing, null);
        });
        Isolation.Placement placement = new OrcaIsolation(client)
                .isolate(project, "w-task", "main");
        check.eq("a matching worktree is attached", "attached", placement.reason());
        check.eq("at the path Orca already listed", existing, placement.path());
        check.that("and worktree create is never invoked",
                calls.stream().noneMatch(OrcaIsolationTest::isCreate));
    }

    private void createsWhenNothingMatches(Check check) throws Exception {
        Path project = Files.createTempDirectory("warden-orca-iso-fresh-");
        Path created = Files.createTempDirectory("warden-orca-iso-created-");
        List<List<String>> calls = new ArrayList<>();
        OrcaClient client = new OrcaClient((directory, timeout, args) -> {
            calls.add(List.copyOf(args));
            return reply(directory, args, null, created);
        });
        Isolation.Placement placement = new OrcaIsolation(client)
                .isolate(project, "w-task", "main");
        check.eq("no match creates a worktree", "created", placement.reason());
        check.eq("at the path create returned", created, placement.path());
        check.that("worktree create was invoked",
                calls.stream().anyMatch(OrcaIsolationTest::isCreate));
    }

    private static boolean isCreate(List<String> args) {
        return args.size() >= 2 && args.get(0).equals("worktree") && args.get(1).equals("create");
    }

    private static OrcaClient.Rpc reply(Path directory, List<String> args, Path existing,
                                        Path created) {
        String op = String.join(" ", args.subList(0, Math.min(2, args.size())));
        return switch (op) {
            case "status" -> rpc(Map.of(
                    "app", Map.of("running", true),
                    "runtime", Map.of("reachable", true, "capabilities", List.of())));
            case "worktree current" -> rpc(Map.of(
                    "isMainWorktree", true,
                    "path", directory.toString(),
                    "displayName", "main"));
            case "repo list" -> rpc(Map.of("repos", List.of(
                    Map.of("id", "repo-1", "path", directory.toString()))));
            case "worktree ps" -> {
                List<Map<String, Object>> trees = new ArrayList<>();
                trees.add(Map.of(
                        "workspaceKind", "git",
                        "worktreeId", "repo-1::" + directory,
                        "repoId", "repo-1",
                        "path", directory.toString(),
                        "branch", "refs/heads/main",
                        "isArchived", false,
                        "isMainWorktree", true,
                        "displayName", "main"));
                if (existing != null) {
                    trees.add(Map.of(
                            "workspaceKind", "git",
                            "worktreeId", "repo-1::" + existing,
                            "repoId", "repo-1",
                            "path", existing.toString(),
                            "branch", "refs/heads/w-task",
                            "isArchived", false,
                            "isMainWorktree", false,
                            "displayName", "w-task"));
                }
                yield rpc(Map.of("worktrees", trees));
            }
            case "worktree create" -> {
                if (created == null) {
                    throw new IllegalStateException("unexpected worktree create " + args);
                }
                yield rpc(Map.of("worktree", Map.of(
                        "id", "repo-1::" + created,
                        "path", created.toString())));
            }
            default -> throw new IllegalStateException("unexpected call " + args);
        };
    }

    private static Map<String, Object> envelope(Map<String, Object> result) {
        return Map.of("ok", true, "result", result);
    }

    private static OrcaClient.Rpc rpc(Map<String, Object> result) {
        Map<String, Object> body = envelope(result);
        return new OrcaClient.Rpc(true, 0, false, 0, body, result, Json.write(body), "");
    }
}
