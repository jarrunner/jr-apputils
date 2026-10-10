package jarrunner.updateutils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** What jr tells the app it launched. jr sets the system property io.github.jarrunner.jr.exe to its own path (the
 *  app's exe, or on macOS the binary inside the .app) on every launch, in both jvm modes. When it is missing the app
 *  is not running under jr (from an IDE, or java -jar), and everything here that needs jr says so instead of failing. */
public final class Jr {
    private Jr() {}

    public static final String EXE_PROPERTY = "io.github.jarrunner.jr.exe";

    /** The exe (Windows) or binary (macOS, Linux) jr launched this app from, or empty when not under jr. */
    public static Optional<Path> exe() {
        var p = System.getProperty(EXE_PROPERTY);
        if (p == null || p.isBlank()) return Optional.empty();
        var path = Path.of(p);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    public static boolean running() {
        return exe().isPresent();
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

    /** Message for an app that was asked to do something only jr can do. */
    static String notUnderJr(String what) {
        return "This copy is not running from its jr launcher (no " + EXE_PROPERTY + "), so it cannot " + what
                + ". Run the installed exe, or install it from its download page.";
    }
}
