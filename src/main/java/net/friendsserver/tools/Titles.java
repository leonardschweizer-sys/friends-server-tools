package net.friendsserver.tools;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Titles for advancements (from plugins/FriendsTools/titles.yml, made with the planner).
 * Everyone who completes an advancement in survival unlocks its title; "first" titles go only to the first player.
 * The worn title shows in chat and in the tab list. /titles to pick one.
 */
final class Titles implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String FIRST = "#first";
    private static final int PAGE = 30;

    /** One advancement's titles. */
    record Entry(String advancement, String title, int rank, String first) {
    }

    /** Who holds an only-once title. */
    record Claim(UUID player, String name, String date) {
    }

    private final FriendsTools plugin;
    private volatile Map<String, Entry> entries = Map.of();
    private final Map<UUID, Set<String>> unlocked = new ConcurrentHashMap<>();
    private final Map<UUID, String> worn = new ConcurrentHashMap<>();
    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    private File dataFile;
    private boolean dirty;

    Titles(FriendsTools plugin) {
        this.plugin = plugin;
    }

    void load() {
        save();
        Map<String, Entry> map = new LinkedHashMap<>();
        File file = new File(plugin.getDataFolder(), "titles.yml");
        if (file.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            for (String adv : yml.getKeys(false)) {
                ConfigurationSection s = yml.getConfigurationSection(adv);
                if (s == null) {
                    continue;
                }
                String title = blankToNull(s.getString("title"));
                String first = blankToNull(s.getString("first"));
                if (title != null || first != null) {
                    String key = adv.toLowerCase(Locale.ROOT);
                    map.put(key, new Entry(key, title, s.getInt("rank", 1) >= 2 ? 2 : 1, first));
                }
            }
            plugin.getLogger().info("Titles: " + map.size() + " advancements with titles loaded.");
        } else {
            plugin.getLogger().info("Titles: no titles.yml in plugins/FriendsTools, so no titles yet.");
        }
        entries = Collections.unmodifiableMap(map);

        dataFile = new File(plugin.getDataFolder(), "titles-data.yml");
        unlocked.clear();
        worn.clear();
        claims.clear();
        if (dataFile.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
            ConfigurationSection players = yml.getConfigurationSection("players");
            if (players != null) {
                for (String id : players.getKeys(false)) {
                    UUID uuid = uuid(id);
                    ConfigurationSection p = players.getConfigurationSection(id);
                    if (uuid == null || p == null) {
                        continue;
                    }
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    set.addAll(p.getStringList("unlocked"));
                    unlocked.put(uuid, set);
                    String w = p.getString("worn");
                    if (w != null && !w.isBlank()) {
                        worn.put(uuid, w);
                    }
                }
            }
            ConfigurationSection c = yml.getConfigurationSection("only-once");
            if (c != null) {
                for (String adv : c.getKeys(false)) {
                    ConfigurationSection s = c.getConfigurationSection(adv);
                    UUID uuid = s == null ? null : uuid(s.getString("player", ""));
                    if (uuid != null) {
                        claims.put(adv, new Claim(uuid, s.getString("name", "?"), s.getString("date", "")));
                    }
                }
            }
        }
    }

    void save() {
        if (!dirty || dataFile == null) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> e : unlocked.entrySet()) {
            yml.set("players." + e.getKey() + ".unlocked", e.getValue().stream().sorted().toList());
        }
        for (Map.Entry<UUID, String> e : worn.entrySet()) {
            yml.set("players." + e.getKey() + ".worn", e.getValue());
        }
        for (Map.Entry<String, Claim> e : claims.entrySet()) {
            yml.set("only-once." + e.getKey() + ".player", e.getValue().player().toString());
            yml.set("only-once." + e.getKey() + ".name", e.getValue().name());
            yml.set("only-once." + e.getKey() + ".date", e.getValue().date());
        }
        try {
            yml.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save titles-data.yml: " + e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Set<String> unlockedOf(UUID id) {
        return unlocked.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
    }

    // ---- unlocking (called by AdvancementGate)

    /** A real first completion of an advancement in survival. */
    void completed(Player p, String advancement) {
        Entry e = entries.get(advancement);
        if (e == null) {
            return;
        }
        if (e.title() != null && unlockedOf(p.getUniqueId()).add(advancement)) {
            dirty = true;
            plugin.messages().send(p, "title-unlocked", component(e, false), "name", e.title());
        }
        if (e.first() != null && !claims.containsKey(advancement)) {
            claims.put(advancement, new Claim(p.getUniqueId(), p.getName(), CustomItems.today()));
            dirty = true;
            if (plugin.getConfig().getBoolean("titles.announce-only-once", true)) {
                Bukkit.getServer().broadcast(render("title-first-claimed", TagResolver.resolver(
                        Placeholder.component("title", tagged(e, true)),
                        Placeholder.component("advancement", advancementName(advancement)),
                        Placeholder.unparsed("player", p.getName()))));
            } else {
                plugin.messages().send(p, "title-unlocked", component(e, true), "name", e.first());
            }
        }
        save();
    }

    /** Titles for advancements the player already has (survival advancements are loaded now). */
    void sync(Player p) {
        Set<String> set = unlockedOf(p.getUniqueId());
        int added = 0;
        for (Entry e : entries.values()) {
            if (e.title() == null || set.contains(e.advancement())) {
                continue;
            }
            Advancement adv = advancement(e.advancement());
            if (adv != null && p.getAdvancementProgress(adv).isDone()) {
                set.add(e.advancement());
                added++;
            }
        }
        if (added > 0) {
            dirty = true;
            save();
            plugin.messages().send(p, "title-synced", "count", String.valueOf(added));
        }
    }

    // ---- display

    private Component render(String key, TagResolver resolver) {
        String text = plugin.getConfig().getString("messages." + key, key);
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        return MM.deserialize(prefix).append(MM.deserialize(text, resolver));
    }

    /** The title text in its rank color, without brackets. */
    private Component tagged(Entry e, boolean first) {
        String fmt = plugin.getConfig().getString(first ? "titles.colors.only-once" : "titles.colors.rank-" + e.rank(),
                first ? "<gradient:#ff5555:#ffaa00><title></gradient>" : e.rank() == 2 ? "<gold><title>" : "<green><title>");
        return MM.deserialize(fmt, Placeholder.unparsed("title", first ? e.first() : e.title()));
    }

    private TagResolver component(Entry e, boolean first) {
        return Placeholder.component("title", tagged(e, first));
    }

    private static Advancement advancement(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        return k == null ? null : Bukkit.getAdvancement(k);
    }

    private static Component advancementName(String key) {
        Advancement adv = advancement(key);
        if (adv != null && adv.getDisplay() != null) {
            return adv.getDisplay().title();
        }
        return Component.text(key);
    }

    /** The worn title as shown before the name ([Title]), or null. Safe to call from the chat thread. */
    Component badge(Player p) {
        String w = worn.get(p.getUniqueId());
        if (w == null) {
            return null;
        }
        boolean first = w.endsWith(FIRST);
        String adv = first ? w.substring(0, w.length() - FIRST.length()) : w;
        Entry e = entries.get(adv);
        if (e == null || (first ? e.first() == null : e.title() == null) || !owns(p.getUniqueId(), adv, first)) {
            return null;
        }
        String fmt = plugin.getConfig().getString("titles.badge", "<dark_gray>[</dark_gray><title><dark_gray>]</dark_gray> ");
        return MM.deserialize(fmt, Placeholder.component("title", tagged(e, first)));
    }

    private boolean owns(UUID id, String adv, boolean first) {
        if (first) {
            Claim c = claims.get(adv);
            return c != null && c.player().equals(id);
        }
        Set<String> set = unlocked.get(id);
        return set != null && set.contains(adv);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("titles.in-chat", true)) {
            return;
        }
        Component badge = badge(event.getPlayer());
        if (badge == null) {
            return;
        }
        ChatRenderer inner = event.renderer();
        event.renderer((source, displayName, message, viewer) -> badge.append(inner.render(source, displayName, message, viewer)));
    }

    // ---- /title

    /** The titles a player can wear: id ("adv" or "adv#first") -> entry, sorted by title text. */
    private List<Map.Entry<String, Entry>> owned(UUID id) {
        Map<String, Entry> out = new HashMap<>();
        Map<String, String> seenText = new HashMap<>();
        for (String adv : unlocked.getOrDefault(id, Set.of())) {
            Entry e = entries.get(adv);
            if (e != null && e.title() != null && seenText.putIfAbsent(e.title().toLowerCase(Locale.ROOT), adv) == null) {
                out.put(adv, e);
            }
        }
        for (Map.Entry<String, Claim> c : claims.entrySet()) {
            Entry e = entries.get(c.getKey());
            if (e != null && e.first() != null && c.getValue().player().equals(id)) {
                out.put(c.getKey() + FIRST, e);
            }
        }
        List<Map.Entry<String, Entry>> list = new ArrayList<>(out.entrySet());
        list.sort(Comparator.comparing(x -> text(x).toLowerCase(Locale.ROOT)));
        return list;
    }

    private static String text(Map.Entry<String, Entry> x) {
        return x.getKey().endsWith(FIRST) ? x.getValue().first() : x.getValue().title();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (entries.isEmpty()) {
            msg.send(sender, "title-no-file");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("only-once")) {
            listOnlyOnce(sender);
            return true;
        }
        if (args.length >= 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take"))
                && sender.hasPermission("friendstools.title.admin")) {
            adminGiveTake(sender, args);
            return true;
        }
        if (!(sender instanceof Player p)) {
            msg.send(sender, "players-only");
            return true;
        }
        if (args.length == 0 || args.length == 1 && args[0].matches("\\d{1,3}")) {
            list(p, args.length == 0 ? 1 : Integer.parseInt(args[0]));
            return true;
        }
        if (args.length == 1 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("none"))) {
            worn.remove(p.getUniqueId());
            dirty = true;
            save();
            plugin.refreshTab();
            msg.send(p, "title-off");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("wear")) {
            wear(p, args[1]);
            return true;
        }
        // /titles <name>
        String name = String.join(" ", args);
        for (Map.Entry<String, Entry> x : owned(p.getUniqueId())) {
            if (text(x).equalsIgnoreCase(name)) {
                wear(p, x.getKey());
                return true;
            }
        }
        msg.send(p, "title-unknown", "name", name);
        return true;
    }

    private void wear(Player p, String id) {
        for (Map.Entry<String, Entry> x : owned(p.getUniqueId())) {
            if (x.getKey().equals(id)) {
                worn.put(p.getUniqueId(), id);
                dirty = true;
                save();
                plugin.refreshTab();
                plugin.messages().send(p, "title-worn", component(x.getValue(), id.endsWith(FIRST)));
                return;
            }
        }
        plugin.messages().send(p, "title-unknown", "name", id);
    }

    private void list(Player p, int page) {
        List<Map.Entry<String, Entry>> owned = owned(p.getUniqueId());
        if (owned.isEmpty()) {
            plugin.messages().send(p, "title-none");
            return;
        }
        int pages = (owned.size() + PAGE - 1) / PAGE;
        page = Math.max(1, Math.min(page, pages));
        long total = entries.values().stream().filter(e -> e.title() != null).map(e -> e.title().toLowerCase(Locale.ROOT))
                .distinct().count();
        plugin.messages().send(p, "title-list", "count", String.valueOf(owned.size()), "total", String.valueOf(total),
                "page", String.valueOf(page), "pages", String.valueOf(pages));
        Component line = Component.empty();
        String current = worn.get(p.getUniqueId());
        for (Map.Entry<String, Entry> x : owned.subList((page - 1) * PAGE, Math.min(owned.size(), page * PAGE))) {
            boolean first = x.getKey().endsWith(FIRST);
            String adv = first ? x.getKey().substring(0, x.getKey().length() - FIRST.length()) : x.getKey();
            Component chip = Component.text(x.getKey().equals(current) ? "» " : "", NamedTextColor.WHITE)
                    .append(tagged(x.getValue(), first))
                    .hoverEvent(HoverEvent.showText(Component.text("From: ", NamedTextColor.GRAY)
                            .append(advancementName(adv))
                            .append(Component.text(first ? "\nOnly-once title" : "\nRank " + (x.getValue().rank() == 2 ? "II" : "I"),
                                    NamedTextColor.DARK_GRAY))
                            .append(Component.text("\nClick to wear it", NamedTextColor.YELLOW))))
                    .clickEvent(ClickEvent.runCommand("/titles wear " + x.getKey()));
            line = line.append(chip).append(Component.text("  "));
        }
        p.sendMessage(line);
        if (pages > 1) {
            Component nav = Component.empty();
            if (page > 1) {
                nav = nav.append(Component.text("« previous  ", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.runCommand("/titles " + (page - 1))));
            }
            if (page < pages) {
                nav = nav.append(Component.text("next »", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.runCommand("/titles " + (page + 1))));
            }
            p.sendMessage(nav);
        }
    }

    private void listOnlyOnce(CommandSender sender) {
        plugin.messages().send(sender, "title-only-once-header");
        List<Entry> list = entries.values().stream().filter(e -> e.first() != null)
                .sorted(Comparator.comparing(e -> e.first().toLowerCase(Locale.ROOT))).toList();
        Component line = Component.empty();
        for (Entry e : list) {
            Claim c = claims.get(e.advancement());
            Component holder = c == null ? Component.text("free", NamedTextColor.DARK_GRAY)
                    : Component.text(c.name(), NamedTextColor.WHITE);
            line = line.append(tagged(e, true).hoverEvent(HoverEvent.showText(Component.text("From: ", NamedTextColor.GRAY)
                            .append(advancementName(e.advancement()))
                            .append(Component.text(c == null ? "" : "\nSince " + c.date(), NamedTextColor.DARK_GRAY)))))
                    .append(Component.text(" (", NamedTextColor.DARK_GRAY)).append(holder)
                    .append(Component.text(")  ", NamedTextColor.DARK_GRAY));
        }
        sender.sendMessage(line);
    }

    /** /titles give|take <player> <title name> (for titles from before titles existed, e.g. who killed the dragon). */
    private void adminGiveTake(CommandSender sender, String[] args) {
        boolean give = args[0].equalsIgnoreCase("give");
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            plugin.messages().send(sender, "customitem-no-player", "player", args[1]);
            return;
        }
        String name = String.join(" ", List.of(args).subList(2, args.length));
        for (Entry e : entries.values()) {
            boolean first = e.first() != null && e.first().equalsIgnoreCase(name);
            boolean normal = e.title() != null && e.title().equalsIgnoreCase(name);
            if (!first && !normal) {
                continue;
            }
            if (first) {
                if (give) {
                    claims.put(e.advancement(), new Claim(target.getUniqueId(),
                            target.getName() == null ? args[1] : target.getName(), CustomItems.today()));
                } else {
                    claims.remove(e.advancement());
                }
            } else if (give) {
                unlockedOf(target.getUniqueId()).add(e.advancement());
            } else {
                unlockedOf(target.getUniqueId()).remove(e.advancement());
            }
            dirty = true;
            save();
            plugin.refreshTab();
            plugin.messages().send(sender, give ? "title-given" : "title-taken", "name", name, "player", args[1]);
            return;
        }
        plugin.messages().send(sender, "title-unknown", "name", name);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("off", "only-once"));
            if (sender.hasPermission("friendstools.title.admin")) {
                options.addAll(List.of("give", "take"));
            }
            if (sender instanceof Player p) {
                for (Map.Entry<String, Entry> x : owned(p.getUniqueId())) {
                    if (!text(x).contains(" ")) {
                        options.add(text(x));
                    }
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take"))) {
            Bukkit.getOnlinePlayers().forEach(pl -> options.add(pl.getName()));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
