package vn.pika.chillnhac.music;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ChillMusic (fork tu EggMusic v2) - native NBS player cho EggKhang, KHONG can NoteBlockAPI.
 * Ho tro NBS new-format (v1-v5, byte0=0) lan classic.
 * Phat bang playSound noteblock + pitch chuan + volume theo config (1-100).
 * Loop lien tuc den khi stop().
 */
public class ChillMusic {

    private final JavaPlugin plugin;
    private final File songFile;
    private final int volume;
    private BukkitTask task;
    private volatile boolean playing;
    private SongData song;

    public ChillMusic(JavaPlugin plugin, File songFile, int volume) {
        this.plugin = plugin;
        this.songFile = songFile;
        this.volume = Math.max(1, Math.min(100, volume));
    }

    public void start() throws Exception {
        stop();
        song = NbsReader.read(songFile);
        if (song.notes == 0) {
            plugin.getLogger().warning("[ChillNhac] Bai nhac rong (0 note) - khong phat!");
            return;
        }
        playing = true;
        final double ticksPerNote = Math.max(1.0, 20.0 / Math.max(1.0, song.tempo));
        final double[] acc = {0.0};
        final int[] idx = {0};
        final int maxTick = song.maxTick;
        final float vol = volume / 100.0f;
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!playing) return;
            acc[0] += 1.0;
            while (acc[0] >= ticksPerNote) {
                acc[0] -= ticksPerNote;
                playTick(idx[0], vol);
                idx[0]++;
                if (idx[0] > maxTick + 40) idx[0] = 0; // loop + nghi ~2s
            }
        }, 20L, 1L);
        plugin.getLogger().info("[ChillNhac] Phat " + song.notes + " note, tempo " + song.tempo + ", loop ON, volume " + volume + ".");
    }

    public void stop() {
        playing = false;
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
            }
            task = null;
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    public void refreshPlayer(Player p) {
        // Radio mode: player online la nghe duoc, khong can dang ky
    }

    private void playTick(int tick, float vol) {
        if (tick < 0 || tick >= song.byTick.size()) return;
        List<NoteEvent> list = song.byTick.get(tick);
        if (list == null || list.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location loc = p.getLocation();
            for (NoteEvent n : list) {
                try {
                    p.playSound(loc, n.sound, vol, n.pitch);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------- DATA ----------
    static class SongData {
        double tempo = 10.0;
        int maxTick = 0;
        int notes = 0;
        List<List<NoteEvent>> byTick = new ArrayList<>();
    }

    static class NoteEvent {
        Sound sound;
        float pitch;
    }

    // ---------- NBS READER (new-format v1-v5 + classic fallback) ----------
    static class NbsReader {
        static SongData read(File f) throws Exception {
            SongData s = new SongData();
            // BufferedInputStream BAT BUOC: detectVel() dung mark/reset de peek frame dau
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 8192))) {
                // NBS "new-format" (v1-v5) van giu header classic o dau file:
                // short length, short height, strings, tempo... Nen doc CLASSIC truoc.
                // Chi khi length==0 moi la new-format thuc su (header mo rong).
                int lo = in.readUnsignedByte();
                int hi = in.readUnsignedByte();
                int songLength = lo | (hi << 8);
                int layerCount;
                boolean extHeader = (songLength == 0);
                int version = 0;
                if (extHeader) {
                    version = in.readUnsignedByte();
                    int vanillaCount = in.readUnsignedByte();
                    if (version >= 3) {
                        songLength = readShort(in);
                    }
                    layerCount = readShort(in);
                } else {
                    layerCount = readShort(in);
                }
                readString(in); // title
                readString(in); // author
                readString(in); // original author
                readString(in); // description
                double tempo = readShort(in) / 100.0;
                in.readBoolean(); // auto-save
                in.readByte(); // auto-save duration
                in.readByte(); // time signature
                readInt(in); readInt(in); readInt(in); readInt(in); readInt(in); // stats x5
                readString(in); // midi file name
                // Phat hien note co kem velocity/panning/pitch (NBS v4+) hay khong
                // bang cach peek frame dau: doc thu theo ca 2 cach, cach nao cho
                // jump/ljump/inst/key hop le thi dung cach do.
                final boolean readVel = detectVel(in);
                if (tempo <= 0) tempo = 10.0;
                s.tempo = tempo;
                // note frames
                int tick = -1;
                while (true) {
                    int jump;
                    try {
                        jump = readShort(in);
                    } catch (Throwable e) {
                        break;
                    }
                    if (jump == 0) break;
                    tick += jump;
                    if (tick < 0) continue;
                    while (true) {
                        int ljump = readShort(in);
                        if (ljump == 0) break;
                        int inst = in.readByte() & 0xFF;
                        int key = in.readByte() & 0xFF;
                        if (readVel) {
                            try {
                                in.readByte(); // velocity
                                in.readByte(); // panning
                                readShort(in); // pitch
                            } catch (Throwable ignored) {
                            }
                        }
                        NoteEvent e = toEvent(inst, key);
                        if (e != null) {
                            while (s.byTick.size() <= tick) s.byTick.add(new ArrayList<>());
                            s.byTick.get(tick).add(e);
                            s.notes++;
                            if (tick > s.maxTick) s.maxTick = tick;
                        }
                    }
                }
            }
            return s;
        }

        /**
         * Phat hien note classic 2-byte (inst+key) hay v4+ 6-byte (+vel/pan/pitch).
         * Kiem tra sau: jump 1..4999, ljump 1..499, inst 0..16, key 0..87.
         * Classic dung -> false (khong tieu thu stream). Chi khi classic SAI ma ban
         * vel DUNG -> tieu thu loop-header + custom roi tra true.
         */
        private static boolean detectVel(DataInputStream in) {
            try {
                in.mark(128);
                int jA = readShort(in);
                int lA = readShort(in);
                int instA = in.readUnsignedByte();
                int keyA = in.readUnsignedByte();
                boolean okA = (jA > 0 && jA < 5000) && (lA > 0 && lA < 500)
                        && (instA <= 16) && (keyA <= 87);
                in.reset();
                if (okA) return false;
                in.mark(256);
                try {
                    in.readByte(); in.readByte(); readShort(in);
                    int cc = in.readUnsignedByte();
                    if (cc < 0 || cc > 64) throw new Exception("cc la");
                    for (int i = 0; i < cc; i++) {
                        readString(in); readString(in); in.readByte(); in.readByte();
                    }
                    int jB = readShort(in);
                    int lB = readShort(in);
                    int instB = in.readUnsignedByte();
                    int keyB = in.readUnsignedByte();
                    boolean okB = (jB > 0 && jB < 5000) && (lB > 0 && lB < 500)
                            && (instB <= 16 + cc) && (keyB <= 87);
                    if (okB) return true;
                } catch (Throwable ignored) {
                }
                in.reset();
                return false;
            } catch (Throwable t) {
                try {
                    in.reset();
                } catch (Throwable ignored) {
                }
                return false;
            }
        }

        private static NoteEvent toEvent(int inst, int key) {
            // Transpose ve range chuan 33-57 (F#3-F#5, 2 octave)
            int k = 33 + (Math.floorMod(key - 33, 24));
            float pitch = (float) Math.pow(2.0, (k - 45) / 12.0);
            if (pitch < 0.5f) pitch = 0.5f;
            if (pitch > 2.0f) pitch = 2.0f;
            NoteEvent e = new NoteEvent();
            e.pitch = pitch;
            switch (inst) {
                case 0: e.sound = Sound.BLOCK_NOTE_BLOCK_HARP; break;
                case 1: e.sound = Sound.BLOCK_NOTE_BLOCK_BASS; break;
                case 2: e.sound = Sound.BLOCK_NOTE_BLOCK_BASEDRUM; break;
                case 3: e.sound = Sound.BLOCK_NOTE_BLOCK_SNARE; break;
                case 4: e.sound = Sound.BLOCK_NOTE_BLOCK_HAT; break;
                case 5: e.sound = Sound.BLOCK_NOTE_BLOCK_GUITAR; break;
                case 6: e.sound = Sound.BLOCK_NOTE_BLOCK_FLUTE; break;
                case 7: e.sound = Sound.BLOCK_NOTE_BLOCK_BELL; break;
                case 8: e.sound = Sound.BLOCK_NOTE_BLOCK_CHIME; break;
                case 9: e.sound = Sound.BLOCK_NOTE_BLOCK_XYLOPHONE; break;
                case 10: e.sound = Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE; break;
                case 11: e.sound = Sound.BLOCK_NOTE_BLOCK_COW_BELL; break;
                case 12: e.sound = Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO; break;
                case 13: e.sound = Sound.BLOCK_NOTE_BLOCK_BIT; break;
                case 14: e.sound = Sound.BLOCK_NOTE_BLOCK_BANJO; break;
                case 15: e.sound = Sound.BLOCK_NOTE_BLOCK_PLING; break;
                default: e.sound = Sound.BLOCK_NOTE_BLOCK_HARP; break; // custom instrument -> harp
            }
            return e;
        }

        private static int readShort(DataInputStream in) throws Exception {
            int a = in.readUnsignedByte();
            int b = in.readUnsignedByte();
            return a | (b << 8);
        }

        private static int readInt(DataInputStream in) throws Exception {
            int a = in.readUnsignedByte();
            int b = in.readUnsignedByte();
            int c = in.readUnsignedByte();
            int d = in.readUnsignedByte();
            return a | (b << 8) | (c << 16) | (d << 24);
        }

        private static String readString(DataInputStream in) throws Exception {
            int len = readInt(in);
            if (len <= 0) return "";
            if (len > 1_000_000) throw new Exception("NBS string qua dai: " + len);
            byte[] buf = new byte[len];
            in.readFully(buf);
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
