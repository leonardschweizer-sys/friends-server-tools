package net.friendsserver.tools;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
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
 *   <li>Prize items (custom-items with "advancement") are given on that first real completion.</li>
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
    private boolean prizeItems;
    private int quietTicks;
    private BukkitTask saver;

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
    }

    void stop() {
        if (saver != null) {
            saver.cancel();
            saver = null;
        }
        save();
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
        return Bukkit.getCurrentTick() >= quietUntil.getOrDefault(p.getUniqueId(), 0)
                && !rewardedOf(p).contains(key);
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
        setBacap(firstTime(p, adv.getKey().toString()));
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
        if (++b[1] > 3) {
            quietUntil.put(p.getUniqueId(), now + quietTicks);
        }
        boolean first = firstTime(p, key);
        if (rewardedOf(p).add(key)) {
            dirty = true;
        }
        if (!first || !prizeItems) {
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
        if (!p.isOnline() || !counts(p.getWorld()) || seeded.contains(p.getUniqueId())) {
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
        set(settings, "reward", on && bacapItems ? 1 : 0);
        set(settings, "trophy", on && bacapTrophies ? 1 : 0);
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
