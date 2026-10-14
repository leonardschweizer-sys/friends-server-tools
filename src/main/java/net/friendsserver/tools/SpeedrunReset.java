package net.friendsserver.tools;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.kyori.adventure.text.Component;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /speedrunreset [player]: resets the shared speedrun run for everyone. Meant to be run by a lobby NPC
 * (console_command speedrunreset {player}).
 *
 * <p>Order matters, so this is done here instead of with plain NPC actions:</p>
 * <ol>
 *   <li>Everyone in the speedrun worlds is sent to the lobby. Multiverse-Inventories saves their run
 *       inventory into the speedrun group while doing that.</li>
 *   <li>Only when nobody is left in the run, the speedrun group profiles are wiped (inventory, XP, bed,
 *       advancements, last location). Wiping earlier would be undone as soon as a player leaves the run.</li>
 *   <li>The worlds are regenerated one after another with one new random seed.</li>
 *   <li>Teleports into the run are blocked until everything is done.</li>
 * </ol>
 */
final class SpeedrunReset implements Listener, CommandExecutor {

    private enum State { IDLE, EVACUATING, REGENERATING }

    private static final long REGEN_TIMEOUT_TICKS = 20L * 120;
    private static final long EVACUATE_FORCE_MILLIS = 8_000;
    private static final long EVACUATE_GIVE_UP_MILLIS = 20_000;

    private final FriendsTools plugin;
    private final Map<UUID, Long> confirmUntil = new HashMap<>();
    private final Deque<String> regenQueue = new ArrayDeque<>();

    private State state = State.IDLE;
    private long lastResetMillis;
    private long seed;
    private String waitingForWorld;
    private UUID resetter;
    private BukkitTask evacuateTask;
    private BukkitTask timeoutTask;

    SpeedrunReset(FriendsTools plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages msg = plugin.messages();

        // From the NPC the console runs "speedrunreset <player>"; a player typing it resets as themselves.
        Player requester = args.length >= 1 ? Bukkit.getPlayerExact(args[0])
                : sender instanceof Player p ? p : null;
        CommandSender replyTo = requester != null ? requester : sender;
        String name = requester != null ? requester.getName() : sender.getName();

        if (!plugin.getConfig().getBoolean("speedrun.enabled", true)) {
            msg.send(replyTo, "speedrun-disabled");
            return true;
        }
        if (state != State.IDLE) {
            msg.send(replyTo, "speedrun-busy");
            return true;
        }
        long now = System.currentTimeMillis();
        long cooldownMillis = 1000L * Math.max(0, plugin.getConfig().getInt("speedrun.cooldown-seconds", 60));
        long waitMillis = lastResetMillis + cooldownMillis - now;
        if (waitMillis > 0) {
            msg.send(replyTo, "speedrun-cooldown", "seconds", String.valueOf((waitMillis + 999) / 1000));
            return true;
        }

        // Click twice to confirm, so nobody wipes everyone's run by accident.
        int confirmSeconds = Math.max(0, plugin.getConfig().getInt("speedrun.confirm-seconds", 10));
        if (requester != null && confirmSeconds > 0) {
            Long until = confirmUntil.get(requester.getUniqueId());
            if (until == null || until < now) {
                confirmUntil.put(requester.getUniqueId(), now + 1000L * confirmSeconds);
                msg.send(requester, "speedrun-confirm",
                        "seconds", String.valueOf(confirmSeconds),
                        "players", String.valueOf(playersInRun().size()));
                return true;
            }
            confirmUntil.remove(requester.getUniqueId());
        }

        start(replyTo);
        return true;
    }

    // ------------------------------------------------------------------ reset steps

    private void start(CommandSender resetBy) {
        Messages msg = plugin.messages();
        state = State.EVACUATING;
        resetter = resetBy instanceof Player p ? p.getUniqueId() : null;
        plugin.getLogger().info(resetBy.getName() + " started a speedrun reset.");
        // Only the player who reset gets the progress messages (no global broadcast)
        msg.send(resetBy, "speedrun-resetting");

        String lobby = plugin.getConfig().getString("speedrun.lobby-world", "lobby");
        for (Player player : playersInRun()) {
            msg.send(player, "speedrun-evacuated");
            if (player.isDead()) {
                player.spigot().respawn();
            }
            player.leaveVehicle();
            // Multiverse teleport, so it uses the lobby spawn and handles passengers like /lobby does
            console("mvtp " + player.getName() + " " + lobby);
        }

        long started = System.currentTimeMillis();
        boolean[] forced = {false};
        evacuateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<Player> left = playersInRun();
            if (left.isEmpty()) {
                evacuateTask.cancel();
                evacuateTask = null;
                // Multiverse-Inventories saves the run inventories to disk asynchronously when players
                // leave the run; give that a moment so the wipe isn't overwritten by a late save.
                Bukkit.getScheduler().runTaskLater(plugin, this::wipeAndRegen, 40L);
                return;
            }
            long elapsed = System.currentTimeMillis() - started;
            if (elapsed > EVACUATE_FORCE_MILLIS && !forced[0]) {
                forced[0] = true;
                World lobbyWorld = Bukkit.getWorld(lobby);
                if (lobbyWorld != null) {
                    for (Player player : left) {
                        if (player.isDead()) {
                            player.spigot().respawn();
                        }
                        player.leaveVehicle();
                        player.teleportAsync(lobbyWorld.getSpawnLocation());
                    }
                }
            }
            if (elapsed > EVACUATE_GIVE_UP_MILLIS) {
                evacuateTask.cancel();
                evacuateTask = null;
                fail("Could not move these players out of the speedrun worlds: "
                        + left.stream().map(Player::getName).toList());
            }
        }, 10L, 10L);
    }

    private void wipeAndRegen() {
        state = State.REGENERATING;
        String group = plugin.getConfig().getString("speedrun.inventory-group", "speedrun");
        if (group != null && !group.isBlank()) {
            console("mvinv bulkedit playerprofile clear @all group=" + group + " --include-groups-worlds");
        }
        // Positive, so Multiverse doesn't read a leading "-" as a command flag
        seed = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        regenQueue.clear();
        regenQueue.addAll(worlds());
        regenNext();
    }

    private void regenNext() {
        String world = regenQueue.poll();
        if (world == null) {
            finish();
            return;
        }
        if (Bukkit.getWorld(world) == null) {
            plugin.getLogger().warning("Speedrun world '" + world + "' is not loaded, skipping it.");
            regenNext();
            return;
        }
        boolean sameSeed = plugin.getConfig().getBoolean("speedrun.same-seed", true);
        long worldSeed = sameSeed ? seed : ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        waitingForWorld = world;
        cancelTimeout();
        timeoutTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> fail("Regenerating '" + world + "' took too long or failed (see Multiverse messages above)."),
                REGEN_TIMEOUT_TICKS);
        console("mv regen " + world + " --seed " + worldSeed);
    }

    /** Multiverse loads the new world at the end of a regen; that's our signal to do the next one. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (state != State.REGENERATING || waitingForWorld == null
                || !event.getWorld().getName().equalsIgnoreCase(waitingForWorld)) {
            return;
        }
        plugin.getLogger().info("Speedrun world '" + waitingForWorld + "' regenerated.");
        waitingForWorld = null;
        cancelTimeout();
        // give Multiverse a second to finish setting up the world before the next regen
        Bukkit.getScheduler().runTaskLater(plugin, this::regenNext, 20L);
    }

    private void finish() {
        state = State.IDLE;
        lastResetMillis = System.currentTimeMillis();
        plugin.getLogger().info("Speedrun reset done. Seed: " + seed);
        tellResetter("speedrun-ready", "seed", String.valueOf(seed));
    }

    private void fail(String reason) {
        plugin.getLogger().warning("Speedrun reset failed: " + reason);
        state = State.IDLE;
        waitingForWorld = null;
        regenQueue.clear();
        cancelTimeout();
        tellResetter("speedrun-failed");
    }

    /** Messages the player who started the reset, if they are still online (the console log has it anyway). */
    private void tellResetter(String key, String... pairs) {
        Player player = resetter != null ? Bukkit.getPlayer(resetter) : null;
        if (player != null) {
            plugin.messages().send(player, key, pairs);
        }
    }

    // ------------------------------------------------------------------ keep people out during a reset

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (state == State.IDLE) {
            return;
        }
        Location to = event.getTo();
        World target = to.getWorld();
        if (target == null || target.equals(event.getFrom().getWorld()) || !isRunWorld(target)) {
            return;
        }
        event.setCancelled(true);
        plugin.messages().send(event.getPlayer(), "speedrun-closed");
    }

    // ------------------------------------------------------------------ run-only chat

    /** Deaths in the speedrun worlds are only shown to players in the run (not global chat, not Discord). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!privateMessages() || !isRunWorld(event.getEntity().getWorld())) {
            return;
        }
        Component message = event.deathMessage();
        event.deathMessage(null); // null = no broadcast, and DiscordSRV skips it
        sendToRun(message);
    }

    /** Same for advancements made in the speedrun worlds. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!privateMessages() || !isRunWorld(event.getPlayer().getWorld())) {
            return;
        }
        Component message = event.message();
        event.message(null);
        sendToRun(message);
    }

    private boolean privateMessages() {
        return plugin.getConfig().getBoolean("speedrun.run-only-messages", true);
    }

    private void sendToRun(Component message) {
        if (message == null) {
            return;
        }
        for (Player player : playersInRun()) {
            player.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(Component.text("[Speedrun] ").append(message));
    }

    // ------------------------------------------------------------------ helpers

    private List<String> worlds() {
        return plugin.getConfig().getStringList("speedrun.worlds");
    }

    private boolean isRunWorld(World world) {
        String name = world.getName().toLowerCase(Locale.ROOT);
        for (String w : worlds()) {
            if (w.toLowerCase(Locale.ROOT).equals(name)) {
                return true;
            }
        }
        return false;
    }

    private List<Player> playersInRun() {
        List<Player> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isRunWorld(player.getWorld())) {
                result.add(player);
            }
        }
        return result;
    }

    private void console(String command) {
        plugin.getLogger().info("Speedrun reset: /" + command);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }
}
