package net.friendsserver.tools;

import com.booksaw.betterTeams.Team;
import com.booksaw.betterTeams.team.EChestComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Team balance = number of diamonds in the BetterTeams team ender chest.
 * The count is written into BetterTeams' own team money, so /team baltop and team info show it too.
 * Only loaded when BetterTeams is installed.
 */
final class TeamBalance implements Listener, CommandExecutor, TabCompleter {

    private final FriendsTools plugin;
    private BukkitTask recountTask;

    TeamBalance(FriendsTools plugin) {
        this.plugin = plugin;
    }

    /** (Re)starts the periodic recount and does one full recount shortly after startup. */
    void start() {
        if (recountTask != null) {
            recountTask.cancel();
            recountTask = null;
        }
        if (!plugin.getConfig().getBoolean("team-balance.enabled", true)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::recountAll, 100L);
        int minutes = plugin.getConfig().getInt("team-balance.recount-minutes", 10);
        if (minutes > 0) {
            long period = 20L * 60 * minutes;
            recountTask = Bukkit.getScheduler().runTaskTimer(plugin, this::recountAll, period, period);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!plugin.getConfig().getBoolean("team-balance.enabled", true)) {
            return;
        }
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof EChestComponent.EchestInventoryHolder)) {
            return;
        }
        Team team = findTeam(inventory, event.getPlayer());
        if (team != null) {
            update(team);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (args.length >= 1 && args[0].equalsIgnoreCase("top")) {
            recountAll();
            List<Team> teams = new ArrayList<>(Team.getTeamManager().getLoadedTeamListClone().values());
            teams.sort(Comparator.comparingDouble(Team::getMoney).reversed());
            msg.send(sender, "teambal-top-header");
            int rank = 1;
            for (Team team : teams) {
                if (rank > 10) {
                    break;
                }
                msg.sendRaw(sender, "teambal-top-line",
                        "rank", String.valueOf(rank),
                        "team", team.getName(),
                        "amount", String.valueOf((long) team.getMoney()));
                rank++;
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return true;
        }
        Team team = Team.getTeam(player);
        if (team == null) {
            msg.send(player, "teambal-no-team");
            return true;
        }
        int amount = update(team);
        msg.send(player, "teambal-own", "team", team.getName(), "amount", String.valueOf(amount));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && "top".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("top");
        }
        return List.of();
    }

    // ------------------------------------------------------------------ helpers

    private void recountAll() {
        for (Team team : Team.getTeamManager().getLoadedTeamListClone().values()) {
            update(team);
        }
    }

    /** Recounts one team and stores the result as its BetterTeams balance. */
    private int update(Team team) {
        int diamonds = countDiamonds(team.getEchest());
        if (Math.round(team.getMoney()) != diamonds) {
            team.setMoney(diamonds);
        }
        return diamonds;
    }

    private Team findTeam(Inventory inventory, HumanEntity viewer) {
        if (viewer instanceof Player player) {
            Team own = Team.getTeam(player);
            if (own != null && own.getEchest() == inventory) {
                return own;
            }
        }
        // e.g. an admin looking into another team's chest with /teama echest
        for (Team team : Team.getTeamManager().getLoadedTeamListClone().values()) {
            if (team.getEchest() == inventory) {
                return team;
            }
        }
        return null;
    }

    private int countDiamonds(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        boolean blocks = plugin.getConfig().getBoolean("team-balance.count-diamond-blocks", true);
        boolean shulkers = plugin.getConfig().getBoolean("team-balance.count-inside-shulkers", true);
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            total += countItem(item, blocks, shulkers);
        }
        return total;
    }

    private static int countItem(ItemStack item, boolean blocks, boolean shulkers) {
        if (item == null) {
            return 0;
        }
        Material type = item.getType();
        if (type == Material.DIAMOND) {
            return item.getAmount();
        }
        if (blocks && type == Material.DIAMOND_BLOCK) {
            return item.getAmount() * 9;
        }
        if (shulkers && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                    && bsm.getBlockState() instanceof ShulkerBox box) {
                int inside = 0;
                for (ItemStack inner : box.getInventory().getContents()) {
                    // no nested shulkers in vanilla, so don't recurse
                    inside += countItem(inner, blocks, false);
                }
                return inside * item.getAmount();
            }
        }
        return 0;
    }
}
