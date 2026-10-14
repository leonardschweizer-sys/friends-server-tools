package net.friendsserver.tools;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class FriendsTools extends JavaPlugin {

    private Messages messages;
    private ChestLog chestLog;
    private Object teamBalance; // TeamBalance, kept as Object so BetterTeams classes only load when present

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);

        // Chest log
        chestLog = new ChestLog(this);
        chestLog.load();
        getServer().getPluginManager().registerEvents(chestLog, this);
        bind("chestlog", chestLog);

        // Biome changer
        BiomeCommand biome = new BiomeCommand(this);
        PluginCommand biomeCmd = getCommand("biome");
        if (biomeCmd != null) {
            biomeCmd.setExecutor(biome);
            biomeCmd.setTabCompleter(biome);
        }

        // Speedrun world reset (run by the lobby NPC)
        SpeedrunReset speedrun = new SpeedrunReset(this);
        getServer().getPluginManager().registerEvents(speedrun, this);
        bind("speedrunreset", speedrun);

        // Fishing-rod pearl stasis that survives portals, world changes and relogs
        RemoteStasis stasis = new RemoteStasis(this);
        getServer().getPluginManager().registerEvents(stasis, this);
        stasis.start();

        // Dismount on logout (cushions), hide Multiverse-Inventories' "mvinv:suppressed" chat line
        getServer().getPluginManager().registerEvents(new Fixes(this), this);

        // Weather fix
        getServer().getPluginManager().registerEvents(new WeatherFix(this), this);

        // Team diamond balance (only when BetterTeams is installed)
        PluginCommand teamBalCmd = getCommand("teambal");
        if (getServer().getPluginManager().isPluginEnabled("BetterTeams")) {
            TeamBalance tb = new TeamBalance(this);
            teamBalance = tb;
            getServer().getPluginManager().registerEvents(tb, this);
            if (teamBalCmd != null) {
                teamBalCmd.setExecutor(tb);
                teamBalCmd.setTabCompleter(tb);
            }
            tb.start();
        } else {
            getLogger().warning("BetterTeams not found, /teambal is disabled.");
            if (teamBalCmd != null) {
                teamBalCmd.setExecutor((sender, command, label, args) -> {
                    messages.send(sender, "teambal-unavailable");
                    return true;
                });
            }
        }
    }

    @Override
    public void onDisable() {
        if (chestLog != null) {
            chestLog.saveNow();
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        // Only /friendstools ends up here; the other commands have their own executors.
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            if (teamBalance instanceof TeamBalance tb) {
                tb.start();
            }
            messages.send(sender, "reloaded");
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return args.length == 1 ? List.of("reload") : List.of();
    }

    Messages messages() {
        return messages;
    }

    private void bind(String name, Object handler) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            return;
        }
        if (handler instanceof org.bukkit.command.CommandExecutor exec) {
            cmd.setExecutor(exec);
        }
        if (handler instanceof org.bukkit.command.TabCompleter tab) {
            cmd.setTabCompleter(tab);
        }
    }
}
