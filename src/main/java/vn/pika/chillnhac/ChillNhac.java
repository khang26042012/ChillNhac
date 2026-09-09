package vn.pika.chillnhac;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import vn.pika.chillnhac.music.ChillMusic;

/**
 * ChillNhac v1.1 - nhac nen chill tu dong cho ca server.
 * Chay la phat (radio). Het bai -> bai tiep (xoay vong).
 * Moi nguoi tu tat/mo rieng bang /nhac (mac dinh MO).
 * Bai hat: file .nbs trong plugins/ChillNhac/songs/ (mac dinh 5 bai bundled).
 * Muon them bai: tha .nbs vao thu muc songs + restart (khong can build lai).
 */
public class ChillNhac extends JavaPlugin implements Listener {

    private ChillMusic music;
    private List<String> playlist = new ArrayList<>();
    private int cursor = 0;
    private int checkTaskId = -1;
    // Nguoi da TAT nhac (mac dinh rong = ai cung nghe)
    private final Set<UUID> muted = new HashSet<>();
    private static final String PREFIX = "\u00A7b[\u00A7eNhac\u00A7b] \u00A77";

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
        // Watchdog: neu nhac dung (het bai / loi) -> chuyen bai tiep
        long period = Math.max(100L, getConfig().getLong("check-ticks", 200L));
        checkTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (music == null || !music.isPlaying()) {
                next();
            }
        }, period, period).getTaskId();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("ChillNhac v1.1 da bat! Len h /nhac de tat/mo. Nghe chill nhe ae.");
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
        muted.clear();
    }

    // ---------- LENH /nhac ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("nhac")) return false;
        if (!(sender instanceof Player)) {
            sender.sendMessage(PREFIX + "Lenh nay chi dung trong game.");
            return true;
        }
        Player p = (Player) sender;
        if (muted.remove(p.getUniqueId())) {
            p.sendMessage(PREFIX + "Da \u00A7aBAT\u00A77 nhac chill. Chill thoi!");
            if (music != null) {
                try {
                    music.refreshPlayer(p);
                } catch (Throwable ignored) {
                }
            }
        } else {
            muted.add(p.getUniqueId());
            try {
                p.stopAllSounds();
            } catch (Throwable ignored) {
            }
            p.sendMessage(PREFIX + "Da \u00A7cTAT\u00A77 nhac chill. Go lai \u00A7e/nhac\u00A77 de bat.");
        }
        return true;
    }

    /** ChillMusic co skip nguoi tat khong? Khong — engine phat toan server.
     *  Giai phap: khi tat, stopAllSounds ngay + watchdog se phat tiep bai sau
     *  nhung nguo i tat se lai nghe. De tat TRIET DE can loc per-player trong engine.
     *  => Xem MutedNoteFilter ben duoi: engine goi isMuted(uuid) truoc moi note. */
    public boolean isMuted(UUID id) {
        return muted.contains(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Nguoi moi vao mac dinh NGHE (khong co trong muted) — khong can lam gi.
        // Nguoi da tat tu truoc (relog trong cung session) giu nguyen trang thai tat.
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Giu trang thai tat qua relog trong cung phien server (khong xoa).
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
            music.setMuteCheck(this::isMuted);
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
