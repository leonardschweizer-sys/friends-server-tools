package net.friendsserver.tools;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * /biome <name>: changes the biome of the whole chunk the player stands in (full height)
 * for a diamond price. Uses vanilla /fillbiome, so clients get the new grass/water colors right away.
 */
final class BiomeCommand implements CommandExecutor, TabCompleter {

    /** 16 x 16 x 64 = 16384 blocks per /fillbiome call, well under the vanilla limit of 32768. */
    private static final int SLICE_HEIGHT = 64;

    private final FriendsTools plugin;

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

        String biome = args[0].toLowerCase(Locale.ROOT);
        if (biome.startsWith("minecraft:")) {
            biome = biome.substring("minecraft:".length());
        }
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
                    "minecraft:execute in %s run fillbiome %d %d %d %d %d %d minecraft:%s",
                    dimension, x1, y, z1, x1 + 15, y2, z1 + 15, biome);
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

    private List<String> allowed() {
        return plugin.getConfig().getStringList("biome.allowed").stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
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
