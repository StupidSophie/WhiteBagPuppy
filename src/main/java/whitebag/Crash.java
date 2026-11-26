package whitebag;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Start-up safety net: instead of the window flashing and vanishing, a crash shows a pop-up with a hint and is
 * written to crash-report.txt. Also makes sure the app runs from its own folder (where its settings live).
 */
final class Crash {
    private Crash() {
    }

    private static volatile String app = "the puppy";
    private static volatile boolean shown, starting = true;

    /** The window is up: later problems are only written down, not popped up. */
    static void started() {
        starting = false;
    }

    /** The folder the .jar is in, or null if it can't be told. */
    static File jarFolder() {
        try {
            File f = new File(Crash.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f.getParentFile() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static File jarFile() {
        try {
            File f = new File(Crash.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * If Windows started us somewhere else (System32, a shortcut's folder...), start again from the jar's own folder
     * so the settings and logs are found. Returns true if a new copy was started (this one should quit).
     */
    static boolean relaunchInOwnFolder(String[] args) {
        if (System.getProperty("puppy.relaunched") != null) return false;
        File jar = jarFile(), dir = jarFolder();
        if (jar == null || dir == null) return false;
        try {
            File here = new File("").getCanonicalFile();
            if (here.equals(dir.getCanonicalFile())) return false;
            String java = new File(new File(System.getProperty("java.home"), "bin"),
                    System.getProperty("os.name", "").toLowerCase().contains("win") ? "javaw.exe" : "java").getPath();
            List<String> cmd = new ArrayList<>();
            cmd.add(new File(java).isFile() ? java : "java");
            cmd.add("-Dpuppy.relaunched=1");
            cmd.add("-jar");
            cmd.add(jar.getPath());
            for (String a : args) cmd.add(a);
            new ProcessBuilder(cmd).directory(dir).start();
            return true;
        } catch (Throwable e) {
            return false; // carry on where we are
        }
    }

    /** Catches anything that goes wrong from here on (start-up, or the window code). */
    static void install(String appName) {
        app = appName;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> report(e, false));
    }

    /** Writes the report and, for a start-up crash (or the first one), shows a pop-up. */
    static void report(Throwable e, boolean fatal) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String hint = hint(e);
        String text = app + " " + version() + " crashed" + (fatal ? " while starting" : "") + ".\n\n" + hint + "\n\n"
                + "Java " + System.getProperty("java.version") + " on " + System.getProperty("os.name")
                + "\nFolder: " + new File("").getAbsolutePath()
                + "\nApp: " + (jarFile() == null ? "?" : jarFile().getPath()) + "\n\n" + sw;
        File saved = save(text);
        e.printStackTrace();
        if (!fatal && (!starting || shown)) return; // pop up for start-up problems, once
        shown = true;
        if (GraphicsEnvironment.isHeadless()) return;
        try {
            JTextArea area = new JTextArea(hint + "\n\n" + (saved != null ? "The details are in " + saved.getPath() + ".\nSend that file (or a screenshot of this) to whoever gave you the puppy.\n\n" : "")
                    + firstLines(sw.toString(), 6), 14, 60);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            JOptionPane.showMessageDialog(null, new JScrollPane(area), app + " had a problem", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
        }
    }

    /** A plain-words guess at what went wrong. */
    static String hint(Throwable e) {
        String all = chain(e).toLowerCase();
        String path = (jarFile() == null ? "" : jarFile().getPath()).toLowerCase();
        if (path.contains("\\temp\\") || path.contains(".zip") || path.contains("/tmp/"))
            return "It looks like it was opened straight from the zip. Right-click the zip, choose Extract All, then open the .jar in the extracted folder.";
        if (all.contains("access is denied") || all.contains("accessdenied") || all.contains("operation not permitted") || all.contains("read-only"))
            return "Windows won't let it save files in this folder. This often happens on the Desktop or in OneDrive (Windows Security's Controlled folder access). Move the whole folder somewhere like C:\\LootPuppy and open it from there.";
        if (all.contains("unsatisfiedlinkerror") || all.contains("npcap") || all.contains("wpcap") || all.contains("jna"))
            return "It couldn't load the network tools it needs. Install Npcap (npcap.com) with \"WinPcap API-compatible mode\" ticked, then try again.";
        if (all.contains("unsupportedclassversion"))
            return "This Java is too old. Install a newer Java (8 or later), then try again.";
        if (all.contains("outofmemory"))
            return "It ran out of memory. Close some programs and try again.";
        return "Something unexpected went wrong.";
    }

    private static String chain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) sb.append(t).append('\n');
        return sb.toString();
    }

    private static String firstLines(String s, int n) {
        String[] l = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, l.length); i++) sb.append(l[i].trim()).append('\n');
        return sb.toString().trim();
    }

    private static File save(String text) {
        List<File> where = new ArrayList<>();
        where.add(new File("crash-report.txt"));
        if (jarFolder() != null) where.add(new File(jarFolder(), "crash-report.txt"));
        where.add(new File(System.getProperty("user.home"), "Desktop" + File.separator + "puppy-crash-report.txt"));
        where.add(new File(System.getProperty("user.home"), "puppy-crash-report.txt"));
        for (File f : where) {
            try {
                Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
                return f.getAbsoluteFile();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String version() {
        try {
            return "Loot Puppy".equals(app) ? LootPuppy.VERSION : UpdateCheck.VERSION;
        } catch (Throwable e) {
            return "";
        }
    }
}
