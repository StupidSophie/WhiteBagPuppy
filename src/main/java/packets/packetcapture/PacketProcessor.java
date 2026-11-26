package packets.packetcapture;

import packets.Packet;
import packets.PacketType;
import packets.incoming.ip.IpAddress;
import packets.packetcapture.encryption.RC4;
import packets.packetcapture.encryption.RotMGRC4Keys;
import packets.packetcapture.logger.PacketLogger;
import packets.packetcapture.pconstructor.PacketConstructor;
import packets.packetcapture.register.Register;
import packets.packetcapture.sniff.PProcessor;
import packets.packetcapture.sniff.Sniffer;
import packets.reader.BufferReader;
import packets.packetcapture.sniff.gui.MissingNpcapGUI;
import util.Util;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The core class to process packets. First the network tap is sniffed to receive all packets. The packets
 * are filtered for port 2050, the rotmg port, and TCP packets. Then the packets are stitched together in
 * streamConstructor and rotmgConstructor class. After the packets are constructed the RC4 cipher is used
 * decrypt the data. The data is then matched with target classes and emitted through the registry.
 */
public class PacketProcessor extends Thread implements PProcessor {
    private final PacketConstructor incomingPacketConstructor;
    private final PacketConstructor outgoingPacketConstructor;
    private final Sniffer sniffer;
    private final PacketLogger logger;
    private final byte[] srcAddr;
    /** White Bag Puppy: incoming/outgoing constructors per game connection. */
    private final java.util.Map<Integer, PacketConstructor[]> connections = new java.util.HashMap<>();

    /**
     * Basic constructor of packetProcessor
     * TODO: Add linux and mac support later
     */
    public PacketProcessor() {
        sniffer = new Sniffer(this);
        incomingPacketConstructor = new PacketConstructor(this, new RC4(RotMGRC4Keys.INCOMING_STRING));
        outgoingPacketConstructor = new PacketConstructor(this, new RC4(RotMGRC4Keys.OUTGOING_STRING));
        logger = new PacketLogger();
        srcAddr = new byte[4];
    }

    /**
     * Start method for PacketProcessor.
     */
    public void run() {
        tapPackets();
    }

    /**
     * Stop method for PacketProcessor.
     */
    public void stopSniffer() {
        sniffer.closeSniffers();
    }

    /**
     * Method to start the packet sniffer that will send packets back to receivedPackets.
     */
    public void tapPackets() {
        logger.startLogger();
        incomingPacketConstructor.startResets();
        outgoingPacketConstructor.startResets();
        try {
            sniffer.startSniffer();
        } catch (UnsatisfiedLinkError e) {
            new MissingNpcapGUI();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Incoming byte data received from incoming TCP packets.
     *
     * @param data    Incoming byte stream
     * @param srcAddr Source IP of incoming packets.
     */
    @Override
    public void incomingStream(byte[] data, byte[] srcAddr) {
        logger.addIncoming(data.length);
        ipEmitter(srcAddr);
        incomingPacketConstructor.build(data);
        Register.INSTANCE.emitLogs(logger);
    }

    /**
     * Outgoing byte data received from outgoing TCP packets.
     *
     * @param data Outgoing byte stream
     */
    @Override
    public void outgoingStream(byte[] data, byte[] srcAddr) {
        logger.addOutgoing(data.length);
        outgoingPacketConstructor.build(data);
        Register.INSTANCE.emitLogs(logger);
    }

    /**
     * White Bag Puppy: each game connection gets its own stream assembly and RC4 ciphers, so several game
     * clients on one PC don't scramble each other. Packets are tagged with their connection id.
     */
    @Override
    public void connectionStream(int connectionId, boolean incoming, boolean fresh, byte[] data, byte[] srcAddr) {
        PacketConstructor[] pc = connections.get(connectionId);
        if (pc == null) {
            pc = new PacketConstructor[]{
                    new PacketConstructor(this, new RC4(RotMGRC4Keys.INCOMING_STRING)),
                    new PacketConstructor(this, new RC4(RotMGRC4Keys.OUTGOING_STRING))};
            for (PacketConstructor c : pc) {
                if (fresh) c.startFresh();
                else c.startResets(); // joined mid-stream: same as starting the app mid-game
            }
            connections.put(connectionId, pc);
        }
        ConnectionRouter.setCurrent(connectionId);
        if (incoming) {
            logger.addIncoming(data.length);
            ipEmitter(srcAddr);
            pc[0].build(data);
        } else {
            logger.addOutgoing(data.length);
            pc[1].build(data);
        }
        Register.INSTANCE.emitLogs(logger);
    }

    @Override
    public void connectionReset(int connectionId, boolean incoming) {
        PacketConstructor[] pc = connections.get(connectionId);
        if (pc != null) pc[incoming ? 0 : 1].reset();
    }

    @Override
    public void connectionClosed(int connectionId) {
        connections.remove(connectionId);
        java.util.ArrayDeque<String> tr = trails.remove(connectionId);
        java.util.function.BiConsumer<Integer, java.util.List<String>> h = onClosedTrail;
        if (h != null && tr != null) {
            try {
                synchronized (tr) {
                    h.accept(connectionId, new java.util.ArrayList<>(tr));
                }
            } catch (Exception ignored) {
            }
        }
        ConnectionRouter.closed(connectionId);
    }

    // ---------------------------------------------------------------- White Bag Puppy: death debugging

    /** The last packet types on each connection: names and sizes only, never what's in them. */
    private static final java.util.Map<Integer, java.util.ArrayDeque<String>> trails = new java.util.concurrent.ConcurrentHashMap<>();
    /** Gets a connection's trail when it closes. */
    public static volatile java.util.function.BiConsumer<Integer, java.util.List<String>> onClosedTrail;
    /** A death message came in that couldn't be read properly. */
    public static volatile Runnable onBadDeath;

    private static void trail(String what) {
        if (onClosedTrail == null) return;
        // logins, reconnects and chat are left out entirely
        if (what.startsWith("HELLO") || what.startsWith("RECONNECT") || what.contains("TEXT")) return;
        java.util.ArrayDeque<String> tr = trails.computeIfAbsent(ConnectionRouter.current(), k -> new java.util.ArrayDeque<>());
        synchronized (tr) {
            tr.addLast(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()) + "  " + what);
            while (tr.size() > 60) tr.removeFirst();
        }
    }

    /**
     * Emits IP changes as incoming packet.
     *
     * @param srcIp Source IP of incoming packets.
     */
    private void ipEmitter(byte[] srcIp) {
        for (int i = 0; i < srcAddr.length; i++) {
            if (srcAddr[i] != srcIp[i]) {
                System.arraycopy(srcIp, 0, srcAddr, 0, srcAddr.length);
                Register.INSTANCE.emitPacketLogs(new IpAddress(srcIp));
                return;
            }
        }
    }

    /**
     * Completed packets constructed by stream and rotmg constructor returned to packet constructor.
     * Decoded by the cipher and sent back to the processor to be emitted to subscribed users.
     *
     * @param type Constructed packet type.
     * @param size size of the packet.
     * @param data Constructed packet data.
     */
    /** White Bag Puppy: when the last game packet came in (0 = none yet), to notice when the game can't be seen. */
    public static volatile long lastPacketAt;

    public void processPackets(int type, int size, ByteBuffer data) {
        lastPacketAt = System.currentTimeMillis();
        if (!PacketType.containsKey(type)) {
            System.err.println("Unknown packet type:" + type + " Data:" + Arrays.toString(data.array()));
            trail("unknown type " + type + " (" + size + " bytes)");
            return;
        }
        logger.addPacket(type, size);
        Packet packetType = PacketType.getPacket(type).factory();
        packetType.setData(data.array());
        BufferReader pData = new BufferReader(data);

        String name = String.valueOf(PacketType.byOrdinal(type));
        try {
            packetType.deserialize(pData);
            if (!pData.isBufferFullyParsed()) {
                pData.printError(packetType);
                trail(name + " " + size + " bytes, NOT fully read (" + pData.getIndex() + "/" + pData.size() + ")");
            } else {
                trail(name + " " + size + " bytes");
            }
        } catch (Exception e) {
            Util.printLogs("Buffer exploded: " + pData.getIndex() + "/" + pData.size());
            debugPackets(type, data.array());
            trail(name + " " + size + " bytes, FAILED to read at " + pData.getIndex() + "/" + pData.size());
            // a death message the game changed the shape of: what was read (who died) is still worth passing on
            if (packetType instanceof packets.incoming.DeathPacket) {
                Runnable w = onBadDeath;
                if (w != null) w.run();
                ConnectionRouter.emit(packetType);
            }
            return;
        }
        ConnectionRouter.emit(packetType);
    }

    /**
     * Helper for debugging packets
     */
    private void debugPackets(int type, byte[] data) {
        Packet packetType = PacketType.getPacket(type).factory();
        Util.printLogs(PacketType.byOrdinal(type) + " " + packetType);
        Util.printLogs(Arrays.toString(data));
    }

    /**
     * Closes the sniffer for shutdown.
     */
    public void closeSniffer() {
        sniffer.closeSniffers();
    }

    @Override
    public void resetIncoming() {
        incomingPacketConstructor.reset();
    }

    @Override
    public void resetOutgoing() {
        outgoingPacketConstructor.reset();
    }
}
