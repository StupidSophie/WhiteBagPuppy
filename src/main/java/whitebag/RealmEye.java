package whitebag;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;

/** Opens an item's (or dungeon's) page on the RealmEye wiki in your browser. */
final class RealmEye {
    private RealmEye() {
    }

    /** "Pirate King's Cutlass" -> https://www.realmeye.com/wiki/pirate-king-s-cutlass */
    static String url(String name) {
        String n = name == null ? "" : name.replace(" (Shiny)", "").replace("’", "'").trim().toLowerCase(Locale.ROOT);
        String slug = n.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return "https://www.realmeye.com/wiki/" + slug;
    }

    /** Opens the page (in the background, so a slow browser doesn't freeze the pup). */
    static void open(String name) {
        if (name == null || name.trim().isEmpty() || name.startsWith("Item #")) return;
        String u = url(name);
        Thread t = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(u));
                    return;
                }
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                new ProcessBuilder(os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", u}
                        : os.contains("mac") ? new String[]{"open", u} : new String[]{"xdg-open", u}).start();
            } catch (Exception e) {
                System.out.println("Couldn't open " + u + ": " + e.getMessage());
            }
        }, "realmeye");
        t.setDaemon(true);
        t.start();
    }

    static final String HINT = "Click for RealmEye";
}
