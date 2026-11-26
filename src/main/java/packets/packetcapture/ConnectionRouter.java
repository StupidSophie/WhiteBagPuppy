package packets.packetcapture;

import packets.Packet;
import packets.packetcapture.register.Register;

/**
 * White Bag Puppy: each game client has its own connection. Every decoded packet is handed here with the id
 * of the connection it came from, so an app can tell several game clients (accounts) apart. By default it
 * just emits the packet as before.
 */
public final class ConnectionRouter {
    public interface Handler {
        void packet(int connectionId, Packet packet);
    }

    public interface Closed {
        void closed(int connectionId);
    }

    private static volatile Handler handler;
    private static volatile Closed closedHandler;
    private static final ThreadLocal<Integer> CURRENT = ThreadLocal.withInitial(() -> 0);

    private ConnectionRouter() {
    }

    public static void setHandler(Handler h) {
        handler = h;
    }

    public static void setClosedHandler(Closed c) {
        closedHandler = c;
    }

    private static final java.util.Map<Integer, String> IPS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The game server's address for this connection, e.g. "52.23.232.42". */
    static void setIp(int connectionId, byte[] a) {
        if (a == null || a.length < 4) return;
        String ip = (a[0] & 255) + "." + (a[1] & 255) + "." + (a[2] & 255) + "." + (a[3] & 255);
        if (!ip.equals(IPS.get(connectionId))) IPS.put(connectionId, ip);
    }

    /** The game server's address for a connection, or "" if not known. */
    public static String ipOf(int connectionId) {
        String ip = IPS.get(connectionId);
        return ip == null ? "" : ip;
    }

    static void setCurrent(int connectionId) {
        CURRENT.set(connectionId);
    }

    public static int current() {
        return CURRENT.get();
    }

    static void emit(Packet packet) {
        Handler h = handler;
        if (h != null) h.packet(CURRENT.get(), packet);
        else Register.INSTANCE.emitPacketLogs(packet);
    }

    static void closed(int connectionId) {
        Closed c = closedHandler;
        IPS.remove(connectionId);
        if (c != null) c.closed(connectionId);
    }
}
