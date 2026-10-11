package jarrunner.jr.apputils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

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
 *  {@code update()}. jr's progress and messages go to this process's stderr unless {@code quiet}.
 *  <p>
 *  To tell the user at startup, without waiting on the network, a CLI whose stdout is data does:
 *  <pre>
 *  Updater.inBackground(Duration.ofDays(1), r -&gt; {
 *      var line = Updater.notice(r, "update");
 *      if (line != null) System.err.println(line);
 *  });
 *  </pre>
 *  Every decision (which release, which channel, whether it is newer) is jr's, so the rules live in one place. */
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
        var exe = JrApp.exe();
        if (exe.isEmpty()) {
            return new UpdateResult(UpdateResult.Status.UNAVAILABLE, null, null, JrApp.notUnderJr("update itself"), 1);
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

    /** At most one check per interval: a result younger than the interval, for this exe's version and channel, comes
     *  from ~/.jr/update-check/&lt;app.id&gt;.json. Errors are not cached, so an offline start does not hide the next
     *  check. jr's own messages are not shown (quiet): this is for an app that tells the user itself. */
    public static UpdateResult checkAtMostEvery(Duration interval) {
        if (JrApp.exe().isEmpty()) return run(false, true);
        var key = JrApp.appVersion().orElse("") + "|" + JrApp.updateChannel() + "|" + JrApp.updateUrl().orElse("");
        var cache = Path.of(System.getProperty("user.home"), ".jr", "update-check",
                JrApp.appId().orElse(JrApp.exeName().orElse("app")).replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
        try {
            if (Files.isRegularFile(cache)) {
                var m = Json.object(Files.readString(cache));
                if (key.equals(m.get("key")) && Instant.parse((String) m.get("checkedAt")).plus(interval).isAfter(Instant.now())) {
                    var status = UpdateResult.Status.valueOf((String) m.get("status"));
                    return new UpdateResult(status, (String) m.get("version"), (String) m.get("latest"), (String) m.get("message"),
                            status == UpdateResult.Status.NEWER ? 10 : 0);
                }
            }
        } catch (IOException | RuntimeException e) {
            // a damaged cache is a missing one
        }
        var r = run(false, true);
        if (r.status() == UpdateResult.Status.CURRENT || r.status() == UpdateResult.Status.NEWER) {
            var m = new LinkedHashMap<String, Object>();
            m.put("key", key);
            m.put("checkedAt", Instant.now().toString());
            m.put("status", r.status().name());
            m.put("version", r.version());
            m.put("latest", r.latest());
            m.put("message", r.message());
            try {
                Files.createDirectories(cache.getParent());
                var tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
                Files.writeString(tmp, Json.write(m));
                Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // no cache only means the next start checks again
            }
        }
        return r;
    }

    /** checkAtMostEvery on a daemon thread, so startup never waits on the network. onResult runs on that thread, and
     *  only when there is a result to act on (not when the app is not running under jr). */
    public static void inBackground(Duration interval, Consumer<UpdateResult> onResult) {
        var t = new Thread(() -> {
            try {
                var r = checkAtMostEvery(interval);
                if (r.status() != UpdateResult.Status.UNAVAILABLE) onResult.accept(r);
            } catch (RuntimeException e) {
                // an update check must never take the app down
            }
        }, "jr-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** One line for the user when a newer version is out, else null: "demo 1.1 is available (this is 1.0): run demo
     *  update". command is how the user updates this app (its own update command, or "-Xjr:update"). */
    public static String notice(UpdateResult r, String command) {
        if (r.status() != UpdateResult.Status.NEWER) return null;
        var name = JrApp.exeName().orElse("this app");
        return name + " " + r.latest() + " is available" + (r.version() == null ? "" : " (this is " + r.version() + ")")
                + ": run " + name + " " + command;
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
