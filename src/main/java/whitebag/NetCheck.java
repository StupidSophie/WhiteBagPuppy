package whitebag;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * "I can't see the game": works out why, using Windows' own lists of programs and connections (tasklist and
 * netstat). Never looks at what the game sends. Finds where the game is connected (address and port), and whether a
 * VPN or game booster is running, which can hide the game's traffic from the pup.
 */
final class NetCheck {
    private NetCheck() {
    }

    /** Programs that route the game's traffic somewhere the pup can't see it. */
    private static final String[][] TUNNELS = {
            {"exitlag", "ExitLag"}, {"noping", "NoPing"}, {"warp-svc", "Cloudflare WARP"}, {"openvpn", "OpenVPN"},
            {"wireguard", "WireGuard"}, {"nordvpn", "NordVPN"}, {"expressvpn", "ExpressVPN"}, {"protonvpn", "Proton VPN"},
            {"surfshark", "Surfshark"}, {"mullvad", "Mullvad"}, {"windscribe", "Windscribe"}, {"pia-service", "Private Internet Access"},
            {"hotspotshield", "Hotspot Shield"}, {"cyberghost", "CyberGhost"}, {"gearup", "GearUP Booster"}, {"wtfast", "WTFast"},
            {"haste", "Haste"}, {"outfox", "Outfox"}, {"mudfish", "Mudfish"}, {"lagofast", "LagoFast"}, {"gpn", "GPN"}};

    /** The check's findings, one line each, in plain words. */
    static List<String> run() {
        List<String> out = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            out.add("this check only works on Windows.");
            return out;
        }
        // the game's process (or processes)
        Set<String> pids = new LinkedHashSet<>();
        Set<String> tunnels = new LinkedHashSet<>();
        for (String l : lines("tasklist", "/FO", "CSV", "/NH")) {
            String[] f = l.split("\",\"");
            if (f.length < 2) continue;
            String exe = f[0].replace("\"", "").toLowerCase(Locale.ROOT);
            if (exe.startsWith("rotmg exalt") || exe.startsWith("rotmgexalt")) pids.add(f[1].replace("\"", "").trim());
            for (String[] t : TUNNELS) if (exe.contains(t[0])) tunnels.add(t[1]);
        }
        if (pids.isEmpty()) {
            out.add("I can't find the game running (RotMG Exalt). Start the game and log in, then try again.");
        } else {
            // where it's connected
            Set<String> remotes = new LinkedHashSet<>();
            for (String l : lines("netstat", "-ano", "-p", "TCP")) {
                String[] f = l.trim().split("\\s+");
                if (f.length < 5 || !f[0].equalsIgnoreCase("TCP") || !pids.contains(f[4])) continue;
                if (!f[3].toUpperCase(Locale.ROOT).startsWith("ESTAB")) continue;
                String remote = f[2];
                if (remote.startsWith("127.") || remote.startsWith("[::1]")) continue;
                remotes.add(remote);
            }
            boolean on2050 = false;
            // web connections (logins, news, analytics) use 443 and 80; anything else could be a game server
            Set<String> other = new LinkedHashSet<>();
            for (String r : remotes) {
                if (r.endsWith(":2050")) on2050 = true;
                else if (!r.endsWith(":443") && !r.endsWith(":80")) other.add(r);
            }
            if (on2050) out.add("the game is connected to a server on the usual port (" + first(remotes, ":2050")
                    + "), but none of your network adapters is showing me that traffic.");
            else if (!other.isEmpty()) out.add("the game is connected to " + String.join(", ", other) + ", not on the usual port 2050. "
                    + "If you're in game on a character right now, the game may have changed how it connects: send this line to Sophie so the pup can be updated.");
            else out.add("the game is running but isn't connected to a game server right now"
                    + (remotes.isEmpty() ? "" : " (its only connections are to web services, which is normal)")
                    + ". Log in to a character, then press Start watching again.");
            if (on2050 && tunnels.isEmpty())
                out.add("Try: reinstall Npcap from npcap.com with \"WinPcap API-compatible mode\" ticked, restart your PC, then press Start watching again.");
        }
        if (!tunnels.isEmpty())
            out.add(String.join(", ", tunnels) + (tunnels.size() == 1 ? " is" : " are") + " running: it sends the game's traffic through its own tunnel, "
                    + "where the pup can't see it. Turn it off (or exclude RotMG from it), restart the game, then press Start watching again.");
        return out;
    }

    private static String first(Set<String> s, String suffix) {
        for (String r : s) if (r.endsWith(suffix)) return r;
        return s.iterator().next();
    }

    /** A Windows command's output lines (empty if it couldn't run). */
    private static List<String> lines(String... cmd) {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {
                String l;
                while ((l = r.readLine()) != null) out.add(l);
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return out;
    }
}
