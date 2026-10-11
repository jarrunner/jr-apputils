package jarrunner.jr.apputils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** What jr told this app about itself. jr passes every leaf of the app's config (the jrc-json it carries) as a system
 *  property {@code io.github.jarrunner.jr.<path>} (list elements as {@code .0}, {@code .1}, ...), plus {@code exe}, its
 *  own path (the app's exe, or on macOS the binary inside the .app), on every launch and in both jvm modes. The
 *  properties are the API; this class gives them names and types.
 *  <p>
 *  Everything is empty when the app was not started by jr (plain java, an IDE, tests), so it can be called
 *  unconditionally, and whatever needs jr says so instead of failing. */
public final class JrApp {
    private JrApp() {}

    public static final String PREFIX = "io.github.jarrunner.jr.";
    public static final String EXE_PROPERTY = PREFIX + "exe";

    /** True when jr started this JVM. */
    public static boolean launchedByJr() {
        return exe().isPresent();
    }

    /** The exe (Windows) or binary (macOS, Linux) jr launched this app from, or empty when not under jr. */
    public static Optional<Path> exe() {
        var p = System.getProperty(EXE_PROPERTY);
        if (p == null || p.isBlank()) return Optional.empty();
        var path = Path.of(p);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** The exe's file name without .exe, e.g. "demo", or empty. */
    public static Optional<String> exeName() {
        return exe().map(p -> p.getFileName().toString()).map(n -> n.toLowerCase().endsWith(".exe") ? n.substring(0, n.length() - 4) : n);
    }

    /** The .app bundle the binary sits in (macOS: X.app/Contents/MacOS/x), or empty. */
    public static Optional<Path> appBundle() {
        return exe().map(Path::toAbsolutePath).flatMap(e -> {
            var macos = e.getParent();
            var contents = macos == null ? null : macos.getParent();
            var app = contents == null ? null : contents.getParent();
            return app != null && macos.getFileName().toString().equals("MacOS") && contents.getFileName().toString().equals("Contents")
                    && app.getFileName().toString().endsWith(".app") ? Optional.of(app) : Optional.empty();
        });
    }

    /** app.id from the config, by convention groupId:artifactId. */
    public static Optional<String> appId() {
        return config("app.id");
    }

    /** app.version from the config: the version this exe was built as. */
    public static Optional<String> appVersion() {
        return config("app.version");
    }

    public static Optional<String> updateUrl() {
        return config("update.url");
    }

    /** update.channel, "stable" when the config names none. */
    public static String updateChannel() {
        return config("update.channel").orElse("stable");
    }

    /** Any leaf of the config by its dotted path, e.g. "jvm.mode" or "jar.sources.0.url". */
    public static Optional<String> config(String path) {
        var v = System.getProperty(PREFIX + path);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v);
    }

    /** A list from the config by its path, e.g. "app.args": path.0, path.1, ... until one is missing. */
    public static List<String> configList(String path) {
        var out = new ArrayList<String>();
        for (var i = 0; ; i++) {
            var v = config(path + "." + i);
            if (v.isEmpty()) return out;
            out.add(v.get());
        }
    }

    /** Message for an app that was asked to do something only jr can do. */
    static String notUnderJr(String what) {
        return "This copy is not running from its jr launcher (no " + EXE_PROPERTY + "), so it cannot " + what
                + ". Run the installed exe, or install it from its download page.";
    }
}
