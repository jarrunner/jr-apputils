<p align="center"><img src="https://raw.githubusercontent.com/jarrunner/jr/main/icon/jr-icon.svg" width="96" alt="jr"></p>

# jr-apputils

Give your Java app its own `install` and `update` commands, with no installer. Your user downloads one file, runs `myapp install`, and later `myapp update`.

- **`install`** puts the app in the user's standard place, on PATH, with shortcuts, on Windows, macOS and Linux. No administrator rights.
- **`update`** has [jr](https://github.com/jarrunner/jr) check the next version's SHA-256 and swap the exe, even while it runs.
- **A polite update notice** at startup, checked at most once a day, without waiting on the network.
- **No dependencies.** Java 25. Everything returns a result; nothing throws when the app runs outside jr.

**Try it:** it is on Maven Central as `io.github.jarrunner:jr-apputils:1.1`:

```xml
<dependency>
  <groupId>io.github.jarrunner</groupId>
  <artifactId>jr-apputils</artifactId>
  <version>1.1</version>
</dependency>
```

and add the `switch` from [An app's update and install commands](#an-apps-update-and-install-commands) to your `main`. Build the exe with [jr-maven-plugin](https://github.com/jarrunner/jr-maven-plugin).

[jr](https://github.com/jarrunner/jr) · [jr-maven-plugin](https://github.com/jarrunner/jr-maven-plugin) · [The app convention](https://github.com/jarrunner/jr/blob/main/docs/apps.md) · [jarrunner.github.io](https://jarrunner.github.io)

## What it is

The library for an app launched by [jr](https://github.com/jarrunner/jr): `io.github.jarrunner:jr-apputils`, package `jarrunner.jr.apputils`. One file is the whole app: the user downloads it, runs `myapp install`, and later `myapp update`. No installer, no script. (It replaces two earlier libraries, jr-runtime and updateutils, merged on 2026-10-11.)

jr already does the parts that must happen outside Java: replacing the running exe safely (`-Xjr:update`), fetching Java and the app's jar, and on macOS writing the `.app` with its icon (`-Xjr:install`). This library does the rest, in Java, once, so every app does not reinvent it:

- `JrApp`: what jr told the app about itself. jr passes every value of the app's config as a system property `io.github.jarrunner.jr.<path>` (`app.id`, `app.version`, `update.url`, `jvm.mode`, ...) plus `exe`, its own path. `JrApp` gives them names: `launchedByJr()`, `exe()`, `exeName()`, `appBundle()` (macOS), `appId()`, `appVersion()`, `updateUrl()`, `updateChannel()`, `config("any.path")`, `configList("app.args")`. All empty when the app runs without jr.
- `Updater`: the app's own `update` command, done by jr. It runs `<exe> -Xjr:update -Xjr:batch` and reads the one JSON line jr prints. Also `checkAtMostEvery`, `inBackground` and `notice` for telling the user at startup. Every decision is jr's, so the update rules live in one place.
- `Install`: the app's own `install` command. It copies the running exe to the user's standard place, puts it on PATH, adds shortcuts and (if asked) starts it at sign-in. It writes a receipt so a reinstall replaces exactly what it made and `uninstall` removes exactly that.
- `Places`: the standard per-user folders on each OS (install, config, data, cache, logs, desktop, menu, login items), for code whose authors do not know every OS's conventions.

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

## Telling the user a new version is out

At startup, without waiting on the network, and at most once a day (the result is kept in `~/.jr/update-check/`):

```java
Updater.inBackground(Duration.ofDays(1), r -> {
    var line = Updater.notice(r, "update");     // "myapp 1.1 is available (this is 1.0): run myapp update"
    if (line != null) System.err.println(line);  // stderr: stdout may be data
});
```

A window app shows the same result its own way, in a status line or its about box.

## What install does on each OS

- **Windows:** copies the exe to `%LOCALAPPDATA%\Programs\<app>\<app>.exe` (a running copy there is renamed to `.jr-replaced`, which jr deletes on a later launch). It puts that folder first on the user PATH, keeping the value's registry type so `%VARIABLES%` survive, and tells Explorer, so new terminals see it without signing out. It writes a Start Menu shortcut, a Desktop shortcut if asked, and a `HKCU\...\Run` entry if asked.
- **macOS:** has jr write the app (`<exe> -Xjr:install`): `~/Applications/<Name>.app` from the Info.plist and icon the binary carries, and `~/.local/bin/<app>` linking to the binary inside it, so the same file is the app in Finder, Launchpad and the Dock, and the command in a terminal. Adds `~/.local/bin` to PATH in `~/.zprofile` if no shell has it. A Desktop link and a LaunchAgent if asked.
- **Linux:** copies the binary to `~/.local/bin`, adds that to PATH in `~/.profile` if needed, and writes `.desktop` entries for the menu, the Desktop and autostart as asked.

Tested by hand with the round trips in `tests/`: `RoundTrip.java` on Windows 11, and `RoundTripMac.java` on macOS 26 (Apple silicon) in a scratch home, with a jr-maven-plugin-built binary. Linux has not been run yet.

## Native access

On Windows the registry, known folders and shortcuts are reached through `java.lang.foreign`. Give the app `--enable-native-access=ALL-UNNAMED` (or its module name) in its jr config's `vm.args`, or the JVM prints a warning the first time.

## Building apps that use it

Build the exe with [jr-maven-plugin](https://github.com/jarrunner/jr-maven-plugin), giving it `update.url` and `app.version`, and publish the update file it writes with each release. See jr's docs for the update file and for building Windows and macOS outputs.

## Version

`1.1`, on Maven Central (`io.github.jarrunner:jr-apputils:1.1`; 1.1 installs on a fresh Windows profile, which 1.0 could not, and adds the PATH line for Mac users whose login shell is bash). Work in progress between releases is published as `-SNAPSHOT` versions to the Central snapshot repository (`https://central.sonatype.com/repository/maven-snapshots/`).
