# updateutils

Install and update for apps launched by [jr](https://github.com/jarrunner/jr). One file is the whole app: the user downloads it, runs `myapp install`, and later `myapp update`. No installer, no script.

jr already does the parts that must happen outside Java: replacing the running exe safely (`-Xjr:update`), fetching Java and the app's jar, and on macOS writing the `.app` with its icon (`-Xjr:install`). This library does the rest, in Java, once, so every app does not reinvent it:

- `Updater`: the app's own `update` command, done by jr. It runs `<exe> -Xjr:update -Xjr:batch` and reads the one JSON line jr prints.
- `Install`: the app's own `install` command. It copies the running exe to the user's standard place, puts it on PATH, adds shortcuts and (if asked) starts it at sign-in. It writes a receipt so a reinstall replaces exactly what it made and `uninstall` removes exactly that.
- `Places`: the standard per-user folders on each OS (install, config, data, cache, logs, desktop, menu, login items), for code whose authors do not know every OS's conventions.
- `Jr`: whether the app runs under jr, and the exe it was launched from.

No dependencies. Java 25. No administrator rights are ever needed.

## An app's update and install commands

```java
switch (args[0]) {
    case "update" -> {
        var r = Updater.update();               // jr checks the sha256 and swaps the exe, even while it runs
        System.out.println(r.message());
        System.exit(r.ok() ? 0 : 1);
    }
    case "install" -> {
        var r = Install.app("myapp").displayName("My App").description("Does things")
                .desktopShortcut(true).run();
        System.exit(r.ok() ? 0 : 1);
    }
    case "uninstall" -> System.exit(Install.uninstall("myapp").ok() ? 0 : 1);
}
```

An app with nothing to do around an update just delegates, as above. An app that must stop something first (a background process still running the old code) checks, stops it, then updates:

```java
var check = Updater.check();
if (check.status() == UpdateResult.Status.NEWER) {
    stopBackgroundProcesses();
    var r = Updater.update();
}
```

When the app is not running under jr (from an IDE, or `java -jar`), `Updater` returns `UNAVAILABLE` and `Install` returns a problem saying so. Neither throws.

## What install does on each OS

- **Windows:** copies the exe to `%LOCALAPPDATA%\Programs\<app>\<app>.exe` (a running copy there is renamed to `.jr-replaced`, which jr deletes on a later launch). It puts that folder first on the user PATH, keeping the value's registry type so `%VARIABLES%` survive, and tells Explorer, so new terminals see it without signing out. It writes a Start Menu shortcut, a Desktop shortcut if asked, and a `HKCU\...\Run` entry if asked.
- **macOS:** has jr write the app (`<exe> -Xjr:install`): `~/Applications/<Name>.app` from the Info.plist and icon the binary carries, and `~/.local/bin/<app>` linking to the binary inside it, so the same file is the app in Finder, Launchpad and the Dock, and the command in a terminal. Adds `~/.local/bin` to PATH in `~/.zprofile` if no shell has it. A Desktop link and a LaunchAgent if asked.
- **Linux:** copies the binary to `~/.local/bin`, adds that to PATH in `~/.profile` if needed, and writes `.desktop` entries for the menu, the Desktop and autostart as asked.

## Native access

On Windows the registry, known folders and shortcuts are reached through `java.lang.foreign`. Give the app `--enable-native-access=ALL-UNNAMED` (or its module name) in its jr config's `vm.args`, or the JVM prints a warning the first time.

## Building apps that use it

Build the exe with [jr-maven-plugin](https://github.com/jarrunner/jr-maven-plugin), giving it `update.url` and `app.version`, and publish the update file it writes with each release. See jr's docs for the update file and for building Windows and macOS outputs.

## Version

`1.0-SNAPSHOT`, published to the Central snapshot repository (`https://central.sonatype.com/repository/maven-snapshots/`).
