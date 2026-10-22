package net.friendsserver.tools;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Objective;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Makes advancements fair on a multi-world server:
 * <ul>
 *   <li>Advancements only make progress in the survival worlds (advancements.worlds). In the lobby, creative and
 *       speedrun worlds nothing is earned.</li>
 *   <li>BACAP's rewards (chat message, items, trophies, XP) are given once per player and advancement.
 *       Multiverse-Inventories takes advancements away and grants them back when you change world group, and
 *       BACAP runs its reward for every grant, so players got flooded with old rewards.
 *       BACAP's settings (scoreboard bac_settings) are kept off, and switched on only in the moment a player
 *       really completes an advancement for the first time.</li>
 *   <li>Prize items (custom-items with "advancement") are given on that first real completion, to every player
 *       who completes it. Those advancements don't also give BACAP's item reward and trophy.</li>
 *   <li>Advancements an admin takes away with /advancement revoke can be earned and rewarded again.</li>
 *   <li>Far-travel advancements the world border makes impossible (Farlander wants x or z 29,999,983) count at a
 *       distance that can be reached (advancements.reach-distance).</li>
 * </ul>
 */
final class AdvancementGate implements Listener {

    private static final List<String> MESSAGE_TIERS = List.of("task", "goal", "challenge", "super_challenge",
            "milestone", "hidden", "advancement_legend");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final FriendsTools plugin;
    private final CustomItems items;
    private final Map<UUID, Set<String>> rewarded = new HashMap<>();
    private final Set<UUID> seeded = new HashSet<>();
    private final Map<UUID, Integer> quietUntil = new HashMap<>();
    private final Map<UUID, int[]> burst = new HashMap<>(); // {tick, completions in that tick}
    private final Map<UUID, Integer> burstUntil = new HashMap<>();
    /** More completions than this in one tick are advancements being put back or handed out in bulk, not earned. */
    private static final int BURST = 5;
    private final Set<String> worlds = new HashSet<>();
    private File file;
    private boolean dirty;
    private boolean resetPlanned;
    private boolean controlBacap;
    private boolean bacapMessages;
    private boolean bacapItems;
    private boolean bacapTrophies;
    private boolean bacapXp;
    private boolean bacapCoop;
    private boolean notWithPrize;
    private boolean prizeItems;
    private int quietTicks;
    private BukkitTask saver;
    /** Advancement -> distance from 0,0 (x or z) at which it counts. */
    private final Map<NamespacedKey, Double> reach = new LinkedHashMap<>();
    private BukkitTask reachTask;
    /** You can't stand exactly on the border: this close to the distance is enough. */
    private static final double REACH_SLACK = 2.0;

    AdvancementGate(FriendsTools plugin, CustomItems items) {
        this.plugin = plugin;
        this.items = items;
    }

    void start() {
        stop();
        var cfg = plugin.getConfig();
        worlds.clear();
        for (String w : cfg.getStringList("advancements.worlds")) {
            worlds.add(w.toLowerCase(Locale.ROOT));
        }
        controlBacap = cfg.getBoolean("advancements.bacap-rewards.control", true);
        bacapMessages = cfg.getBoolean("advancements.bacap-rewards.messages", true);
        bacapItems = cfg.getBoolean("advancements.bacap-rewards.items", true);
        bacapTrophies = cfg.getBoolean("advancements.bacap-rewards.trophies", true);
        bacapXp = cfg.getBoolean("advancements.bacap-rewards.xp", true);
        bacapCoop = cfg.getBoolean("advancements.bacap-rewards.coop", false);
        notWithPrize = cfg.getBoolean("advancements.bacap-rewards.not-with-prize-items", true);
        prizeItems = cfg.getBoolean("advancements.prize-items", true);
        quietTicks = Math.max(20, cfg.getInt("advancements.ignore-seconds-after-world-change", 3) * 20);

        List<String> missing = new ArrayList<>();
        for (String w : worlds) {
            if (Bukkit.getWorlds().stream().noneMatch(x -> x.getName().equalsIgnoreCase(w))) {
                missing.add(w);
            }
        }
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("advancements.worlds: no world called " + missing + ". Worlds here: "
                    + Bukkit.getWorlds().stream().map(World::getName).toList());
        }

        file = new File(plugin.getDataFolder(), "advancement-rewards.yml");
        load();
        // BACAP's own rewards stay off; datapacks load during startup, so set it again a bit later too
        setBacap(false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> setBacap(false), 40L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            quiet(p);
        }
        saver = Bukkit.getScheduler().runTaskTimer(plugin, this::save, 600L, 600L);
        startReach();
    }

    void stop() {
        if (saver != null) {
            saver.cancel();
            saver = null;
        }
        if (reachTask != null) {
            reachTask.cancel();
            reachTask = null;
        }
        save();
    }

    // ---- far-travel advancements (advancements.reach-distance)

    private void startReach() {
        reach.clear();
        var cfg = plugin.getConfig();
        String path = "advancements.reach-distance";
        // an older config.yml without the section uses the plugin's (Bukkit would hand out an empty one)
        ConfigurationSection section = cfg.contains(path, true) ? cfg.getConfigurationSection(path)
                : cfg.getDefaults() == null ? null : cfg.getDefaults().getConfigurationSection(path);
        if (section != null) {
            for (String name : section.getKeys(false)) {
                NamespacedKey key = NamespacedKey.fromString(name);
                double distance = section.getDouble(name, 0);
                if (key == null || distance <= 0) {
                    plugin.getLogger().warning(path + ": \"" + name + "\" needs an advancement and a distance above 0.");
                    continue;
                }
                reach.put(key, distance);
            }
        }
        if (reach.isEmpty()) {
            return;
        }
        reachTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkReach, 100L, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (NamespacedKey key : reach.keySet()) {
                if (Bukkit.getAdvancement(key) == null) {
                    plugin.getLogger().warning(path + ": there is no advancement " + key + " (datapack missing?)");
                }
            }
        }, 60L);
    }

    private void checkReach() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || !counts(p.getWorld())) {
                continue;
            }
            Location at = p.getLocation();
            WorldBorder border = p.getWorld().getWorldBorder();
            Location center = border.getCenter();
            double half = border.getSize() / 2;
            for (Map.Entry<NamespacedKey, Double> e : reach.entrySet()) {
                double d = e.getValue();
                // the distance, or the world's border if that comes first (as far as anyone can get)
                boolean far = at.getX() >= Math.min(d, center.getX() + half) - REACH_SLACK
                        || at.getX() <= Math.max(-d, center.getX() - half) + REACH_SLACK
                        || at.getZ() >= Math.min(d, center.getZ() + half) - REACH_SLACK
                        || at.getZ() <= Math.max(-d, center.getZ() - half) + REACH_SLACK;
                if (!far) {
                    continue;
                }
                Advancement adv = Bukkit.getAdvancement(e.getKey());
                if (adv == null) {
                    continue;
                }
                AdvancementProgress progress = p.getAdvancementProgress(adv);
                for (String criterion : List.copyOf(progress.getRemainingCriteria())) {
                    if (progress.isDone()) {
                        break;
                    }
                    progress.awardCriteria(criterion); // rewards, title and prize item come with it as usual
                }
            }
        }
    }

    private boolean counts(World world) {
        return worlds.isEmpty() || worlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    private static boolean real(Advancement adv) {
        // recipe unlocks and BACAP's hidden helper advancements have no display
        AdvancementDisplay display = adv.getDisplay();
        return display != null && !adv.getKey().getKey().startsWith("recipes/");
    }

    private Set<String> rewardedOf(Player p) {
        return rewarded.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
    }

    /** A completion right now is a real first one (not a re-grant by a world change). */
    private boolean firstTime(Player p, String key) {
        UUID id = p.getUniqueId();
        int now = Bukkit.getCurrentTick();
        if (rewardedOf(p).contains(key) || now < burstUntil.getOrDefault(id, 0)) {
            return false;
        }
        // Before we know what a player already had, advancements granted right after coming into survival are
        // Multiverse-Inventories putting them back. Once we know (seeded), those are all in the rewarded list.
        return seeded.contains(id) || now >= quietUntil.getOrDefault(id, 0);
    }

    /** This advancement gives a prize item, so BACAP's own item reward and trophy are left out. */
    private boolean prizeInstead(String key) {
        return prizeItems && notWithPrize && !items.forAdvancement(key).isEmpty();
    }

    // ---- events

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCriterion(PlayerAdvancementCriterionGrantEvent event) {
        Player p = event.getPlayer();
        if (!counts(p.getWorld())) {
            event.setCancelled(true);
            return;
        }
        Advancement adv = event.getAdvancement();
        if (!real(adv)) {
            return;
        }
        // BACAP's reward function runs right after the last criterion is granted, with these settings
        String key = adv.getKey().toString();
        setBacap(firstTime(p, key), prizeInstead(key));
        if (!resetPlanned) {
            resetPlanned = true;
            Bukkit.getScheduler().runTask(plugin, () -> {
                resetPlanned = false;
                setBacap(false);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDone(PlayerAdvancementDoneEvent event) {
        Player p = event.getPlayer();
        Advancement adv = event.getAdvancement();
        if (!real(adv) || !counts(p.getWorld())) {
            return;
        }
        String key = adv.getKey().toString();
        // many completions in one tick = advancements being put back (world change), never a real burst
        int now = Bukkit.getCurrentTick();
        int[] b = burst.computeIfAbsent(p.getUniqueId(), k -> new int[]{now, 0});
        if (b[0] != now) {
            b[0] = now;
            b[1] = 0;
        }
        if (++b[1] > BURST) {
            burstUntil.put(p.getUniqueId(), now + quietTicks);
        }
        boolean first = firstTime(p, key);
        boolean hadIt = !rewardedOf(p).add(key);
        if (!hadIt) {
            dirty = true;
        }
        if (!first) {
            // say why, but not for the ones Multiverse-Inventories puts back on every world change
            boolean settled = now >= quietUntil.getOrDefault(p.getUniqueId(), 0)
                    && now >= burstUntil.getOrDefault(p.getUniqueId(), 0);
            if (settled && hadIt && prizeItems && !items.forAdvancement(key).isEmpty()) {
                plugin.getLogger().info(p.getName() + " got " + key + " again: no prize item, they were rewarded"
                        + " for it before (an admin /advancement revoke makes it count again).");
            }
            return;
        }
        save(); // right away, so a crash can't make it pay out twice
        Titles titles = plugin.titles();
        if (titles != null) {
            titles.completed(p, key);
        }
        if (!prizeItems) {
            return;
        }
        for (CustomItems.Definition def : items.forAdvancement(key)) {
            ItemStack item = items.create(def, p.getName(), CustomItems.today());
            p.getInventory().addItem(item).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            p.sendMessage(MM.deserialize(plugin.getConfig().getString("messages.prize-item-awarded",
                            "<gold>You got <item> <gold>for this advancement!"),
                    Placeholder.component("item", MM.deserialize(def.name()))));
        }
    }

    // Multiverse-Inventories puts the advancements back inside its own world-change/join handlers, so the quiet
    // time has to start before those run: when the teleport/respawn/login is decided, not after it.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo().getWorld() != null && event.getFrom().getWorld() != event.getTo().getWorld()
                && counts(event.getTo().getWorld()) && !counts(event.getFrom().getWorld())) {
            quiet(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        World to = event.getRespawnLocation().getWorld();
        if (to != null && to != event.getPlayer().getWorld() && counts(to) && !counts(event.getPlayer().getWorld())) {
            quiet(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        if (counts(p.getWorld()) && !counts(event.getFrom())) {
            quiet(p);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        quiet(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        quiet(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        quietUntil.remove(event.getPlayer().getUniqueId());
        burst.remove(event.getPlayer().getUniqueId());
        burstUntil.remove(event.getPlayer().getUniqueId());
    }

    // An admin taking advancements away (/advancement revoke): those can be earned and rewarded again.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        revokeCommand(event.getPlayer(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        revokeCommand(event.getSender(), event.getCommand());
    }

    private void revokeCommand(CommandSender sender, String line) {
        String[] args = line.trim().replaceFirst("^/", "").split("\\s+");
        if (args.length < 3 || !args[1].equalsIgnoreCase("revoke")) {
            return;
        }
        String command = args[0].toLowerCase(Locale.ROOT);
        if (!command.equals("advancement") && !command.equals("minecraft:advancement")) {
            return;
        }
        List<Player> targets = new ArrayList<>();
        try {
            for (Entity e : Bukkit.selectEntities(sender, args[2])) {
                if (e instanceof Player p) {
                    targets.add(p);
                }
            }
        } catch (IllegalArgumentException e) {
            Player p = Bukkit.getPlayerExact(args[2]);
            if (p != null) {
                targets.add(p);
            }
        }
        if (!targets.isEmpty()) {
            // the command runs after this event: look next tick
            Bukkit.getScheduler().runTask(plugin, () -> targets.forEach(this::forgetRevoked));
        }
    }

    private void forgetRevoked(Player p) {
        // only in survival and not while Multiverse-Inventories is swapping advancements around
        if (!p.isOnline() || !counts(p.getWorld()) || !seeded.contains(p.getUniqueId())
                || Bukkit.getCurrentTick() < quietUntil.getOrDefault(p.getUniqueId(), 0)) {
            return;
        }
        int forgotten = 0;
        for (Iterator<String> it = rewardedOf(p).iterator(); it.hasNext(); ) {
            NamespacedKey key = NamespacedKey.fromString(it.next());
            Advancement adv = key == null ? null : Bukkit.getAdvancement(key);
            if (adv != null && !p.getAdvancementProgress(adv).isDone()) {
                it.remove();
                forgotten++;
            }
        }
        if (forgotten > 0) {
            dirty = true;
            save();
            plugin.getLogger().info(p.getName() + " can earn " + forgotten + " revoked advancement(s) again,"
                    + " with rewards and prize items.");
        }
    }

    /**
     * Multiverse-Inventories grants the survival advancements back right after a player comes into survival
     * (or joins there): nothing is rewarded in that moment. Afterwards, the first time we see a player,
     * everything they already have counts as rewarded, so old advancements never pay out again.
     */
    private void quiet(Player p) {
        int until = Bukkit.getCurrentTick() + quietTicks;
        if (quietUntil.getOrDefault(p.getUniqueId(), 0) >= until) {
            return; // already quiet (login, join and teleport can come together)
        }
        quietUntil.put(p.getUniqueId(), until);
        Bukkit.getScheduler().runTaskLater(plugin, () -> seed(p), quietTicks + 20L);
    }

    private void seed(Player p) {
        if (!p.isOnline() || !counts(p.getWorld())) {
            return;
        }
        // titles for advancements the player already has (also ones from before titles existed)
        Titles titles = plugin.titles();
        if (titles != null) {
            titles.sync(p);
        }
        if (seeded.contains(p.getUniqueId())) {
            return;
        }
        Set<String> set = rewardedOf(p);
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            if (real(adv) && p.getAdvancementProgress(adv).isDone()) {
                set.add(adv.getKey().toString());
            }
        }
        seeded.add(p.getUniqueId());
        dirty = true;
    }

    // ---- BACAP settings

    private void setBacap(boolean on) {
        setBacap(on, false);
    }

    private void setBacap(boolean on, boolean prizeInstead) {
        if (!controlBacap) {
            return;
        }
        Objective settings = Bukkit.getScoreboardManager().getMainScoreboard().getObjective("bac_settings");
        if (settings == null) {
            return; // BACAP not installed
        }
        for (String tier : MESSAGE_TIERS) {
            set(settings, tier, on && bacapMessages ? 1 : 0);
        }
        set(settings, "reward", on && bacapItems && !prizeInstead ? 1 : 0);
        set(settings, "trophy", on && bacapTrophies && !prizeInstead ? 1 : 0);
        set(settings, "exp", on && bacapXp ? 1 : 0);
        // BACAP co-op grants every advancement to everyone online; never for advancements being put back
        set(settings, "coop", on && bacapCoop ? 1 : 0);
    }

    private static void set(Objective objective, String entry, int value) {
        var score = objective.getScore(entry);
        if (!score.isScoreSet() || score.getScore() != value) {
            score.setScore(value);
        }
    }

    // ---- storage (plugins/FriendsTools/advancement-rewards.yml)

    private void load() {
        rewarded.clear();
        seeded.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String s : yml.getStringList("seeded")) {
            try {
                seeded.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // skip broken lines
            }
        }
        var players = yml.getConfigurationSection("players");
        if (players != null) {
            for (String id : players.getKeys(false)) {
                try {
                    rewarded.put(UUID.fromString(id), new HashSet<>(players.getStringList(id)));
                } catch (IllegalArgumentException ignored) {
                    // skip broken lines
                }
            }
        }
    }

    private void save() {
        if (!dirty || file == null) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("seeded", seeded.stream().map(UUID::toString).sorted().toList());
        for (Map.Entry<UUID, Set<String>> e : rewarded.entrySet()) {
            yml.set("players." + e.getKey(), e.getValue().stream().sorted().toList());
        }
        try {
            yml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save advancement-rewards.yml: " + e);
        }
    }
}
