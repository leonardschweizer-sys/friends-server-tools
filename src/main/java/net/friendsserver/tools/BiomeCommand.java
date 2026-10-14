package net.friendsserver.tools;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * /biome <name>: changes the biome of the whole chunk the player stands in (full height)
 * for a diamond price. Uses vanilla /fillbiome, so clients get the new grass/water colors right away.
 */
final class BiomeCommand implements CommandExecutor, TabCompleter {

    /** 16 x 16 x 64 = 16384 blocks per /fillbiome call, well under the vanilla limit of 32768. */
    private static final int SLICE_HEIGHT = 64;

    /** Vanilla tags for Nether and End biomes. Every other biome counts as an overworld biome. */
    private static final TagKey<Biome> IS_NETHER =
            TagKey.create(RegistryKey.BIOME, Key.key("minecraft", "is_nether"));
    private static final TagKey<Biome> IS_END =
            TagKey.create(RegistryKey.BIOME, Key.key("minecraft", "is_end"));

    private final FriendsTools plugin;
    private boolean warnedRegistry;

    BiomeCommand(FriendsTools plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return true;
        }
        if (!plugin.getConfig().getBoolean("biome.enabled", true)) {
            return true;
        }
        int cost = Math.max(0, plugin.getConfig().getInt("biome.cost-diamonds", 32));
        if (args.length != 1) {
            msg.send(player, "biome-usage", "cost", String.valueOf(cost));
            return true;
        }

        String biome = shortName(args[0].toLowerCase(Locale.ROOT));
        if (!allowed().contains(biome)) {
            msg.send(player, "biome-unknown", "biome", biome);
            return true;
        }

        World world = player.getWorld();
        if (!plugin.getConfig().getStringList("biome.worlds").contains(world.getName())) {
            msg.send(player, "biome-wrong-world");
            return true;
        }

        PlayerInventory inv = player.getInventory();
        ItemStack oneDiamond = new ItemStack(Material.DIAMOND);
        if (countPlainDiamonds(inv, oneDiamond) < cost) {
            msg.send(player, "not-enough-diamonds");
            return true;
        }
        if (cost > 0) {
            HashMap<Integer, ItemStack> missing = inv.removeItem(new ItemStack(Material.DIAMOND, cost));
            if (!missing.isEmpty()) {
                // Should not happen because we counted first; give back what was taken and stop.
                int notTaken = missing.values().stream().mapToInt(ItemStack::getAmount).sum();
                int taken = cost - notTaken;
                if (taken > 0) {
                    inv.addItem(new ItemStack(Material.DIAMOND, taken));
                }
                msg.send(player, "not-enough-diamonds");
                return true;
            }
        }

        Chunk chunk = player.getLocation().getChunk();
        int x1 = chunk.getX() << 4;
        int z1 = chunk.getZ() << 4;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        String dimension = world.getKey().asString();
        for (int y = minY; y <= maxY; y += SLICE_HEIGHT) {
            int y2 = Math.min(y + SLICE_HEIGHT - 1, maxY);
            String cmd = String.format(Locale.ROOT,
                    "minecraft:execute in %s run fillbiome %d %d %d %d %d %d %s",
                    dimension, x1, y, z1, x1 + 15, y2, z1 + 15, fullName(biome));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
        plugin.getLogger().info(player.getName() + " changed chunk " + chunk.getX() + "," + chunk.getZ()
                + " in " + world.getName() + " to " + biome);
        msg.send(player, "biome-done", "biome", biome, "cost", String.valueOf(cost));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String start = args[0].toLowerCase(Locale.ROOT);
        return allowed().stream().filter(b -> b.startsWith(start)).toList();
    }

    /**
     * Biomes players may pick, sorted. With biome.all-overworld-biomes (default) this is every overworld
     * biome the running server knows, minus biome.blocked, so biomes added by Minecraft updates appear
     * without editing the config. Otherwise it is the biome.allowed list.
     */
    private List<String> allowed() {
        Set<String> result = new TreeSet<>();
        if (plugin.getConfig().getBoolean("biome.all-overworld-biomes", true)) {
            result.addAll(overworldBiomes());
        }
        if (result.isEmpty()) {
            for (String name : plugin.getConfig().getStringList("biome.allowed")) {
                result.add(shortName(name.toLowerCase(Locale.ROOT)));
            }
        }
        for (String name : plugin.getConfig().getStringList("biome.blocked")) {
            result.remove(shortName(name.toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(result);
    }

    /**
     * Every biome the running server knows except Nether and End biomes and the_void. Read from the live
     * registry, so new biomes from Minecraft updates (e.g. dappled_forest in 26.3) are included automatically.
     * Returns an empty set if the registry can't be read.
     */
    private Set<String> overworldBiomes() {
        Set<String> names = new TreeSet<>();
        try {
            Registry<Biome> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
            Set<String> notOverworld = new HashSet<>();
            for (TypedKey<Biome> key : registry.getTag(IS_NETHER).values()) {
                notOverworld.add(key.key().asString());
            }
            for (TypedKey<Biome> key : registry.getTag(IS_END).values()) {
                notOverworld.add(key.key().asString());
            }
            notOverworld.add("minecraft:the_void");
            for (Biome biome : registry) {
                String key = biome.getKey().asString();
                if (!notOverworld.contains(key)) {
                    names.add(shortName(key));
                }
            }
        } catch (RuntimeException e) {
            if (!warnedRegistry) {
                warnedRegistry = true;
                plugin.getLogger().warning("Could not read the biome registry, using biome.allowed instead: " + e);
            }
            names.clear();
        }
        return names;
    }

    /** "minecraft:plains" -> "plains"; biomes from datapacks keep their namespace. */
    private static String shortName(String name) {
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    private static String fullName(String name) {
        return name.contains(":") ? name : "minecraft:" + name;
    }

    /** Counts only normal diamonds (renamed ones are ignored, because removeItem can't take them). */
    private static int countPlainDiamonds(PlayerInventory inv, ItemStack oneDiamond) {
        int total = 0;
        for (ItemStack item : inv.getStorageContents()) {
            if (item != null && item.isSimilar(oneDiamond)) {
                total += item.getAmount();
            }
        }
        return total;
    }
}
