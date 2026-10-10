// A MANUAL test, not part of the build: updateutils' Install on macOS, in a scratch home so nothing of the real
// account is touched. Needs a jr-maven-plugin-built macOS binary (it carries the app's Info.plist and icon). On a Mac,
// from the repo root, after mvn package:
//   javac -cp target/classes -d target/rt tests/RoundTripMac.java
//   H=$(mktemp -d); HOME=$H java -Duser.home=$H -cp target/classes:target/rt RoundTripMac <macOS binary> "<Display Name>"
import jarrunner.updateutils.*;

import java.nio.file.Files;
import java.nio.file.Path;

/** updateutils round trip on macOS: install (the app in ~/Applications, the command link, PATH in ~/.zprofile, a Desktop
 *  link, a LaunchAgent), check everything exists, reinstall, uninstall, check everything is gone. */
public class RoundTripMac {
    static int pass, fail;

    static void ok(String what, boolean b) {
        System.out.println((b ? "PASS " : "FAIL ") + what);
        if (b) pass++; else fail++;
    }

    public static void main(String[] a) throws Exception {
        var binary = Path.of(a[0]).toAbsolutePath();
        var name = a[1];
        var app = binary.getFileName().toString();
        var home = Places.home();
        if (!System.getenv("HOME").equals(home.toString())) throw new IllegalStateException("run with HOME and user.home both on a scratch folder");
        ok("not under jr: install refuses with a clear message", Install.app(app).run().problems().get(0).contains("not running from its jr launcher"));

        System.setProperty(Jr.EXE_PROPERTY, binary.toString());
        var r = Install.app(app).desktopShortcut(true).startAtLogin("--tray").run();
        System.out.println("problems: " + r.problems());
        var bundle = home.resolve("Applications").resolve(name + ".app");
        var link = home.resolve(".local/bin").resolve(app);
        var desk = Places.desktopDir().resolve(name + ".app");
        var agent = Places.loginItemsDir().resolve("io.github.jarrunner.updateutils." + app + ".plist");
        var profile = home.resolve(".zprofile");
        ok("install: no problems", r.ok());
        ok("install: the app in ~/Applications", Files.isRegularFile(bundle.resolve("Contents/Info.plist")) && Files.isExecutable(bundle.resolve("Contents/MacOS").resolve(app)));
        ok("install: the command links to the app's binary", Files.isSymbolicLink(link) && Files.isSameFile(link, bundle.resolve("Contents/MacOS").resolve(app)));
        ok("install: Result.command is the link", link.equals(r.command()));
        ok("install: Desktop link to the app", Files.isSymbolicLink(desk) && Files.isSameFile(desk, bundle));
        ok("install: LaunchAgent with the arguments", Files.exists(agent) && Files.readString(agent).contains("<string>--tray</string>")
                && new ProcessBuilder("plutil", "-lint", agent.toString()).start().waitFor() == 0);
        ok("install: ~/.local/bin on PATH in ~/.zprofile", Files.exists(profile) && Files.readString(profile).contains(".local/bin"));
        ok("install: receipt", Files.exists(Places.dataDir(app).resolve("jr-install.json")));

        var r2 = Install.app(app).desktopShortcut(true).startAtLogin("--tray").run();
        ok("reinstall: no problems, the PATH line once", r2.ok() && Files.readString(profile).split("\\.local/bin", -1).length == 2);
        ok("reinstall: app, link, Desktop link and LaunchAgent still there", Files.exists(bundle) && Files.isSymbolicLink(link) && Files.isSymbolicLink(desk) && Files.exists(agent));

        var u = Install.uninstall(app);
        System.out.println("uninstall problems: " + u.problems());
        ok("uninstall: no problems", u.ok());
        ok("uninstall: the app gone", !Files.exists(bundle));
        ok("uninstall: the command link gone", !Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        ok("uninstall: Desktop link gone", !Files.exists(desk, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        ok("uninstall: LaunchAgent gone", !Files.exists(agent));
        ok("uninstall: the PATH line gone", !Files.exists(profile) || !Files.readString(profile).contains(".local/bin"));
        ok("uninstall: receipt and data folder gone", !Files.exists(Places.dataDir(app)));
        System.out.println("passed " + pass + ", failed " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
