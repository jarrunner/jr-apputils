// A MANUAL test, not part of the build: it really writes to this user's PATH, Start Menu, Desktop and HKCU Run key
// (with a unique test name) and removes all of it again. Windows only. From the repo root, after mvn package:
//   javac -cp target/classes -d target/rt tests/RoundTrip.java
//   java --enable-native-access=ALL-UNNAMED -cp "target/classes;target/rt" RoundTrip <scratch>\uu-roundtrip-test <any jr exe>
import jarrunner.updateutils.*;

import java.nio.file.Files;
import java.nio.file.Path;

/** updateutils round trip on this machine: install (scratch install dir, real PATH/Start Menu/Desktop/Run with a
 *  unique test name), check everything exists, uninstall, check everything is gone. */
public class RoundTrip {
    static int pass, fail;

    static void ok(String what, boolean b) {
        System.out.println((b ? "PASS " : "FAIL ") + what);
        if (b) pass++; else fail++;
    }

    public static void main(String[] a) throws Exception {
        var app = "uu-roundtrip-test";
        var dir = Path.of(a[0]);
        System.out.println("installDir default would be " + Places.installDir(app));
        System.out.println("desktop " + Places.desktopDir() + ", menu " + Places.menuDir() + ", data " + Places.dataDir(app));
        ok("not under jr: install refuses with a clear message", Install.app(app).run().problems().get(0).contains("not running from its jr launcher"));
        ok("not under jr: update UNAVAILABLE", Updater.update().status() == UpdateResult.Status.UNAVAILABLE);

        System.setProperty(Jr.EXE_PROPERTY, a[1]);
        var r = Install.app(app).displayName("UU Roundtrip Test").description("updateutils test").installDir(dir)
                .desktopShortcut(true).startAtLogin("--tray").run();
        System.out.println("problems: " + r.problems());
        var exe = dir.resolve(app + ".exe");
        var lnkMenu = Places.menuDir().resolve("UU Roundtrip Test.lnk");
        var lnkDesk = Places.desktopDir().resolve("UU Roundtrip Test.lnk");
        ok("install: no problems", r.ok());
        ok("install: exe copied", Files.size(exe) == Files.size(Path.of(a[1])));
        ok("install: Start Menu shortcut", Files.exists(lnkMenu));
        ok("install: Desktop shortcut", Files.exists(lnkDesk));
        var path = regPath();
        ok("install: dir first on user PATH", path.startsWith(dir.toString() + ";"));
        ok("install: Run entry", regRun("UU Roundtrip Test") != null && regRun("UU Roundtrip Test").contains("--tray"));
        ok("install: receipt", Files.exists(Places.dataDir(app).resolve("jr-install.json")));

        var r2 = Install.app(app).displayName("UU Roundtrip Test").installDir(dir).desktopShortcut(true).startAtLogin("--tray").run();
        ok("reinstall: no problems, PATH has the dir once", r2.ok() && regPath().split(java.util.regex.Pattern.quote(dir.toString()), -1).length == 2);

        Files.writeString(dir.resolve(app + ".exe.jr-replaced"), "left by an update");
        var u = Install.uninstall(app);
        System.out.println("uninstall problems: " + u.problems());
        ok("uninstall: no problems", u.ok());
        ok("uninstall: exe gone", !Files.exists(exe));
        ok("uninstall: shortcuts gone", !Files.exists(lnkMenu) && !Files.exists(lnkDesk));
        ok("uninstall: PATH entry gone", !regPath().contains(dir.toString()));
        ok("uninstall: Run entry gone", regRun("UU Roundtrip Test") == null);
        ok("uninstall: receipt gone", !Files.exists(Places.dataDir(app).resolve("jr-install.json")));
        ok("uninstall: install folder and data folder gone", !Files.exists(dir) && !Files.exists(Places.dataDir(app)));
        Files.deleteIfExists(Places.dataDir(app));
        System.out.println("passed " + pass + ", failed " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static String regPath() throws Exception {
        return query("Environment", "Path");
    }

    static String regRun(String name) throws Exception {
        return query("Software\\Microsoft\\Windows\\CurrentVersion\\Run", name);
    }

    static String query(String key, String name) throws Exception {
        var p = new ProcessBuilder("reg", "query", "HKCU\\" + key, "/v", name).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) return null;
        for (var l : out.split("\r?\n")) {
            var t = l.trim();
            if (t.startsWith(name + " ")) {
                var parts = t.split("\\s{4}", 3);
                return parts.length == 3 ? parts[2] : "";
            }
        }
        return null;
    }
}
