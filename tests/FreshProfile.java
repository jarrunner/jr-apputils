// Run by CI (.github/workflows/fresh-profile.yml) on a throwaway windows-latest runner: install with the DEFAULT
// install folder on a profile that has no %LOCALAPPDATA%\Programs. RoundTrip.java always passes installDir, so it
// never resolves the default folder; this is the case 1.0 failed (SHGetKnownFolderPath 0x80070002, jarrunner PRP-48).
// It writes the real user PATH, Start Menu and Desktop, so do not run it on a machine you use. From the repo root:
//   javac -cp target/classes -d target/fp tests/FreshProfile.java
//   java --enable-native-access=ALL-UNNAMED -cp "target/classes;target/fp" FreshProfile <any jr exe>
import jarrunner.jr.apputils.*;

import java.nio.file.Files;
import java.nio.file.Path;

public class FreshProfile {
    public static void main(String[] a) throws Exception {
        var app = "uu-fresh-profile-test";
        var programs = Path.of(System.getenv("LOCALAPPDATA"), "Programs");
        System.out.println("before: " + programs + (Files.exists(programs) ? " exists" : " does not exist"));
        System.setProperty(JrApp.EXE_PROPERTY, a[0]);
        var r = Install.app(app).displayName("UU Fresh Profile Test").log(System.out::println).run();
        System.out.println("problems: " + r.problems() + ", command: " + r.command());
        var exe = programs.resolve(app).resolve(app + ".exe");
        var ok = r.ok() && Files.isRegularFile(exe);
        Install.uninstall(app);
        System.out.println(ok ? "PASS install with the default folder on a fresh profile" : "FAIL install with the default folder on a fresh profile");
        System.exit(ok ? 0 : 1);
    }
}
