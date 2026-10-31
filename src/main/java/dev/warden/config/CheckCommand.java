package dev.warden.config;

import dev.warden.execution.ModelCheck;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleRunner;
import dev.warden.run.TaskLoop;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.function.Consumer;

public final class CheckCommand {
    private CheckCommand() {}
    public static Map<String, Object> run(String[] args, UserConfig user, Path project,
                                          ProcessRunner processes, Consumer<String> progress) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        String task = null;
        boolean all = false;
        for (int index = 1; index < args.length; index++) {
            switch (args[index]) {
                case "--all" -> all = true;
                case "--profile" -> {
                    if (++index == args.length) throw new IllegalArgumentException("--profile needs a name");
                    names.add(args[index]);
                }
                case "--task" -> {
                    if (++index == args.length) throw new IllegalArgumentException("--task needs a task");
                    task = args[index];
                }
                case "--quiet" -> { }
                default -> throw new IllegalArgumentException("check: unknown argument; use --all, --profile NAME or --task TASK");
            }
        }
        if ((all && (!names.isEmpty() || task != null)) || task != null && !names.isEmpty())
            throw new IllegalArgumentException("choose one check scope: --all, --profile or --task");
        if (task != null) {
            var loaded = new ConfigLoader().load(project, task);
            for (var row : TaskLoop.dispatchPreview(loaded.root(), loaded, user,
                    profile -> RoleRunner.executableFound(processes, profile))) {
                if (!Boolean.TRUE.equals(row.get("skipped")) && row.get("profile") instanceof String name) names.add(name);
            }
        } else if (names.isEmpty()) { names.addAll(user.profiles().keySet()); names.addAll(DecisionProfile.load(user.home()).keySet()); }
        return new ModelCheck(processes).run(user, project, names, progress);
    }
}
