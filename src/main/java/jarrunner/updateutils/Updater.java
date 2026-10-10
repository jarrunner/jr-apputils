package jarrunner.updateutils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** An app's own "update" command, done by jr. jr already knows where the app's releases are (update.url in the
 *  config it carries), checks the download's sha256 and swaps the exe safely, even while it runs; this runs
 *  {@code <exe> -Xjr:update -Xjr:batch} as a child and reads the one JSON line jr prints.
 *
 *  <pre>
 *  var r = Updater.update();          // or Updater.check()
 *  System.out.println(r.message());
 *  System.exit(r.ok() ? 0 : 1);
 *  </pre>
 *  An app with nothing to do around the update just delegates like that. One that must stop something first (a
 *  daemon still running the old code) or tidy up after does that around the call: {@code check()} first, stop, then
 *  {@code update()}. jr's progress and messages go to this process's stderr unless {@code quiet}. */
public final class Updater {
    private Updater() {}

    public static UpdateResult check() {
        return run("-Xjr:update-check", false);
    }

    public static UpdateResult update() {
        return run("-Xjr:update", false);
    }

    /** As check() / update(), but jr's own messages and progress are not shown. */
    public static UpdateResult run(boolean updateNow, boolean quiet) {
        return run(updateNow ? "-Xjr:update" : "-Xjr:update-check", quiet);
    }

    private static UpdateResult run(String flag, boolean quiet) {
        var exe = Jr.exe();
        if (exe.isEmpty()) {
            return new UpdateResult(UpdateResult.Status.UNAVAILABLE, null, null, Jr.notUnderJr("update itself"), 1);
        }
        return run(exe.get(), List.of(flag, "-Xjr:batch"), quiet);
    }

    static UpdateResult run(Path exe, List<String> args, boolean quiet) {
        var cmd = new ArrayList<String>();
        cmd.add(exe.toString());
        cmd.addAll(args);
        try {
            var pb = new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(nullFile()));
            pb.redirectError(quiet ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.INHERIT);
            var p = pb.start();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var code = p.waitFor();
            return parse(out, code);
        } catch (IOException e) {
            return new UpdateResult(UpdateResult.Status.ERROR, null, null, "Could not run " + exe + ": " + e.getMessage(), 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new UpdateResult(UpdateResult.Status.ERROR, null, null, "Interrupted while updating.", 1);
        }
    }

    /** Reads jr's line; a jr too old to know -Xjr:batch prints none, and its exit code still tells success. */
    static UpdateResult parse(String stdout, int code) {
        String line = null;
        for (var l : stdout.split("\n")) if (l.trim().startsWith("{")) line = l.trim();
        if (line == null) {
            var status = code == 0 ? UpdateResult.Status.CURRENT : code == 10 ? UpdateResult.Status.NEWER : UpdateResult.Status.ERROR;
            return new UpdateResult(status, null, null, code == 1 ? "The update failed (this jr reports no details)." : stdout.trim(), code);
        }
        var m = Json.object(line);
        var status = switch (String.valueOf(m.get("status"))) {
            case "current" -> UpdateResult.Status.CURRENT;
            case "newer" -> UpdateResult.Status.NEWER;
            case "updated" -> UpdateResult.Status.UPDATED;
            default -> UpdateResult.Status.ERROR;
        };
        return new UpdateResult(status, (String) m.get("version"), (String) m.get("latest"), (String) m.get("message"), code);
    }

    private static java.io.File nullFile() {
        return new java.io.File(Places.os() == Places.Os.WINDOWS ? "NUL" : "/dev/null");
    }
}
