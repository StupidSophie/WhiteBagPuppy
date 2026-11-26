package whitebag;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Posts for Biscuit that couldn't go through (Biscuit restarting or deploying): kept in the "outbox" folder and sent
 * when Biscuit answers again, oldest first. Kept for two days, and between pup restarts.
 */
final class Outbox {
    private Outbox() {
    }

    private static final File DIR = new File(System.getProperty("puppy.outbox", "outbox"));
    private static final long RETRY_MS = Long.getLong("puppy.outboxRetryMs", 60_000L);
    private static final long KEEP_MS = 48L * 3600_000L;
    private static volatile DiscordWebhook.Log log = m -> { };
    private static Thread worker;

    /** Keeps a post to send later. */
    static synchronized void hold(String url, String contentType, byte[] body, String what) {
        try {
            if (!DIR.isDirectory() && !DIR.mkdirs()) return;
            String name = System.currentTimeMillis() + "-" + Long.toHexString(System.nanoTime() & 0xffffff);
            Files.write(new File(DIR, name + ".body").toPath(), body);
            String meta = url + "\n" + contentType + "\n" + what.replace('\n', ' ') + "\n";
            Files.write(new File(DIR, name + ".meta").toPath(), meta.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.log("*whimper* couldn't keep the " + what + " for later: " + e.getMessage());
            return;
        }
        start(null);
    }

    static int waiting() {
        String[] m = DIR.list((d, n) -> n.endsWith(".meta"));
        return m == null ? 0 : m.length;
    }

    /** Starts sending what's waiting (also at start-up, for posts kept last time). */
    static synchronized void start(DiscordWebhook.Log l) {
        if (l != null) log = l;
        if (worker != null && worker.isAlive()) return;
        if (waiting() == 0) return;
        worker = new Thread(Outbox::run, "outbox");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run() {
        while (true) {
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!sendAll()) continue;
            synchronized (Outbox.class) {
                if (waiting() == 0) {
                    worker = null;
                    return;
                }
            }
        }
    }

    /** Tries everything waiting, oldest first; false if Biscuit still isn't answering. */
    static boolean sendAll() {
        File[] metas = DIR.listFiles((d, n) -> n.endsWith(".meta"));
        if (metas == null) return true;
        Arrays.sort(metas, (a, b) -> a.getName().compareTo(b.getName()));
        for (File meta : metas) {
            String base = meta.getName().substring(0, meta.getName().length() - 5);
            File bodyFile = new File(DIR, base + ".body");
            try {
                String[] m = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8).split("\n");
                long made = Long.parseLong(base.split("-")[0]);
                String what = m.length > 2 ? m[2] : "post";
                if (System.currentTimeMillis() - made > KEEP_MS) {
                    log.log("*sad ears* gave up on the " + what + " from two days ago: Biscuit never answered.");
                    delete(meta, bodyFile);
                    continue;
                }
                int code = send(m[0], m[1], Files.readAllBytes(bodyFile.toPath()));
                if (code >= 200 && code < 300) {
                    log.log("*finally drops it at Biscuit's feet* sent the " + what + " I was holding! *proud tail wag*");
                    delete(meta, bodyFile);
                } else if (code >= 500 || code == 429 || code < 0) {
                    return false; // still down (or busy): try again later, in order
                } else {
                    log.log("*whimper* Biscuit wouldn't take the " + what + " I was holding (HTTP " + code + "), so I let it go.");
                    delete(meta, bodyFile);
                }
            } catch (Exception e) {
                delete(meta, bodyFile); // unreadable: don't get stuck on it
            }
        }
        return true;
    }

    private static void delete(File... fs) {
        for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** The answer's HTTP code, or -1 if Biscuit couldn't be reached. */
    private static int send(String url, String contentType, byte[] body) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(10_000);
            con.setReadTimeout(30_000);
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(body.length);
            con.setRequestProperty("Content-Type", contentType);
            con.setRequestProperty("User-Agent", "WhiteBagPuppy/" + UpdateCheck.VERSION);
            try (OutputStream os = con.getOutputStream()) {
                os.write(body);
            }
            int code = con.getResponseCode();
            try (InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream()) {
                if (in != null) while (in.read() >= 0) { /* drain */ }
            } catch (Exception ignored) {
            }
            return code;
        } catch (Exception e) {
            return -1;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
