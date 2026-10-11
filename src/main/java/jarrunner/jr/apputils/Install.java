package jarrunner.jr.apputils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** An app's own "install" command: puts the exe that is running into the user's standard place and wires it into the
 *  system, for this user only, without administrator rights. What it does is written to a receipt
 *  ({@link Places#dataDir}/jr-install.json), so running it again replaces exactly what it made and {@link #uninstall}
 *  removes exactly that and nothing else.
 *
 *  <pre>
 *  var r = Install.app("myapp").displayName("My App").description("Does things")
 *          .desktopShortcut(true).run();
 *  </pre>
 *  <ul>
 *  <li>Windows: copies the exe to %LOCALAPPDATA%\Programs\&lt;app&gt;\&lt;app&gt;.exe (a running copy there is renamed to
 *      .jr-replaced, which jr deletes on a later launch), puts that folder first on the user PATH and tells Explorer,
 *      writes Start Menu and (if asked) Desktop shortcuts, and (if asked) a Run entry to start at sign-in.</li>
 *  <li>macOS: has jr write the app (&lt;exe&gt; -Xjr:install: ~/Applications/&lt;Name&gt;.app from the Info.plist and icon the
 *      binary carries, and ~/.local/bin/&lt;app&gt; linking to it), adds ~/.local/bin to PATH in ~/.zprofile if no shell
 *      has it, and (if asked) a Desktop link to the app and a LaunchAgent.</li>
 *  <li>Linux: copies the binary to ~/.local/bin, adds that to PATH in ~/.profile if needed, and writes .desktop
 *      entries for the menu, the Desktop and autostart as asked.</li>
 *  </ul>
 *  Each step that fails is reported and the rest still run. Nothing that this did not create is changed or removed. */
public final class Install {
    private final String app;
    private String displayName, description, shortcutArguments = "", loginArguments;
    private Path installDir, workingDir;
    private boolean onPath = true, menuShortcut = true, desktopShortcut, startAtLogin;
    private Consumer<String> log = System.out::println;

    private Install(String app) {
        if (!app.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("app name must be a plain file name: " + app);
        this.app = app;
        this.displayName = app;
    }

    /** app = the command name, the exe's name without .exe, and the name of the app's folders. */
    public static Install app(String app) {
        return new Install(app);
    }

    /** The name on shortcuts and the Start Menu (macOS takes the app's name from its Info.plist). */
    public Install displayName(String v) { displayName = v; return this; }
    public Install description(String v) { description = v; return this; }
    /** Instead of Places.installDir (Windows and Linux; macOS apps always go to ~/Applications). */
    public Install installDir(Path v) { installDir = v; return this; }
    public Install workingDir(Path v) { workingDir = v; return this; }
    public Install onPath(boolean v) { onPath = v; return this; }
    public Install menuShortcut(boolean v) { menuShortcut = v; return this; }
    public Install desktopShortcut(boolean v) { desktopShortcut = v; return this; }
    /** Arguments the shortcuts pass (empty: the app as if double-clicked). */
    public Install shortcutArguments(String v) { shortcutArguments = v == null ? "" : v; return this; }
    /** Start the app when the user signs in, with these arguments. */
    public Install startAtLogin(String arguments) { startAtLogin = true; loginArguments = arguments == null ? "" : arguments; return this; }
    /** Where progress lines go; System.out by default. */
    public Install log(Consumer<String> v) { log = v; return this; }

    /** What an install or uninstall did: the command it put in place (or null), and the steps that failed. */
    public record Result(Path command, List<String> problems) {
        public boolean ok() { return problems.isEmpty(); }
    }

    public Result run() {
        var exe = JrApp.exe();
        if (exe.isEmpty()) return new Result(null, List.of(JrApp.notUnderJr("install itself")));
        var problems = new ArrayList<String>();
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("app", app);
        receipt.put("installed", Instant.now().toString());
        var made = new ArrayList<Object>();
        receipt.put("made", made);
        var previous = readReceipt(app);
        if (previous != null) removeMade(previous, false, problems::add); // what an earlier install made, so nothing is left from it
        Path command = null;
        try {
            command = switch (Places.os()) {
                case WINDOWS -> windows(exe.get(), made, problems);
                case MACOS -> macos(exe.get(), made, problems);
                case LINUX -> linux(exe.get(), made, problems);
            };
        } catch (RuntimeException | IOException e) {
            problems.add("install: " + e.getMessage());
        }
        if (command != null) receipt.put("command", command.toString());
        try {
            writeReceipt(app, receipt);
        } catch (IOException e) {
            problems.add("could not write the install receipt: " + e.getMessage());
        }
        for (var p : problems) log.accept("problem: " + p);
        return new Result(command, List.copyOf(problems));
    }

    // ---- Windows ----

    private Path windows(Path exe, List<Object> made, List<String> problems) throws IOException {
        var dir = installDir != null ? installDir : Places.installDir(app);
        var target = dir.resolve(app + ".exe");
        Files.createDirectories(dir);
        if (!same(exe, target)) {
            place(exe, target);
            log.accept("Installed " + target);
        } else {
            log.accept("Already installed at " + target);
        }
        made.add(item("file", target));
        if (onPath) step(problems, "PATH", () -> {
            if (winPathAdd(dir)) log.accept(dir + " is first on your PATH (new terminals see it)");
            made.add(item("path", dir));
        });
        if (menuShortcut) step(problems, "Start Menu shortcut", () -> {
            var lnk = Places.menuDir().resolve(displayName + ".lnk");
            WinNative.shortcut(lnk, target, shortcutArguments, workingDir, description);
            made.add(item("file", lnk));
            log.accept("Start Menu: " + displayName);
        });
        if (desktopShortcut) step(problems, "Desktop shortcut", () -> {
            var lnk = Places.desktopDir().resolve(displayName + ".lnk");
            WinNative.shortcut(lnk, target, shortcutArguments, workingDir, description);
            made.add(item("file", lnk));
            log.accept("Desktop shortcut: " + displayName);
        });
        if (startAtLogin) step(problems, "start at sign-in", () -> {
            WinNative.regSet(RUN_KEY, displayName, "\"" + target + "\"" + (loginArguments.isEmpty() ? "" : " " + loginArguments), false);
            made.add(item("run", displayName));
            log.accept("Starts when you sign in");
        });
        return target;
    }

    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    /** Puts dir first in the user PATH, keeping the value's registry type (an REG_EXPAND_SZ keeps its %VARIABLES%;
     *  reading it expanded and writing it back would flatten them for good). True if the PATH changed. */
    static boolean winPathAdd(Path dir) {
        var raw = WinNative.regGet("Environment", "Path");
        var entries = new ArrayList<String>();
        entries.add(dir.toString());
        if (raw != null) for (var e : raw.split(";")) if (!e.isBlank() && !sameEntry(e, dir)) entries.add(e);
        var value = String.join(";", entries);
        if (value.equals(raw)) return false;
        WinNative.regSet("Environment", "Path", value, WinNative.regIsExpand("Environment", "Path"));
        WinNative.broadcastEnvironmentChange();
        return true;
    }

    /** Takes a folder off the user PATH (Windows; elsewhere it does nothing): for moving an app off a layout an
     *  older version of it used. True if the PATH changed. */
    public static boolean removeFromUserPath(Path dir) {
        return Places.os() == Places.Os.WINDOWS && winPathRemove(dir);
    }

    static boolean winPathRemove(Path dir) {
        var raw = WinNative.regGet("Environment", "Path");
        if (raw == null) return false;
        var entries = new ArrayList<String>();
        for (var e : raw.split(";")) if (!e.isBlank() && !sameEntry(e, dir)) entries.add(e);
        var value = String.join(";", entries);
        if (value.equals(raw)) return false;
        WinNative.regSet("Environment", "Path", value, WinNative.regIsExpand("Environment", "Path"));
        WinNative.broadcastEnvironmentChange();
        return true;
    }

    private static boolean sameEntry(String entry, Path dir) {
        var a = entry.trim().replace('/', '\\');
        while (a.endsWith("\\")) a = a.substring(0, a.length() - 1);
        return a.equalsIgnoreCase(dir.toString());
    }

    // ---- macOS ----

    private Path macos(Path exe, List<Object> made, List<String> problems) throws IOException {
        var out = runJr(exe, "-Xjr:install");
        for (var l : out.text.split("\n")) if (!l.isBlank()) log.accept(l.strip());
        if (out.code != 0) throw new IOException("the app could not be written (" + exe + " -Xjr:install, exit " + out.code + ")");
        var link = Places.home().resolve(".local/bin").resolve(app);
        made.add(item("file", link));
        var inApp = Files.isSymbolicLink(link) ? link.resolveSibling(Files.readSymbolicLink(link)).normalize() : null;
        var bundle = inApp == null ? null : bundleOf(inApp);
        if (bundle != null) made.add(item("dir", bundle));
        if (onPath) step(problems, "PATH", () -> pathInProfile(Places.home().resolve(".zprofile"), made));
        if (desktopShortcut && bundle != null) step(problems, "Desktop link", () -> {
            var l = Places.desktopDir().resolve(bundle.getFileName());
            Files.createDirectories(l.getParent());
            Files.deleteIfExists(l);
            Files.createSymbolicLink(l, bundle);
            made.add(item("file", l));
            log.accept("Desktop: " + bundle.getFileName());
        });
        if (startAtLogin) step(problems, "start at login", () -> {
            var label = "io.github.jarrunner.apputils." + app;
            var plist = Places.loginItemsDir().resolve(label + ".plist");
            Files.createDirectories(plist.getParent());
            var args = new StringBuilder("    <string>").append(xml((inApp != null ? inApp : link).toString())).append("</string>\n");
            for (var a : loginArguments.split(" ")) if (!a.isBlank()) args.append("    <string>").append(xml(a)).append("</string>\n");
            Files.writeString(plist, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                    + "<plist version=\"1.0\">\n<dict>\n  <key>Label</key>\n  <string>" + label + "</string>\n"
                    + "  <key>ProgramArguments</key>\n  <array>\n" + args + "  </array>\n"
                    + "  <key>RunAtLoad</key>\n  <true/>\n</dict>\n</plist>\n");
            made.add(item("file", plist));
            log.accept("Starts when you log in");
        });
        return link;
    }

    private static Path bundleOf(Path inApp) {
        for (var p = inApp; p != null; p = p.getParent()) if (p.getFileName() != null && p.getFileName().toString().endsWith(".app")) return p;
        return null;
    }

    // ---- Linux ----

    private Path linux(Path exe, List<Object> made, List<String> problems) throws IOException {
        var dir = installDir != null ? installDir : Places.installDir(app);
        var target = dir.resolve(app);
        Files.createDirectories(dir);
        if (!same(exe, target)) {
            place(exe, target);
            target.toFile().setExecutable(true, false);
            log.accept("Installed " + target);
        }
        made.add(item("file", target));
        if (onPath) step(problems, "PATH", () -> pathInProfile(Places.home().resolve(".profile"), made));
        var entry = "[Desktop Entry]\nType=Application\nName=" + displayName + "\n"
                + (description == null ? "" : "Comment=" + description + "\n")
                + "Exec=\"" + target + "\"" + (shortcutArguments.isEmpty() ? "" : " " + shortcutArguments) + "\n"
                + (workingDir == null ? "" : "Path=" + workingDir + "\n") + "Terminal=false\n";
        if (menuShortcut) step(problems, "menu entry", () -> made.add(item("file", desktopFile(Places.menuDir(), entry))));
        if (desktopShortcut) step(problems, "Desktop entry", () -> made.add(item("file", desktopFile(Places.desktopDir(), entry))));
        if (startAtLogin) step(problems, "autostart", () -> made.add(item("file", desktopFile(Places.loginItemsDir(),
                entry.replaceFirst("(?m)^Exec=.*$", "Exec=\"" + target.toString().replace("\\", "\\\\").replace("$", "\\$") + "\"" + (loginArguments.isEmpty() ? "" : " " + loginArguments))))));
        return target;
    }

    private Path desktopFile(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        var f = dir.resolve(app + ".desktop");
        Files.writeString(f, content);
        f.toFile().setExecutable(true, false);
        return f;
    }

    // ---- macOS and Linux: ~/.local/bin on PATH ----

    private static final String PROFILE_LINE = "export PATH=\"$HOME/.local/bin:$PATH\"";

    /** Adds ~/.local/bin to PATH in a shell profile, unless PATH has it already or the profile already says so. */
    private void pathInProfile(Path profile, List<Object> made) throws IOException {
        var line = PROFILE_LINE + "  # added by " + app + " (jr-apputils)";
        var m = item("profile", profile);
        m.put("line", line);
        var existing = Files.exists(profile) ? Files.readString(profile) : "";
        if (existing.contains(line + "\n")) { // ours, from an earlier install: keep it on the receipt
            made.add(m);
            return;
        }
        var bin = Places.home().resolve(".local/bin").toString();
        var path = System.getenv("PATH");
        if (path != null) for (var p : path.split(":")) if (p.equals(bin) || p.equals(bin + "/")) return;
        if (existing.contains(".local/bin")) return;
        Files.writeString(profile, existing + (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n") + line + "\n");
        made.add(m);
        log.accept("Added ~/.local/bin to PATH in " + profile + " (new terminals see it)");
    }

    // ---- uninstall ----

    /** Removes what the last install made (from its receipt) and the receipt. A file in use (the running exe on
     *  Windows) is renamed to .jr-replaced instead, which jr deletes on a later launch. */
    public static Result uninstall(String app) {
        return uninstall(app, System.out::println);
    }

    public static Result uninstall(String app, Consumer<String> log) {
        var receipt = readReceipt(app);
        if (receipt == null) return new Result(null, List.of("no install receipt for " + app + " (" + receiptFile(app) + ")"));
        var problems = new ArrayList<String>();
        removeMade(receipt, true, s -> { problems.add(s); log.accept("problem: " + s); });
        try {
            Files.deleteIfExists(receiptFile(app));
            deleteIfEmpty(receiptFile(app).getParent(), receipt);
        } catch (IOException e) {
            problems.add("could not remove the receipt: " + e.getMessage());
        }
        log.accept("Uninstalled " + app);
        return new Result(null, List.copyOf(problems));
    }

    /** Undoes a receipt's entries. all=false is the reinstall case: the program files, PATH entry and profile line
     *  are about to be written again, so only shortcuts and login items are removed (their names may have changed). */
    @SuppressWarnings("unchecked")
    private static void removeMade(Map<String, Object> receipt, boolean all, Consumer<String> problem) {
        if (!(receipt.get("made") instanceof List<?> list)) return;
        for (var o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            var m = (Map<String, Object>) raw;
            var kind = String.valueOf(m.get("kind"));
            var at = String.valueOf(m.get("at"));
            try {
                switch (kind) {
                    case "path" -> { if (all && Places.os() == Places.Os.WINDOWS) winPathRemove(Path.of(at)); }
                    case "run" -> WinNative.regSet(RUN_KEY, at, null, false);
                    case "profile" -> {
                        if (!all) break;
                        var f = Path.of(at);
                        var line = String.valueOf(m.get("line"));
                        if (Files.exists(f)) Files.writeString(f, Files.readString(f).replace(line + "\n", ""));
                    }
                    case "file" -> {
                        var f = Path.of(at);
                        var lower = at.toLowerCase(Locale.ROOT);
                        var isProgram = !(lower.endsWith(".lnk") || lower.endsWith(".desktop") || lower.endsWith(".plist") || lower.endsWith(".app"));
                        if (!all && isProgram) break;
                        deleteOrSetAside(f);
                        if (all && isProgram) { // and what an update left beside it, and the folder if that empties it
                            Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".jr-replaced"));
                            deleteIfEmpty(f.getParent(), receipt);
                        }
                    }
                    case "dir" -> { if (all) deleteTree(Path.of(at)); }
                    default -> { }
                }
            } catch (RuntimeException | IOException e) {
                problem.accept("removing " + kind + " " + at + ": " + e.getMessage());
            }
        }
    }

    // ---- helpers ----

    private interface Step { void run() throws Exception; }

    private static void step(List<String> problems, String what, Step s) {
        try {
            s.run();
        } catch (Exception e) {
            problems.add(what + ": " + e.getMessage());
        }
    }

    private static Map<String, Object> item(String kind, Object at) {
        var m = new LinkedHashMap<String, Object>();
        m.put("kind", kind);
        m.put("at", at.toString());
        return m;
    }

    private static boolean same(Path a, Path b) {
        try {
            return Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    /** Copies src beside target and renames it in; a target in use is renamed to .jr-replaced first (Windows lets a
     *  running exe be renamed, never overwritten). */
    private static void place(Path src, Path target) throws IOException {
        var tmp = target.resolveSibling(target.getFileName() + ".jr-new");
        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException busy) {
            var aside = target.resolveSibling(target.getFileName() + ".jr-replaced");
            Files.deleteIfExists(aside);
            Files.move(target, aside);
            Files.move(tmp, target);
        }
    }

    private static void deleteOrSetAside(Path f) throws IOException {
        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.delete(f);
        } catch (IOException busy) {
            var aside = f.resolveSibling(f.getFileName() + ".jr-replaced");
            Files.deleteIfExists(aside);
            Files.move(f, aside);
        }
    }

    /** Removes dir when it is empty and named after the app (its install or data folder), never a shared folder
     *  such as ~/.local/bin or the Desktop. */
    private static void deleteIfEmpty(Path dir, Map<String, Object> receipt) throws IOException {
        if (dir == null || dir.getFileName() == null || !dir.getFileName().toString().equals(receipt.get("app"))) return;
        try (var entries = Files.list(dir)) {
            if (entries.findAny().isPresent()) return;
        }
        Files.deleteIfExists(dir);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(dir)) {
            for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    static Path receiptFile(String app) {
        return Places.dataDir(app).resolve("jr-install.json");
    }

    static Map<String, Object> readReceipt(String app) {
        var f = receiptFile(app);
        try {
            return Files.exists(f) ? Json.object(Files.readString(f)) : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static void writeReceipt(String app, Map<String, Object> receipt) throws IOException {
        var f = receiptFile(app);
        Files.createDirectories(f.getParent());
        Files.writeString(f, Json.write(receipt));
    }

    private record Output(int code, String text) {}

    private static Output runJr(Path exe, String flag) throws IOException {
        try {
            var p = new ProcessBuilder(exe.toString(), flag).redirectErrorStream(true).start();
            var text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Output(p.waitFor(), text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
