package net.friendsserver.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Logs who opened chests, barrels and shulker boxes and what they took or put in.
 * Nothing else is logged. Players look at a container and run /chestlog.
 */
final class ChestLog implements Listener, CommandExecutor, TabCompleter {

    /** One log line. Field names are short to keep the JSON file small. */
    static final class Entry {
        long t;      // epoch millis
        String p;    // player name
        String a;    // action text

        Entry() {
        }

        Entry(long t, String p, String a) {
            this.t = t;
            this.p = p;
            this.a = a;
        }
    }

    /** What a player saw when opening a container. */
    private record Session(List<String> keys, Map<String, Integer> before) {
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type STORE_TYPE = new TypeToken<Map<String, List<Entry>>>() {
    }.getType();

    private final FriendsTools plugin;
    private final Path file;
    private final Map<String, Deque<Entry>> logs = new HashMap<>();
    private final Map<UUID, Session> open = new HashMap<>();
    private boolean dirty;

    ChestLog(FriendsTools plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("chestlog.json");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("chestlog.enabled", true);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!enabled() || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        List<String> keys = keysFor(event.getInventory());
        if (keys.isEmpty()) {
            return;
        }
        open.put(player.getUniqueId(), new Session(keys, count(event.getInventory())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = open.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        String action = describeChanges(session.before(), count(event.getInventory()));
        long now = System.currentTimeMillis();
        for (String key : session.keys()) {
            add(key, new Entry(now, player.getName(), action));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Normally InventoryCloseEvent fires on quit; this just avoids a stale session.
        open.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (enabled() && isTracked(event.getBlockPlaced())) {
            add(key(event.getBlockPlaced()),
                    new Entry(System.currentTimeMillis(), event.getPlayer().getName(), "placed it"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (enabled() && isTracked(event.getBlock())) {
            add(key(event.getBlock()),
                    new Entry(System.currentTimeMillis(), event.getPlayer().getName(), "broke it"));
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return true;
        }
        int distance = plugin.getConfig().getInt("chestlog.max-distance", 6);
        Block target = player.getTargetBlockExact(distance);
        if (target == null || !isTracked(target)) {
            msg.send(player, "chestlog-not-container", "distance", String.valueOf(distance));
            return true;
        }
        pruneOld(key(target));
        Deque<Entry> entries = logs.get(key(target));
        if (entries == null || entries.isEmpty()) {
            msg.send(player, "chestlog-empty");
            return true;
        }

        List<Entry> newestFirst = new ArrayList<>(entries);
        java.util.Collections.reverse(newestFirst);
        int pageSize = Math.max(1, plugin.getConfig().getInt("chestlog.page-size", 10));
        int pages = (newestFirst.size() + pageSize - 1) / pageSize;
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        page = Math.max(1, Math.min(page, pages));

        msg.send(player, "chestlog-header",
                "x", String.valueOf(target.getX()),
                "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()),
                "page", String.valueOf(page),
                "pages", String.valueOf(pages));
        long now = System.currentTimeMillis();
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, newestFirst.size());
        for (Entry e : newestFirst.subList(from, to)) {
            msg.sendRaw(player, "chestlog-line",
                    "ago", ago(now - e.t),
                    "player", e.p,
                    "action", e.a);
        }
        net.kyori.adventure.text.Component nav = PageNav.of("/chestlog", page, pages);
        if (nav != null) {
            player.sendMessage(nav);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }

    // ------------------------------------------------------------------ storage

    void load() {
        // Save changes once a minute, writing the file off the main thread.
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 20L * 60, 20L * 60);
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, List<Entry>> data = GSON.fromJson(json, STORE_TYPE);
            if (data != null) {
                data.forEach((k, v) -> logs.put(k, new ArrayDeque<>(v)));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read chestlog.json", e);
        }
    }

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        String json = serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(json));
    }

    void saveNow() {
        write(serialize());
        dirty = false;
    }

    private String serialize() {
        Map<String, List<Entry>> copy = new LinkedHashMap<>();
        logs.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        return GSON.toJson(copy, STORE_TYPE);
    }

    private synchronized void write(String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("chestlog.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save chestlog.json", e);
        }
    }

    private void add(String key, Entry entry) {
        Deque<Entry> deque = logs.computeIfAbsent(key, k -> new ArrayDeque<>());
        deque.addLast(entry);
        int max = Math.max(1, plugin.getConfig().getInt("chestlog.max-entries-per-container", 100));
        while (deque.size() > max) {
            deque.removeFirst();
        }
        dirty = true;
    }

    private void pruneOld(String key) {
        int keepDays = plugin.getConfig().getInt("chestlog.keep-days", 90);
        Deque<Entry> deque = logs.get(key);
        if (keepDays <= 0 || deque == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - keepDays * 86_400_000L;
        while (!deque.isEmpty() && deque.peekFirst().t < cutoff) {
            deque.removeFirst();
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Block keys for a chest/barrel/shulker inventory (both halves for a double chest). */
    private static List<String> keysFor(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        List<String> keys = new ArrayList<>(2);
        if (holder instanceof DoubleChest doubleChest) {
            if (doubleChest.getLeftSide() instanceof Chest left) {
                keys.add(key(left.getBlock()));
            }
            if (doubleChest.getRightSide() instanceof Chest right) {
                keys.add(key(right.getBlock()));
            }
        } else if (holder instanceof Chest chest) {
            keys.add(key(chest.getBlock()));
        } else if (holder instanceof Barrel barrel) {
            keys.add(key(barrel.getBlock()));
        } else if (holder instanceof ShulkerBox shulker) {
            keys.add(key(shulker.getBlock()));
        }
        return keys;
    }

    private static boolean isTracked(Block block) {
        BlockState state = block.getState();
        return state instanceof Chest || state instanceof Barrel || state instanceof ShulkerBox;
    }

    private static String key(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private static Map<String, Integer> count(Inventory inventory) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            counts.merge(itemName(item), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    private static String itemName(ItemStack item) {
        return item.getType().getKey().getKey().replace('_', ' ');
    }

    private static String describeChanges(Map<String, Integer> before, Map<String, Integer> after) {
        TreeSet<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        List<String> took = new ArrayList<>();
        List<String> put = new ArrayList<>();
        for (String name : names) {
            int diff = after.getOrDefault(name, 0) - before.getOrDefault(name, 0);
            if (diff < 0) {
                took.add(-diff + " " + name);
            } else if (diff > 0) {
                put.add(diff + " " + name);
            }
        }
        if (took.isEmpty() && put.isEmpty()) {
            return "opened it";
        }
        StringBuilder sb = new StringBuilder();
        if (!took.isEmpty()) {
            sb.append("took ").append(String.join(", ", took));
        }
        if (!put.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("put in ").append(String.join(", ", put));
        }
        return sb.toString();
    }

    private static String ago(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) {
            return s + "s ago";
        }
        long m = s / 60;
        if (m < 60) {
            return m + "m ago";
        }
        long h = m / 60;
        if (h < 48) {
            return h + "h ago";
        }
        return String.format(Locale.ROOT, "%dd ago", h / 24);
    }
}
