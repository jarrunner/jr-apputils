package jarrunner.updateutils;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** The few Win32 calls installing needs, through FFM (no JNI, no PowerShell): known folders, the user's PATH and Run
 *  key in the registry, the WM_SETTINGCHANGE broadcast that makes Explorer re-read the environment, and .lnk
 *  shortcuts through the shell's IShellLinkW. Loaded only on Windows. Needs --enable-native-access for the app's
 *  module (ALL-UNNAMED on the class path), or the JVM prints a warning on first use. */
final class WinNative {
    private WinNative() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIBS = Arena.ofAuto();
    private static final AddressLayout PTR = ADDRESS;

    /** Folders that may be moved (Desktop to OneDrive, say), so they are asked for, never built from a variable. */
    static final String DESKTOP = "B4BFCC3A-DB2C-424C-B029-7FE99A87C641";
    static final String PROGRAMS = "A77F5D77-2E2B-44C3-A6A2-ABA601054A51";           // Start Menu\Programs
    static final String STARTUP = "B97D20BB-F46A-4C97-BA10-5E3608430854";
    static final String LOCAL_APP_DATA = "F1B32785-6FBA-4FCF-9D55-7B8E7F157091";
    static final String ROAMING_APP_DATA = "3EB685DB-65F9-4CF6-A03A-E3EF65729F3D";
    static final String USER_PROGRAM_FILES = "5CD7AEE2-2219-4A67-B85D-6C9CE15660CB"; // %LOCALAPPDATA%\Programs

    private static final MemorySegment HKEY_CURRENT_USER = MemorySegment.ofAddress(0x80000001L);
    private static final int KEY_READ_WRITE = 0x2001F, REG_SZ = 1, REG_EXPAND_SZ = 2, ERROR_FILE_NOT_FOUND = 2;

    private static MethodHandle fn(String lib, String name, FunctionDescriptor d) {
        var sym = SymbolLookup.libraryLookup(lib, LIBS).find(name).orElseThrow(() -> new IllegalStateException(lib + " has no " + name));
        return LINKER.downcallHandle(sym, d);
    }

    private static final MethodHandle SH_GET_KNOWN_FOLDER_PATH = fn("shell32", "SHGetKnownFolderPath", FunctionDescriptor.of(JAVA_INT, PTR, JAVA_INT, PTR, PTR));
    private static final MethodHandle CO_TASK_MEM_FREE = fn("ole32", "CoTaskMemFree", FunctionDescriptor.ofVoid(PTR));
    private static final MethodHandle CO_INITIALIZE_EX = fn("ole32", "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, PTR, JAVA_INT));
    private static final MethodHandle CO_CREATE_INSTANCE = fn("ole32", "CoCreateInstance", FunctionDescriptor.of(JAVA_INT, PTR, PTR, JAVA_INT, PTR, PTR));
    private static final MethodHandle REG_OPEN_KEY_EX = fn("advapi32", "RegOpenKeyExW", FunctionDescriptor.of(JAVA_INT, PTR, PTR, JAVA_INT, JAVA_INT, PTR));
    private static final MethodHandle REG_CREATE_KEY_EX = fn("advapi32", "RegCreateKeyExW", FunctionDescriptor.of(JAVA_INT, PTR, PTR, JAVA_INT, PTR, JAVA_INT, JAVA_INT, PTR, PTR, PTR));
    private static final MethodHandle REG_QUERY_VALUE_EX = fn("advapi32", "RegQueryValueExW", FunctionDescriptor.of(JAVA_INT, PTR, PTR, PTR, PTR, PTR, PTR));
    private static final MethodHandle REG_SET_VALUE_EX = fn("advapi32", "RegSetValueExW", FunctionDescriptor.of(JAVA_INT, PTR, PTR, JAVA_INT, JAVA_INT, PTR, JAVA_INT));
    private static final MethodHandle REG_DELETE_VALUE = fn("advapi32", "RegDeleteValueW", FunctionDescriptor.of(JAVA_INT, PTR, PTR));
    private static final MethodHandle REG_CLOSE_KEY = fn("advapi32", "RegCloseKey", FunctionDescriptor.of(JAVA_INT, PTR));
    private static final MethodHandle SEND_MESSAGE_TIMEOUT = fn("user32", "SendMessageTimeoutW", FunctionDescriptor.of(JAVA_LONG, PTR, JAVA_INT, JAVA_LONG, PTR, JAVA_INT, JAVA_INT, PTR));

    private static MemorySegment wide(Arena a, String s) {
        return a.allocateFrom(s, StandardCharsets.UTF_16LE);
    }

    private static String readWide(MemorySegment p) {
        return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    /** A GUID written "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX", as the 16 bytes Windows reads. */
    private static MemorySegment guid(Arena a, String g) {
        var h = g.replace("-", "");
        var s = a.allocate(16);
        s.set(JAVA_INT, 0, (int) Long.parseLong(h.substring(0, 8), 16));
        s.set(ValueLayout.JAVA_SHORT, 4, (short) Integer.parseInt(h.substring(8, 12), 16));
        s.set(ValueLayout.JAVA_SHORT, 6, (short) Integer.parseInt(h.substring(12, 16), 16));
        for (var i = 0; i < 8; i++) s.set(ValueLayout.JAVA_BYTE, 8 + i, (byte) Integer.parseInt(h.substring(16 + 2 * i, 18 + 2 * i), 16));
        return s;
    }

    static Path knownFolder(String id) {
        try (var a = Arena.ofConfined()) {
            var out = a.allocate(PTR);
            var hr = (int) SH_GET_KNOWN_FOLDER_PATH.invokeExact(guid(a, id), 0, MemorySegment.NULL, out);
            var p = out.get(PTR, 0);
            try {
                if (hr != 0) throw new IllegalStateException("SHGetKnownFolderPath(" + id + ") failed: 0x" + Integer.toHexString(hr));
                return Path.of(readWide(p));
            } finally {
                CO_TASK_MEM_FREE.invokeExact(p);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** A value under HKEY_CURRENT_USER\key, as stored (an REG_EXPAND_SZ keeps its %VARIABLES%), or null. */
    static String regGet(String key, String name) {
        try (var a = Arena.ofConfined()) {
            var hk = open(a, key, false);
            if (hk == null) return null;
            try {
                var type = a.allocate(JAVA_INT);
                var size = a.allocate(JAVA_INT);
                var rc = (int) REG_QUERY_VALUE_EX.invokeExact(hk, wide(a, name), MemorySegment.NULL, type, MemorySegment.NULL, size);
                if (rc == ERROR_FILE_NOT_FOUND) return null;
                if (rc != 0) throw new IllegalStateException("RegQueryValueExW(" + name + ") failed: " + rc);
                var buf = a.allocate(size.get(JAVA_INT, 0) + 2L);
                rc = (int) REG_QUERY_VALUE_EX.invokeExact(hk, wide(a, name), MemorySegment.NULL, type, buf, size);
                if (rc != 0) throw new IllegalStateException("RegQueryValueExW(" + name + ") failed: " + rc);
                return buf.getString(0, StandardCharsets.UTF_16LE);
            } finally {
                var ignored = (int) REG_CLOSE_KEY.invokeExact(hk);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The registry type of a value: true when it is REG_EXPAND_SZ (and for a value that does not exist yet). */
    static boolean regIsExpand(String key, String name) {
        try (var a = Arena.ofConfined()) {
            var hk = open(a, key, false);
            if (hk == null) return true;
            try {
                var type = a.allocate(JAVA_INT);
                var rc = (int) REG_QUERY_VALUE_EX.invokeExact(hk, wide(a, name), MemorySegment.NULL, type, MemorySegment.NULL, MemorySegment.NULL);
                return rc != 0 || type.get(JAVA_INT, 0) == REG_EXPAND_SZ;
            } finally {
                var ignored = (int) REG_CLOSE_KEY.invokeExact(hk);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Sets a string value under HKEY_CURRENT_USER\key (creating the key), or deletes it when value is null. */
    static void regSet(String key, String name, String value, boolean expand) {
        try (var a = Arena.ofConfined()) {
            var hk = open(a, key, true);
            try {
                int rc;
                if (value == null) {
                    rc = (int) REG_DELETE_VALUE.invokeExact(hk, wide(a, name));
                    if (rc == ERROR_FILE_NOT_FOUND) rc = 0;
                } else {
                    var data = wide(a, value);
                    rc = (int) REG_SET_VALUE_EX.invokeExact(hk, wide(a, name), 0, expand ? REG_EXPAND_SZ : REG_SZ, data, (int) data.byteSize());
                }
                if (rc != 0) throw new IllegalStateException("writing HKCU\\" + key + "\\" + name + " failed: " + rc);
            } finally {
                var ignored = (int) REG_CLOSE_KEY.invokeExact(hk);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static MemorySegment open(Arena a, String key, boolean create) throws Throwable {
        var out = a.allocate(PTR);
        int rc = create
                ? (int) REG_CREATE_KEY_EX.invokeExact(HKEY_CURRENT_USER, wide(a, key), 0, MemorySegment.NULL, 0, KEY_READ_WRITE, MemorySegment.NULL, out, MemorySegment.NULL)
                : (int) REG_OPEN_KEY_EX.invokeExact(HKEY_CURRENT_USER, wide(a, key), 0, KEY_READ_WRITE, out);
        if (rc == ERROR_FILE_NOT_FOUND && !create) return null;
        if (rc != 0) throw new IllegalStateException("opening HKCU\\" + key + " failed: " + rc);
        return out.get(PTR, 0);
    }

    /** Tells Explorer and every other top-level window that the environment changed, so new terminals get the new
     *  PATH without signing out. Windows that do not answer within 5 seconds are skipped. */
    static void broadcastEnvironmentChange() {
        try (var a = Arena.ofConfined()) {
            var result = a.allocate(JAVA_LONG);
            var ignored = (long) SEND_MESSAGE_TIMEOUT.invokeExact(MemorySegment.ofAddress(0xFFFF), 0x1A, 0L, wide(a, "Environment"), 2, 5000, result);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Writes a .lnk: target, arguments, working folder, description and icon (the target's own first icon). */
    static void shortcut(Path lnk, Path target, String arguments, Path workingDir, String description) {
        try (var a = Arena.ofConfined()) {
            var ignored = (int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, 2); // apartment; S_FALSE or RPC_E_CHANGED_MODE are fine
            var out = a.allocate(PTR);
            check((int) CO_CREATE_INSTANCE.invokeExact(guid(a, "00021401-0000-0000-C000-000000000046"), MemorySegment.NULL, 1,
                    guid(a, "000214F9-0000-0000-C000-000000000046"), out), "CoCreateInstance(ShellLink)");
            var link = out.get(PTR, 0);
            try {
                check(call(link, 20, a, wide(a, target.toString())), "IShellLinkW.SetPath");
                check(call(link, 11, a, wide(a, arguments == null ? "" : arguments)), "IShellLinkW.SetArguments");
                if (workingDir != null) check(call(link, 9, a, wide(a, workingDir.toString())), "IShellLinkW.SetWorkingDirectory");
                if (description != null) check(call(link, 7, a, wide(a, description)), "IShellLinkW.SetDescription");
                check(callIcon(link, wide(a, target.toString())), "IShellLinkW.SetIconLocation");
                var pf = a.allocate(PTR);
                check(callQi(link, guid(a, "0000010b-0000-0000-C000-000000000046"), pf), "QueryInterface(IPersistFile)");
                var persist = pf.get(PTR, 0);
                try {
                    check(callSave(persist, wide(a, lnk.toString())), "IPersistFile.Save");
                } finally {
                    release(persist);
                }
            } finally {
                release(link);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " failed: 0x" + Integer.toHexString(hr));
    }

    private static MemorySegment method(MemorySegment obj, int index) {
        var vtable = obj.reinterpret(PTR.byteSize()).get(PTR, 0);
        return vtable.reinterpret((index + 1) * PTR.byteSize()).getAtIndex(PTR, index);
    }

    private static final FunctionDescriptor THIS_STR = FunctionDescriptor.of(JAVA_INT, PTR, PTR);

    private static int call(MemorySegment obj, int index, Arena a, MemorySegment wideArg) throws Throwable {
        return (int) LINKER.downcallHandle(method(obj, index), THIS_STR).invokeExact(obj, wideArg);
    }

    private static int callIcon(MemorySegment link, MemorySegment path) throws Throwable {
        return (int) LINKER.downcallHandle(method(link, 17), FunctionDescriptor.of(JAVA_INT, PTR, PTR, JAVA_INT)).invokeExact(link, path, 0);
    }

    private static int callQi(MemorySegment obj, MemorySegment iid, MemorySegment out) throws Throwable {
        return (int) LINKER.downcallHandle(method(obj, 0), FunctionDescriptor.of(JAVA_INT, PTR, PTR, PTR)).invokeExact(obj, iid, out);
    }

    private static int callSave(MemorySegment persist, MemorySegment file) throws Throwable {
        return (int) LINKER.downcallHandle(method(persist, 6), FunctionDescriptor.of(JAVA_INT, PTR, PTR, JAVA_INT)).invokeExact(persist, file, 1);
    }

    private static void release(MemorySegment obj) throws Throwable {
        var ignored = (int) LINKER.downcallHandle(method(obj, 2), FunctionDescriptor.of(JAVA_INT, PTR)).invokeExact(obj);
    }
}
