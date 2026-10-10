package jarrunner.updateutils;

import java.nio.file.Path;
import java.util.Locale;

/** Where things go, per operating system, for one user and without administrator rights. Each OS has its own
 *  conventions and they do not look alike; this is the one place that knows them, so app code can just ask.
 *
 *  <pre>
 *                   Windows                                 macOS                                   Linux
 *  install folder   %LOCALAPPDATA%\Programs\&lt;app&gt;            ~/Applications (the .app)               ~/.local/bin
 *  commands (PATH)  the install folder, put on the user PATH ~/.local/bin (a link into the .app)       ~/.local/bin
 *  config           %APPDATA%\&lt;app&gt;                         ~/Library/Application Support/&lt;app&gt;    $XDG_CONFIG_HOME/&lt;app&gt; (~/.config)
 *  data             %LOCALAPPDATA%\&lt;app&gt;                    ~/Library/Application Support/&lt;app&gt;    $XDG_DATA_HOME/&lt;app&gt; (~/.local/share)
 *  cache            %LOCALAPPDATA%\&lt;app&gt;\cache              ~/Library/Caches/&lt;app&gt;                 $XDG_CACHE_HOME/&lt;app&gt; (~/.cache)
 *  logs             %LOCALAPPDATA%\&lt;app&gt;\logs               ~/Library/Logs/&lt;app&gt;                   $XDG_STATE_HOME/&lt;app&gt; (~/.local/state)
 *  desktop          the Desktop known folder (may be OneDrive) ~/Desktop                              $XDG_DESKTOP_DIR or ~/Desktop
 *  menu entries     Start Menu\Programs                     (Launchpad shows ~/Applications)          ~/.local/share/applications
 *  start at login   Startup folder / HKCU\...\Run           ~/Library/LaunchAgents                  ~/.config/autostart
 *  </pre>
 *  Windows folders come from the shell (SHGetKnownFolderPath), never from a variable, because a user can move them.
 *  An app that already has a folder of its own can keep using it; this is for the rest. */
public final class Places {
    private Places() {}

    public enum Os { WINDOWS, MACOS, LINUX }

    public static Os os() {
        var n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return n.startsWith("windows") ? Os.WINDOWS : n.startsWith("mac") ? Os.MACOS : Os.LINUX;
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    /** Where -Xjr:install / Install puts the app: the folder holding the exe on Windows, ~/Applications on macOS
     *  (which holds &lt;Name&gt;.app), ~/.local/bin on Linux (which holds the binary itself). */
    public static Path installDir(String app) {
        return switch (os()) {
            case WINDOWS -> WinNative.knownFolder(WinNative.USER_PROGRAM_FILES).resolve(app);
            case MACOS -> home().resolve("Applications");
            case LINUX -> binDir();
        };
    }

    /** The folder whose commands a terminal finds by name. On Windows that is the install folder (Install puts it on
     *  the user PATH); on macOS and Linux it is ~/.local/bin, which most shells already put on PATH when it exists. */
    public static Path binDir(String app) {
        return os() == Os.WINDOWS ? installDir(app) : binDir();
    }

    private static Path binDir() {
        return home().resolve(".local").resolve("bin");
    }

    public static Path configDir(String app) {
        return switch (os()) {
            case WINDOWS -> WinNative.knownFolder(WinNative.ROAMING_APP_DATA).resolve(app);
            case MACOS -> home().resolve("Library/Application Support").resolve(app);
            case LINUX -> xdg("XDG_CONFIG_HOME", ".config").resolve(app);
        };
    }

    public static Path dataDir(String app) {
        return switch (os()) {
            case WINDOWS -> WinNative.knownFolder(WinNative.LOCAL_APP_DATA).resolve(app);
            case MACOS -> home().resolve("Library/Application Support").resolve(app);
            case LINUX -> xdg("XDG_DATA_HOME", ".local/share").resolve(app);
        };
    }

    public static Path cacheDir(String app) {
        return switch (os()) {
            case WINDOWS -> WinNative.knownFolder(WinNative.LOCAL_APP_DATA).resolve(app).resolve("cache");
            case MACOS -> home().resolve("Library/Caches").resolve(app);
            case LINUX -> xdg("XDG_CACHE_HOME", ".cache").resolve(app);
        };
    }

    public static Path logDir(String app) {
        return switch (os()) {
            case WINDOWS -> WinNative.knownFolder(WinNative.LOCAL_APP_DATA).resolve(app).resolve("logs");
            case MACOS -> home().resolve("Library/Logs").resolve(app);
            case LINUX -> xdg("XDG_STATE_HOME", ".local/state").resolve(app);
        };
    }

    public static Path desktopDir() {
        if (os() == Os.WINDOWS) return WinNative.knownFolder(WinNative.DESKTOP);
        var xdg = System.getenv("XDG_DESKTOP_DIR");
        return xdg != null && xdg.startsWith("/") ? Path.of(xdg) : home().resolve("Desktop");
    }

    /** Start Menu\Programs on Windows, ~/.local/share/applications on Linux; macOS has none (Launchpad lists
     *  ~/Applications by itself). */
    public static Path menuDir() {
        return switch (os()) {
            case WINDOWS -> WinNative.knownFolder(WinNative.PROGRAMS);
            case MACOS -> home().resolve("Applications");
            case LINUX -> xdg("XDG_DATA_HOME", ".local/share").resolve("applications");
        };
    }

    /** The Startup folder on Windows, ~/Library/LaunchAgents on macOS, ~/.config/autostart on Linux. */
    public static Path loginItemsDir() {
        return switch (os()) {
            case WINDOWS -> WinNative.knownFolder(WinNative.STARTUP);
            case MACOS -> home().resolve("Library/LaunchAgents");
            case LINUX -> xdg("XDG_CONFIG_HOME", ".config").resolve("autostart");
        };
    }

    private static Path xdg(String variable, String fallback) {
        var v = System.getenv(variable);
        return v != null && v.startsWith("/") ? Path.of(v) : home().resolve(fallback);
    }
}
