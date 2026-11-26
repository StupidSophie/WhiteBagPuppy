package whitebag;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * "Open White Bag Puppy when Windows starts": a small script in the Windows Startup folder that opens the pup (with
 * no console window) when you sign in. Turning it off deletes the script. Windows only.
 */
final class WinStartup {
    private WinStartup() {
    }

    private static final String NAME = "White Bag Puppy.vbs";

    static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && folder() != null && Crash.jarFolder() != null;
    }

    /** The Startup folder (%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup), or null. */
    private static File folder() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return null;
        return new File(appData, "Microsoft\\Windows\\Start Menu\\Programs\\Startup");
    }

    static boolean isOn() {
        File f = folder();
        return f != null && new File(f, NAME).isFile();
    }

    /** Turns it on or off; returns an error message, or null when it worked. */
    static String set(boolean on) {
        File dir = folder();
        if (dir == null || Crash.jarFolder() == null) return "I can't find the Windows Startup folder.";
        File script = new File(dir, NAME);
        try {
            if (!on) {
                Files.deleteIfExists(script.toPath());
                return null;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) return "I can't make the Windows Startup folder.";
            // UTF-16 with its byte-order mark, so folders with any letters in their names work
            byte[] body = script().getBytes(StandardCharsets.UTF_16LE);
            byte[] out = new byte[body.length + 2];
            out[0] = (byte) 0xFF;
            out[1] = (byte) 0xFE;
            System.arraycopy(body, 0, out, 2, body.length);
            Files.write(script.toPath(), out);
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /** At start-up: if it's on, point it at where the pup is now (in case its folder moved). */
    static void refresh() {
        if (supported() && isOn()) set(true);
    }

    /** The Java the pup is running on (the one White Bag Puppy.exe installed), or plain javaw. */
    private static String javaw() {
        File w = new File(new File(System.getProperty("java.home", ""), "bin"), "javaw.exe");
        return w.isFile() ? "\"\"" + w.getAbsolutePath().replace("\"", "\"\"") + "\"\"" : "javaw";
    }

    /** VBScript: open the pup's start file from its own folder, with no console window. */
    private static String script() {
        File dir = Crash.jarFolder();
        String d = dir.getAbsolutePath().replace("\"", "\"\"");
        File bat = new File(dir, "Start White Bag Puppy.bat");
        String run = bat.isFile()
                ? "\"\"\"" + bat.getAbsolutePath().replace("\"", "\"\"") + "\"\"\""
                : "\"" + javaw() + " -jar \"\"" + new File(dir, "WhiteBagPuppy.jar").getAbsolutePath().replace("\"", "\"\"") + "\"\"\"";
        return "' Opens White Bag Puppy when Windows starts. Turn it off in the pup (Home), or delete this file.\r\n"
                + "Set sh = CreateObject(\"WScript.Shell\")\r\n"
                + "sh.CurrentDirectory = \"" + d + "\"\r\n"
                + "sh.Run " + run + ", 0, False\r\n";
    }
}
