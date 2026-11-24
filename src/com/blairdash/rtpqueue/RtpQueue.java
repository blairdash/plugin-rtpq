/*
 * RTPQueue - queue up, then get teleported together.
 * Copyright (C) 2026 blairdash
 * Licensed under the GNU General Public License v3.0 (see LICENSE).
 */
package com.blairdash.rtpqueue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RTP Queue: /rtpqueue puts you in a queue. As soon as two players are waiting they are both teleported
 * to the same random spot, so they can fight without having to trust each other with coordinates.
 * Works on Paper and Folia.
 */
public final class RtpQueue extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final int BATCH = 6;
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    /** Players waiting, in join order, with the time they joined. */
    private final Map<UUID, Long> queue = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var join = getCommand("rtpqueue");
        if (join != null) { join.setExecutor(this); join.setTabCompleter(this); }
        var clear = getCommand("rtpqueue-clear");
        if (clear != null) clear.setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        // Drop people who waited too long (checked every few seconds).
        Bukkit.getAsyncScheduler().runAtFixedRate(this, t -> expire(), 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() { synchronized (queue) { queue.clear(); } }

    // ---- messages --------------------------------------------------------------------------------
    private Component text(String key, String... pairs) {
        String s = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) s = s.replace(pairs[i], pairs[i + 1]);
        return AMP.deserialize(getConfig().getString("messages.prefix", "") + s);
    }

    private void tell(Player p, String key, String... pairs) { p.sendMessage(text(key, pairs)); }

    // ---- commands --------------------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("rtpqueue-clear")) {
            if (!sender.hasPermission("rtpqueue.clear")) { sender.sendMessage(text("no-permission")); return true; }
            int n;
            synchronized (queue) { n = queue.size(); queue.clear(); }
            sender.sendMessage(text("cleared", "%count%", String.valueOf(n)));
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!p.hasPermission("rtpqueue.use")) { tell(p, "no-permission"); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
            boolean was;
            synchronized (queue) { was = queue.remove(p.getUniqueId()) != null; }
            tell(p, was ? "left" : "not-in-queue");
            return true;
        }
        join(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1 && "leave".startsWith(a[0].toLowerCase(Locale.ROOT))) return List.of("leave");
        return List.of();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { synchronized (queue) { queue.remove(e.getPlayer().getUniqueId()); } }

    // ---- queue -----------------------------------------------------------------------------------
    private void join(Player p) {
        Player a = null, b = null;
        int size;
        synchronized (queue) {
            if (queue.containsKey(p.getUniqueId())) { tell(p, "already-in-queue"); return; }
            queue.put(p.getUniqueId(), System.currentTimeMillis());
            size = queue.size();
            if (size >= 2) {
                // The two who have waited longest go first.
                List<UUID> ids = new ArrayList<>(queue.keySet());
                Player first = Bukkit.getPlayer(ids.get(0)), second = Bukkit.getPlayer(ids.get(1));
                if (first != null && second != null) {
                    a = first; b = second;
                    queue.remove(ids.get(0)); queue.remove(ids.get(1));
                }
            }
        }
        if (a == null) {
            tell(p, "joined");
            tell(p, "queue-size", "%count%", String.valueOf(size), "%s%", size == 1 ? "" : "s");
            return;
        }
        match(a, b);
    }

    private void expire() {
        long limit = getConfig().getLong("queue-timeout-seconds", 60) * 1000L;
        if (limit <= 0) return;
        long now = System.currentTimeMillis();
        List<UUID> gone = new ArrayList<>();
        synchronized (queue) {
            queue.entrySet().removeIf(en -> {
                if (now - en.getValue() < limit) return false;
                gone.add(en.getKey());
                return true;
            });
        }
        for (UUID id : gone) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.getScheduler().run(this, t -> tell(p, "timed-out"), null);
        }
    }
    // ---- teleporting -----------------------------------------------------------------------------
    private void match(Player a, Player b) {
        tell(a, "searching"); tell(b, "searching");
        if (getConfig().getBoolean("use-rtp-command", true) && !getConfig().getString("rtp-command", "rtp").isBlank()) rtpThenTp(a, b);
        else randomSpot(a, b);
    }

    /**
     * Runs the server's own /rtp as the first player, waits until they have actually moved, then teleports the
     * second player next to them. Falls back to the built-in random spot if /rtp does nothing in time.
     */
    private void rtpThenTp(Player a, Player b) {
        Location start = a.getLocation();
        String command = getConfig().getString("rtp-command", "rtp").replaceFirst("^/", "");
        long deadline = System.currentTimeMillis() + getConfig().getLong("rtp-timeout-seconds", 15) * 1000L;
        a.getScheduler().run(this, t -> Bukkit.dispatchCommand(a, command), () -> fail(a, b));
        AtomicInteger once = new AtomicInteger();
        a.getScheduler().runAtFixedRate(this, task -> {
            if (!a.isOnline()) { task.cancel(); if (once.getAndIncrement() == 0) fail(a, b); return; }
            Location now = a.getLocation();
            boolean moved = now.getWorld() != start.getWorld() || now.distanceSquared(start) > 50 * 50;
            if (moved) {
                task.cancel();
                if (once.getAndIncrement() != 0) return;
                Location spot = now.clone();
                int sep = getConfig().getInt("separation", 4);
                CompletableFuture<Location> second = sep <= 0 ? CompletableFuture.completedFuture(spot)
                        : nearby(spot.clone().add(sep, 0, 0), spot);
                second.thenAccept(l2 -> {
                    a.getScheduler().run(this, x -> { a.sendActionBar(text("teleported-bar")); tell(a, "teleported"); }, null);
                    send(b, l2);
                });
            } else if (System.currentTimeMillis() > deadline) {
                task.cancel();
                if (once.getAndIncrement() == 0) randomSpot(a, b);
            }
        }, () -> { if (once.getAndIncrement() == 0) fail(a, b); }, 10L, 10L);
    }

    private void randomSpot(Player a, Player b) {
        World world = pickWorld(a);
        if (world == null) { fail(a, b); return; }
        int min = getConfig().getInt("min-distance", 0), max = getConfig().getInt("max-distance", 1000);
        double half = world.getWorldBorder().getSize() / 2.0 - 16;
        max = (int) Math.max(1, Math.min(max, half));
        min = Math.min(min, max - 1);
        search(world, min, max, getConfig().getInt("max-attempts", 40)).thenAccept(loc -> {
            if (loc == null) { fail(a, b); return; }
            int sep = getConfig().getInt("separation", 4);
            CompletableFuture<Location> second = sep <= 0 ? CompletableFuture.completedFuture(loc)
                    : nearby(loc.clone().add(sep, 0, 0), loc);
            second.thenAccept(l2 -> { send(a, loc); send(b, l2); });
        }).exceptionally(t -> { fail(a, b); return null; });
    }

    private void send(Player p, Location loc) {
        p.teleportAsync(loc).thenAccept(ok -> p.getScheduler().run(this, t -> {
            if (ok) { p.sendActionBar(text("teleported-bar")); tell(p, "teleported"); }
            else tell(p, "failed");
        }, null));
    }

    private void fail(Player a, Player b) {
        for (Player p : new Player[] { a, b }) {
            if (p.isOnline()) p.getScheduler().run(this, t -> tell(p, "failed"), null);
        }
    }

    private World pickWorld(Player p) {
        String name = getConfig().getString("world", "");
        if (!name.isEmpty()) { World w = Bukkit.getWorld(name); if (w != null) return w; }
        World w = p.getWorld();
        if (w.getEnvironment() == World.Environment.NORMAL) return w;
        for (World o : Bukkit.getWorlds()) if (o.getEnvironment() == World.Environment.NORMAL) return o;
        return null;
    }

    private CompletableFuture<Location> search(World w, int min, int max, int tries) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        batch(w, min, max, tries, result);
        return result;
    }

    private void batch(World w, int min, int max, int tries, CompletableFuture<Location> result) {
        if (result.isDone()) return;
        if (tries <= 0) { result.complete(null); return; }
        AtomicInteger left = new AtomicInteger(BATCH);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < BATCH; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double dist = min + r.nextDouble() * Math.max(1, max - min);
            int x = (int) Math.round(Math.cos(ang) * dist);
            int z = (int) Math.round(Math.sin(ang) * dist);
            w.getChunkAtAsync(x >> 4, z >> 4).whenComplete((chunk, err) -> {
                if (err != null) { done(left, w, min, max, tries, result); return; }
                // Block reads must happen on the thread that owns the chunk (matters on Folia).
                Bukkit.getRegionScheduler().run(this, w, x >> 4, z >> 4, t -> {
                    Location l = safeSpot(w, x, z);
                    if (l != null) result.complete(l);
                    done(left, w, min, max, tries, result);
                });
            });
        }
    }

    private void done(AtomicInteger left, World w, int min, int max, int tries, CompletableFuture<Location> result) {
        if (left.decrementAndGet() == 0 && !result.isDone()) batch(w, min, max, tries - BATCH, result);
    }

    /** A safe spot at {@code want}; falls back to {@code fallback} (the first player's spot). */
    private CompletableFuture<Location> nearby(Location want, Location fallback) {
        CompletableFuture<Location> out = new CompletableFuture<>();
        World w = want.getWorld();
        int x = want.getBlockX(), z = want.getBlockZ();
        w.getChunkAtAsync(x >> 4, z >> 4).whenComplete((chunk, err) -> {
            if (err != null) { out.complete(fallback); return; }
            Bukkit.getRegionScheduler().run(this, w, x >> 4, z >> 4, t -> {
                Location l = safeSpot(w, x, z);
                out.complete(l != null ? l : fallback);
            });
        });
        return out;
    }

    private Location safeSpot(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z);
        if (ok(w.getBlockAt(x, y, z), w.getBlockAt(x, y + 1, z), w.getBlockAt(x, y + 2, z)))
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        return null;
    }

    private static boolean ok(Block floor, Block a, Block b) {
        Material f = floor.getType();
        if (!f.isSolid() || floor.isLiquid()) return false;
        switch (f) {
            case MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, FIRE, SOUL_FIRE, SWEET_BERRY_BUSH, POWDER_SNOW,
                 WITHER_ROSE, BEDROCK, END_PORTAL, END_GATEWAY, NETHER_PORTAL -> { return false; }
            default -> { }
        }
        return clear(a) && clear(b);
    }

    private static boolean clear(Block b) {
        Material m = b.getType();
        return (m.isAir() || (b.isPassable() && !b.isLiquid())) && m != Material.FIRE && m != Material.SOUL_FIRE
                && m != Material.NETHER_PORTAL && m != Material.END_PORTAL && m != Material.SWEET_BERRY_BUSH;
    }
}