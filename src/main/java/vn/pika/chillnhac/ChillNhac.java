package vn.pika.chillnhac;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import vn.pika.chillnhac.music.ChillMusic;

/**
 * ChillNhac v1.0 - nhac nen chill tu dong cho ca server.
 * Chay la phat (radio, khong can lenh). Het bai -> nghi vai giay -> bai tiep (xoay vong).
 * Bai hat: file .nbs trong plugins/ChillNhac/songs/ (mac dinh 5 bai bundled).
 * Muon them bai: tha .nbs vao thu muc songs + restart (khong can build lai).
 */
public class ChillNhac extends JavaPlugin {

    private ChillMusic music;
    private List<String> playlist = new ArrayList<>();
    private int cursor = 0;
    private int checkTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureSongs();
        buildPlaylist();
        if (playlist.isEmpty()) {
            getLogger().warning("Khong co file .nbs nao trong songs/ - tat nhac!");
            return;
        }
        if (getConfig().getBoolean("shuffle", true)) {
            Collections.shuffle(playlist);
        }
        getLogger().info("Playlist chill (" + playlist.size() + " bai): " + playlist);
        playCurrent();
        // Watchdog 10s: neu nhac dung (het bai / loi) -> chuyen bai tiep
        long period = Math.max(100L, getConfig().getLong("check-ticks", 200L));
        checkTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (music == null || !music.isPlaying()) {
                next();
            }
        }, period, period).getTaskId();
        getLogger().info("ChillNhac v1.0 da bat! Nghe chill nhe ae.");
    }

    @Override
    public void onDisable() {
        if (checkTaskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(checkTaskId);
            } catch (Throwable ignored) {
            }
            checkTaskId = -1;
        }
        stopCurrent();
    }

    /** Copy 5 bai bundled ra plugins/ChillNhac/songs/ neu chua co. */
    private void ensureSongs() {
        File dir = new File(getDataFolder(), "songs");
        if (!dir.exists()) dir.mkdirs();
        String[] bundled = {"Sweden.nbs", "Canon in D.nbs", "Fur Elise.nbs", "faded.nbs", "merrygoroundoflife.nbs"};
        for (String name : bundled) {
            File out = new File(dir, name);
            if (!out.exists()) {
                try {
                    saveResource("songs/" + name, false);
                } catch (Throwable t) {
                    getLogger().warning("Khong copy duoc " + name + ": " + t.getMessage());
                }
            }
        }
    }

    /** Quet .nbs trong songs/ (bo file loi 0 byte). */
    private void buildPlaylist() {
        playlist.clear();
        cursor = 0;
        File dir = new File(getDataFolder(), "songs");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".nbs"));
        if (files == null) return;
        List<String> names = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.length() > 100) names.add(f.getName());
        }
        Collections.sort(names);
        playlist.addAll(names);
    }

    private void playCurrent() {
        stopCurrent();
        if (playlist.isEmpty()) return;
        if (cursor < 0 || cursor >= playlist.size()) cursor = 0;
        String name = playlist.get(cursor);
        File f = new File(new File(getDataFolder(), "songs"), name);
        int vol = Math.max(1, Math.min(100, getConfig().getInt("volume", 25)));
        try {
            music = new ChillMusic(this, f, vol);
            music.start();
            getLogger().info("Dang phat chill: " + name + " (volume " + vol + ")");
        } catch (Throwable ex) {
            getLogger().warning("Bo qua bai loi " + name + ": " + ex.getMessage());
            next();
        }
    }

    private void stopCurrent() {
        if (music != null) {
            try {
                music.stop();
            } catch (Throwable ignored) {
            }
            music = null;
        }
    }

    private void next() {
        if (playlist.isEmpty()) return;
        cursor = (cursor + 1) % playlist.size();
        playCurrent();
    }
}
