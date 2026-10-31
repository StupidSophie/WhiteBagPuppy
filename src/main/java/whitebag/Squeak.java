package whitebag;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.Toolkit;
import java.util.Random;

/**
 * Little sounds, generated in code (no sound files): a squeaky-toy "arf!" when a bag drops,
 * pitched a bit differently each time, and a soft "boop" for clicks.
 */
final class Squeak {
    private static final float RATE = 44100f;
    private static final Random RANDOM = new Random();
    /** Turned off from the Home page. */
    static volatile boolean enabled = true;
    /** Bags whose built-in sound is turned off (the boxes under the bags on Home). Your own sound file still plays. */
    static final java.util.Set<BagType> muted = java.util.Collections.synchronizedSet(java.util.EnumSet.noneOf(BagType.class));

    private Squeak() {
    }

    /** Sets of built-in bag sounds to pick from on Home (your own sound files still win). */
    enum Pack {
        SQUEAKY("Squeaky toy"), CHIME("Soft chime"), RETRO("Retro 8-bit"), BARKS("Little barks"),
        MUSIC_BOX("Music box"), BUBBLES("Bubble pops"), MEOWS("Kitty meows"), SPARKLES("Magic sparkles");
        final String label;

        Pack(String label) {
            this.label = label;
        }

        static Pack of(String name) {
            try {
                return valueOf(name == null ? "SQUEAKY" : name.trim());
            } catch (IllegalArgumentException e) {
                return SQUEAKY;
            }
        }
    }

    static volatile Pack pack = Pack.SQUEAKY;
    /** 0 to 100: how loud every sound is (the volume slider on Home). */
    static volatile int volume = 100;

    /** The squeak, somewhere between a bit lower and a bit higher than normal. */
    static void play() {
        if (!enabled) return;
        double pitch = 0.9 + RANDOM.nextDouble() * 0.22;
        playAsync(squeak(pitch), true);
    }

    /**
     * A different squeak for each bag colour, so you can tell what dropped without looking:
     * white = a rising three-squeak fanfare, red = two quick high squeaks, orange = squeak-squeak down,
     * gold = squeak plus a bell, egg basket = tiny chick peeps, blue = one low squeak.
     * Boosted bags add a little sparkle at the end.
     */
    static void play(BagType type) {
        if (!enabled) return;
        if (type == null) {
            play();
            return;
        }
        java.io.File custom = customSound(type);
        if (custom != null && playFile(custom)) return;
        if (muted.contains(type)) return;
        playAsync(bagSound(type), true);
    }

    /** The built-in sound for this bag, even if it's turned off (to preview it). */
    static void playDefault(BagType type) {
        if (!enabled) return;
        playAsync(bagSound(type), true);
    }

    /** A realm event: your own sound (cube-god.wav, else event.wav), or a little two-bell chime and a squeak. */
    static void playEvent(String eventName) {
        playEvent(eventName, "event");
    }

    /**
     * An event from the Events tab: sounds/&lt;its name&gt;.wav (like cube-god.wav or the-shatters.wav), else
     * sounds/&lt;kind&gt;.wav (event.wav, key-pop.wav or realm-closed.wav), else sounds/event.wav, else the built-in chime.
     */
    static void playEvent(String eventName, String kind) {
        if (!enabled) return;
        String slug = slug(eventName);
        for (String n : new String[]{slug, kind, "event"}) {
            for (String ext : EXTENSIONS) {
                java.io.File f = new java.io.File(SOUND_DIR, n + ext);
                if (f.isFile() && playFile(f)) return;
            }
        }
        playAsync(pcm(bell(1318, 0.28), bell(1760, 0.32), silence(0.04), squeakPcm(1.05)), true);
    }

    /** Someone said your name or one of your words in chat: sounds/chat-ping.wav, else a quick two-note ding. */
    static void playPing() {
        if (!enabled) return;
        for (String ext : EXTENSIONS) {
            java.io.File f = new java.io.File(SOUND_DIR, "chat-ping" + ext);
            if (f.isFile() && playFile(f)) return;
        }
        playAsync(pcm(bell(1568, 0.12), bell(2093, 0.3)), true);
    }

    /** "Oryx's Sanctuary" -> "oryxs-sanctuary", used for sound file names. */
    static String slug(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace("'", "").replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    // ---------------------------------------------------------------- your own sounds

    static final java.io.File SOUND_DIR = new java.io.File("sounds");
    private static final String[] EXTENSIONS = {".wav", ".aif", ".aiff", ".au"};

    /** File name (without extension) for a bag's own sound, e.g. "white" or "boosted-white". */
    static String soundName(BagType type) {
        BagType colour = LootStats.colour(type);
        String base = colour == BagType.EGG ? "egg" : colour.name().toLowerCase(java.util.Locale.ROOT);
        return colour == type ? base : "boosted-" + base;
    }

    /** Your own sound for this bag from the sounds folder: boosted-white.wav, else white.wav, and so on. */
    static java.io.File customSound(BagType type) {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(soundName(type));
        if (LootStats.colour(type) != type) names.add(soundName(LootStats.colour(type)));
        for (String n : names) {
            for (String ext : EXTENSIONS) {
                java.io.File f = new java.io.File(SOUND_DIR, n + ext);
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    /** Plays a sound file; returns false if it can't be read (then the built-in sound plays instead). */
    private static boolean playFile(java.io.File f) {
        try {
            javax.sound.sampled.AudioInputStream in = AudioSystem.getAudioInputStream(f);
            javax.sound.sampled.Clip clip = AudioSystem.getClip();
            clip.addLineListener(e -> {
                if (e.getType() == javax.sound.sampled.LineEvent.Type.STOP) clip.close();
            });
            clip.open(in);
            if (volume <= 0) {
                clip.close();
                return true;
            }
            if (volume < 100 && clip.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
                javax.sound.sampled.FloatControl gain = (javax.sound.sampled.FloatControl) clip.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
                double k = Math.pow(volume / 100.0, 2);
                gain.setValue((float) Math.max(gain.getMinimum(), 20 * Math.log10(k)));
            }
            clip.start();
            return true;
        } catch (Throwable e) {
            System.out.println("Couldn't play " + f + ": " + e.getMessage());
            return false;
        }
    }

    /** Makes the sounds folder with a note explaining how to use it, the first time the app runs. */
    static void prepareSoundFolder() {
        try {
            if (!SOUND_DIR.isDirectory() && !SOUND_DIR.mkdirs()) return;
            java.io.File readme = new java.io.File(SOUND_DIR, "README.txt");
            // rewrite our own note when it's from an older version (it's ours: it starts with this line)
            if (readme.exists()) {
                String old = new String(java.nio.file.Files.readAllBytes(readme.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (old.contains("key-pop.wav") || !old.startsWith("Put your own bag sounds in this folder")) return;
            }
            String text = "Put your own bag sounds in this folder and the pup plays them instead of its squeaks.\r\n\r\n"
                    + "Name each file after the bag colour:\r\n"
                    + "  white.wav, red.wav, orange.wav, gold.wav, egg.wav, blue.wav, cyan.wav\r\n\r\n"
                    + "Boosted bags use the same sound, unless you add one just for them:\r\n"
                    + "  boosted-white.wav, boosted-red.wav, boosted-orange.wav, boosted-gold.wav, boosted-egg.wav, boosted-blue.wav, boosted-cyan.wav\r\n\r\n"
                    + "Events tab: event.wav for realm events, key-pop.wav for key pops, realm-closed.wav for closing realms,\r\n"
                    + "or one file per event or dungeon named after it, like cube-god.wav or the-shatters.wav.\r\n\r\n"
                    + "Use .wav files (.aiff and .au work too). MP3 doesn't work, so convert it to .wav first,\r\n"
                    + "for example with Audacity or an online converter. Keep them short, a second or two.\r\n"
                    + "Changes work straight away, no restart needed. Turn a bag on in Bags to Watch to hear it.\r\n";
            java.nio.file.Files.write(readme.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static byte[] bagSound(BagType type) {
        return bagSound(type, pack);
    }

    static byte[] bagSound(BagType type, Pack p) {
        BagType colour = LootStats.colour(type);
        java.util.List<short[]> parts = new java.util.ArrayList<>();
        switch (p) {
            case CHIME: chimes(colour, parts); break;
            case RETRO: retro(colour, parts); break;
            case BARKS: barks(colour, parts); break;
            case MUSIC_BOX: musicBox(colour, parts); break;
            case BUBBLES: bubbles(colour, parts); break;
            case MEOWS: meows(colour, parts); break;
            case SPARKLES: sparkles(colour, parts); break;
            default: squeaks(colour, parts);
        }
        if (colour != type) { // boosted
            parts.add(silence(0.03));
            if (p == Pack.RETRO) {
                for (double f : new double[]{1568, 2093, 2637}) parts.add(square(f, 0.05, 0.16));
            } else {
                parts.add(bell(3136, 0.22));
                parts.add(bell(3951, 0.25));
            }
        }
        return pcm(parts.toArray(new short[0][]));
    }

    /** Soft chime: little bells, a tune per colour. */
    private static void chimes(BagType colour, java.util.List<short[]> parts) {
        double[] notes;
        switch (colour) {
            case WHITE: notes = new double[]{1047, 1319, 1568, 2093}; break;
            case RED: notes = new double[]{1568, 1568}; break;
            case ORANGE: notes = new double[]{1319, 1047}; break;
            case GOLD: notes = new double[]{2093, 2637, 3136}; break;
            case EGG: notes = new double[]{2637, 2794, 3136}; break;
            case CYAN: notes = new double[]{1175, 1760}; break;
            default: notes = new double[]{880}; // blue
        }
        for (int i = 0; i < notes.length; i++) {
            boolean last = i == notes.length - 1;
            parts.add(bell(notes[i], last ? 0.5 : 0.13));
        }
    }

    /** Retro 8-bit: square-wave blips like an old game. */
    private static void retro(BagType colour, java.util.List<short[]> parts) {
        double[] notes;
        double len = 0.07;
        switch (colour) {
            case WHITE: notes = new double[]{523, 659, 784, 1047, 1319}; break;
            case RED: notes = new double[]{988, 0, 988}; break;
            case ORANGE: notes = new double[]{784, 587}; len = 0.09; break;
            case GOLD: notes = new double[]{988, 1319}; len = 0.1; break;
            case EGG: notes = new double[]{1760, 1976, 2093}; len = 0.05; break;
            case CYAN: notes = new double[]{659, 880}; break;
            default: notes = new double[]{330}; len = 0.16; // blue
        }
        for (int i = 0; i < notes.length; i++) {
            boolean last = i == notes.length - 1;
            parts.add(notes[i] == 0 ? silence(0.04) : square(notes[i], last ? len * 2.2 : len, 0.17));
        }
    }

    /** Little barks: yips for small bags, a woof for blue. */
    private static void barks(BagType colour, java.util.List<short[]> parts) {
        switch (colour) {
            case WHITE:
                parts.add(bark(1.0)); parts.add(silence(0.07));
                parts.add(bark(1.15)); parts.add(silence(0.07));
                parts.add(bark(1.35));
                break;
            case RED:
                parts.add(bark(1.25)); parts.add(silence(0.05)); parts.add(bark(1.25));
                break;
            case ORANGE:
                parts.add(bark(1.15)); parts.add(silence(0.06)); parts.add(bark(0.95));
                break;
            case GOLD:
                parts.add(bark(1.1)); parts.add(bell(1760, 0.35));
                break;
            case EGG:
                for (int i = 0; i < 3; i++) {
                    parts.add(bark(1.8 + i * 0.12));
                    parts.add(silence(0.03));
                }
                break;
            case CYAN:
                parts.add(bark(1.05)); parts.add(chirp(0.08, 2400, 3200, 0.25));
                break;
            default:
                parts.add(bark(0.62)); // blue: one low woof
        }
    }

    /** Music box: plucked tines, a little tune per colour. */
    private static void musicBox(BagType colour, java.util.List<short[]> parts) {
        double[] notes;
        switch (colour) {
            case WHITE: notes = new double[]{1568, 1976, 2349, 3136, 2349, 3136}; break;
            case RED: notes = new double[]{2093, 2093, 2637}; break;
            case ORANGE: notes = new double[]{2349, 1976, 1568}; break;
            case GOLD: notes = new double[]{1568, 2093, 2637, 3136}; break;
            case EGG: notes = new double[]{3136, 3520, 3951}; break;
            case CYAN: notes = new double[]{1760, 2217}; break;
            default: notes = new double[]{1175, 1568}; // blue
        }
        for (int i = 0; i < notes.length; i++) parts.add(tine(notes[i], i == notes.length - 1 ? 0.6 : 0.12));
    }

    /** One music box tine: bright pluck, quick decay. */
    private static short[] tine(double freq, double seconds) {
        int n = (int) (RATE * seconds);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / RATE;
            double env = Math.min(1, i / (RATE * 0.002)) * Math.exp(-t * 6);
            double v = Math.sin(2 * Math.PI * freq * t) + 0.25 * Math.sin(2 * Math.PI * freq * 4 * t) * Math.exp(-t * 30);
            s[i] = (short) (v / 1.25 * env * 0.3 * Short.MAX_VALUE);
        }
        return s;
    }

    /** Bubble pops: little plops that slide up. */
    private static void bubbles(BagType colour, java.util.List<short[]> parts) {
        double[] pops;
        switch (colour) {
            case WHITE: pops = new double[]{1.0, 1.25, 1.55}; break;
            case RED: pops = new double[]{1.5, 1.5}; break;
            case ORANGE: pops = new double[]{1.3, 1.0}; break;
            case GOLD: pops = new double[]{1.2}; break;
            case EGG: pops = new double[]{2.0, 2.2, 2.4}; break;
            case CYAN: pops = new double[]{1.1, 1.4}; break;
            default: pops = new double[]{0.6}; // blue: one deep bloop
        }
        for (double p : pops) {
            parts.add(pop(p));
            parts.add(silence(0.05));
        }
        if (colour == BagType.GOLD) parts.add(bell(1760, 0.35));
    }

    private static short[] pop(double pitch) {
        int n = (int) (RATE * 0.07);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double f = (380 + 1100 * t * t) * pitch;
            phase += 2 * Math.PI * f / RATE;
            double env = Math.min(1, t / 0.05) * Math.pow(1 - t, 1.8);
            s[i] = (short) (Math.sin(phase) * env * 0.34 * Short.MAX_VALUE);
        }
        return s;
    }

    /** Kitty meows: "mi-aow" slides, short mews for small bags. */
    private static void meows(BagType colour, java.util.List<short[]> parts) {
        switch (colour) {
            case WHITE:
                parts.add(meow(1.0, 0.3)); parts.add(silence(0.06));
                parts.add(meow(1.15, 0.3)); parts.add(silence(0.06));
                parts.add(meow(1.3, 0.42));
                break;
            case RED:
                parts.add(meow(1.3, 0.16)); parts.add(silence(0.04)); parts.add(meow(1.3, 0.16));
                break;
            case ORANGE:
                parts.add(meow(1.15, 0.25)); parts.add(silence(0.05)); parts.add(meow(0.95, 0.3));
                break;
            case GOLD:
                parts.add(meow(1.1, 0.32)); parts.add(bell(1760, 0.35));
                break;
            case EGG:
                for (int i = 0; i < 3; i++) {
                    parts.add(meow(1.7 + i * 0.1, 0.12));
                    parts.add(silence(0.03));
                }
                break;
            case CYAN:
                parts.add(meow(1.05, 0.22)); parts.add(chirp(0.08, 2400, 3200, 0.22));
                break;
            default:
                parts.add(meow(0.7, 0.45)); // blue: a big lazy meow
        }
    }

    private static short[] meow(double pitch, double seconds) {
        int n = (int) (RATE * seconds);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double f = (520 + 380 * Math.sin(Math.PI * Math.min(1, t * 1.3)) - 120 * t) * pitch;
            f *= 1 + 0.01 * Math.sin(2 * Math.PI * 6 * i / RATE);
            phase += 2 * Math.PI * f / RATE;
            double open = Math.sin(Math.PI * Math.min(1, t * 1.15)); // "m" closed, "ia" open, "ow" closing
            double v = Math.sin(phase) + (0.3 + 0.5 * open) * Math.sin(2 * phase) + 0.35 * open * Math.sin(3 * phase)
                    + 0.15 * open * Math.sin(4 * phase);
            double env = Math.min(1, t / 0.08) * Math.pow(1 - t, 0.9);
            s[i] = (short) (v / 2.3 * env * 0.5 * Short.MAX_VALUE);
        }
        return s;
    }

    /** Magic sparkles: a shimmer of tiny bells running up (or down). */
    private static void sparkles(BagType colour, java.util.List<short[]> parts) {
        double from, to;
        int grains;
        switch (colour) {
            case WHITE: from = 1500; to = 4200; grains = 10; break;
            case RED: from = 2600; to = 3400; grains = 4; break;
            case ORANGE: from = 3200; to = 1800; grains = 6; break;
            case GOLD: from = 1800; to = 4800; grains = 12; break;
            case EGG: from = 3800; to = 4800; grains = 5; break;
            case CYAN: from = 2000; to = 3000; grains = 6; break;
            default: from = 900; to = 1300; grains = 3; // blue
        }
        int step = (int) (RATE * 0.045);
        int tail = (int) (RATE * 0.35);
        short[] out = new short[step * grains + tail];
        for (int g = 0; g < grains; g++) {
            double f = from + (to - from) * (grains == 1 ? 0 : g / (double) (grains - 1));
            short[] b = bell(f, 0.35);
            int at = g * step;
            for (int i = 0; i < b.length && at + i < out.length; i++)
                out[at + i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, out[at + i] + b[i] * 0.48));
        }
        parts.add(out);
    }

    /** One soft, cartoony bark: a quick pitch hump with a touch of breath. */
    private static short[] bark(double pitch) {
        int n = (int) (RATE * (0.12 / Math.sqrt(pitch)));
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double f = (420 + 520 * Math.sin(Math.PI * Math.min(1, t * 1.6))) * pitch;
            phase += 2 * Math.PI * f / RATE;
            double v = Math.sin(phase) + 0.55 * Math.sin(2 * phase) + 0.3 * Math.sin(3 * phase) + 0.15 * Math.sin(5 * phase);
            v += 0.18 * (RANDOM.nextDouble() * 2 - 1) * (1 - t); // breathy start
            double env = Math.min(1, t / 0.06) * Math.pow(1 - t, 1.4);
            s[i] = (short) (v / 2.2 * env * 0.5 * Short.MAX_VALUE);
        }
        return s;
    }

    /** A square-wave note with a tiny fade, for the retro pack. */
    private static short[] square(double freq, double seconds, double volume) {
        int n = (int) (RATE * seconds);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / RATE;
            double v = Math.sin(2 * Math.PI * freq * t) >= 0 ? 1 : -1;
            double env = Math.min(1, i / (RATE * 0.003)) * Math.min(1, (n - i) / (RATE * 0.01));
            s[i] = (short) (v * env * volume * Short.MAX_VALUE);
        }
        return s;
    }

    /** The original squeaky toy. */
    private static void squeaks(BagType colour, java.util.List<short[]> parts) {
        switch (colour) {
            case WHITE:
                parts.add(squeakPcm(1.0));
                parts.add(silence(0.05));
                parts.add(squeakPcm(1.2));
                parts.add(silence(0.05));
                parts.add(squeakPcm(1.45));
                break;
            case RED:
                parts.add(shortSqueak(1.3));
                parts.add(silence(0.04));
                parts.add(shortSqueak(1.3));
                break;
            case ORANGE:
                parts.add(squeakPcm(1.12));
                parts.add(silence(0.05));
                parts.add(squeakPcm(0.95));
                break;
            case GOLD:
                parts.add(squeakPcm(1.08));
                parts.add(bell(1760, 0.35));
                break;
            case EGG:
                for (int i = 0; i < 3; i++) {
                    parts.add(chirp(0.06, 2600 + i * 250, 3400 + i * 250, 0.32));
                    parts.add(silence(0.035));
                }
                break;
            default: // blue
                parts.add(squeakPcm(0.72));
        }
    }

    private static short[] squeakPcm(double pitch) {
        short[] a = chirp(0.11, 1250 * pitch, 2700 * pitch, 0.42);
        short[] gap = new short[(int) (RATE * 0.045)];
        short[] b = chirp(0.15, 2900 * pitch, 1500 * pitch, 0.42);
        short[] out = new short[a.length + gap.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length + gap.length, b.length);
        return out;
    }

    private static short[] shortSqueak(double pitch) {
        return chirp(0.09, 1600 * pitch, 3000 * pitch, 0.4);
    }

    private static short[] silence(double seconds) {
        return new short[(int) (RATE * seconds)];
    }

    /** A soft bell: a sine with a gentle overtone that rings out. */
    private static short[] bell(double freq, double seconds) {
        int n = (int) (RATE * seconds);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / RATE;
            double env = Math.min(1, i / (RATE * 0.004)) * Math.exp(-t * 9);
            double v = Math.sin(2 * Math.PI * freq * t) + 0.3 * Math.sin(2 * Math.PI * freq * 2.76 * t);
            s[i] = (short) (v / 1.3 * env * 0.28 * Short.MAX_VALUE);
        }
        return s;
    }

    /** A soft, short boop for clicks and petting. */
    static void boop() {
        if (!enabled) return;
        playAsync(boopSound(0.96 + RANDOM.nextDouble() * 0.08), false);
    }

    /** These 16-bit samples at the volume slider's level. */
    static byte[] atVolume(byte[] data, int vol) {
        if (vol >= 100) return data;
        double k = Math.pow(Math.max(0, vol) / 100.0, 2); // feels more even than a straight line
        byte[] out = new byte[data.length];
        for (int i = 0; i + 1 < data.length; i += 2) {
            int v = (short) ((data[i] & 255) | (data[i + 1] << 8));
            v = (int) Math.round(v * k);
            out[i] = (byte) v;
            out[i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    private static void playAsync(byte[] raw, boolean beepIfNoAudio) {
        if (volume <= 0) return;
        byte[] data = atVolume(raw, volume);
        Thread t = new Thread(() -> {
            try {
                AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                    line.open(fmt, data.length);
                    line.start();
                    line.write(data, 0, data.length);
                    line.drain();
                }
            } catch (Throwable e) {
                if (beepIfNoAudio) Toolkit.getDefaultToolkit().beep();
            }
        }, "squeak");
        t.setDaemon(true);
        t.start();
    }

    /** Kept for tests: the squeak at normal pitch. */
    static byte[] sound() {
        return squeak(1.0);
    }

    static byte[] squeak(double pitch) {
        // "ar-" rising chirp, tiny gap, "-f!" falling chirp
        short[] a = chirp(0.11, 1250 * pitch, 2700 * pitch, 0.42);
        short[] gap = new short[(int) (RATE * 0.045)];
        short[] b = chirp(0.15, 2900 * pitch, 1500 * pitch, 0.42);
        return pcm(a, gap, b);
    }

    static byte[] boopSound(double pitch) {
        // a short, round blip that slides down a little, quiet and soft-edged
        int n = (int) (RATE * 0.07);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double f = (720 - 180 * t) * pitch;
            phase += 2 * Math.PI * f / RATE;
            double env = Math.min(1, t / 0.12) * Math.pow(1 - t, 2.2);
            s[i] = (short) ((Math.sin(phase) + 0.15 * Math.sin(2 * phase)) / 1.15 * env * 0.2 * Short.MAX_VALUE);
        }
        return pcm(s);
    }

    private static byte[] pcm(short[]... parts) {
        int len = 0;
        for (short[] p : parts) len += p.length;
        byte[] out = new byte[len * 2];
        int i = 0;
        for (short[] p : parts) {
            for (short v : p) {
                out[i++] = (byte) v;
                out[i++] = (byte) (v >> 8);
            }
        }
        return out;
    }

    /** Frequency sweep with a fast wobble and a rubbery 2nd/3rd harmonic. */
    private static short[] chirp(double seconds, double f0, double f1, double volume) {
        int n = (int) (RATE * seconds);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double ease = 1 - Math.pow(1 - t, 2);
            double f = f0 + (f1 - f0) * ease;
            f *= 1 + 0.035 * Math.sin(2 * Math.PI * 38 * i / RATE); // squeaky wobble
            phase += 2 * Math.PI * f / RATE;
            double v = Math.sin(phase) + 0.35 * Math.sin(2 * phase) + 0.12 * Math.sin(3 * phase);
            double env = Math.min(1, t / 0.08) * Math.pow(1 - t, 0.6);
            s[i] = (short) (v / 1.47 * env * volume * Short.MAX_VALUE);
        }
        return s;
    }
}
