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
    private TabList tabList;
    private LoginManager login;
    private PvpManager pvp;
    private DiscUpload discUpload;
    private ServerPack serverPack;
    private CustomItems customItems;
    private BuffGolems buffGolems;
    private AdvancementGate advancementGate;
    private Titles titles;

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
        Fixes fixes = new Fixes(this);
        getServer().getPluginManager().registerEvents(fixes, this);
        fixes.applyAttributeSwapping();

        // Login system for offline mode (cracked players), keeps everyone's existing IDs
        login = new LoginManager(this);
        getServer().getPluginManager().registerEvents(login, this);
        for (String name : List.of("login", "register", "changepassword", "premium", "auth")) {
            bind(name, login);
        }
        login.start();

        // Weather fix
        getServer().getPluginManager().registerEvents(new WeatherFix(this), this);

        // /ping [player]
        PingCommand ping = new PingCommand(this);
        bind("ping", ping);
        getServer().getPluginManager().registerEvents(ping, this);

        // Tab list: team tag + name + PvP marker + ping
        tabList = new TabList(this);
        getServer().getPluginManager().registerEvents(tabList, this);

        // /pvp: off by default, both players need it on (also for arrows, potions, wolves...), marker next to names
        pvp = new PvpManager(this);
        getServer().getPluginManager().registerEvents(pvp, this);
        bind("pvp", pvp);
        pvp.start();

        // Every player death drops the player's head
        getServer().getPluginManager().registerEvents(new DeathHeads(this), this);
        getServer().getPluginManager().registerEvents(new InvisibleSplash(this), this);
        // [item] and [inventory] in chat
        ChatShare chatShare = new ChatShare(this);
        getServer().getPluginManager().registerEvents(chatShare, this);
        bind("showinv", chatShare);
        bind("progress", new ProgressCommand(this));
        // Survival versions of Axiom's Tinker, No Updates and Force Place
        BuildTools buildTools = new BuildTools(this);
        getServer().getPluginManager().registerEvents(buildTools, this);
        bind("tinker", buildTools);
        bind("noupdates", buildTools);
        bind("forceplace", buildTools);

        // /upload: small website to upload songs straight into CustomDiscs
        discUpload = new DiscUpload(this);
        bind("discupload", discUpload);
        discUpload.start();

        // Server texture pack (plugins/FriendsTools/pack.zip), served by the website, sent on join
        serverPack = new ServerPack(this);
        getServer().getPluginManager().registerEvents(serverPack, this);
        serverPack.load();
        bind("pack", serverPack);

        // One-of-a-kind prize items (look from the server pack, can't be faked by renaming) + /customitem
        customItems = new CustomItems(this);
        customItems.load();
        bind("customitem", customItems);

        // Prize golems with a custom model (display entities on an invisible golem, rig from the server pack)
        buffGolems = new BuffGolems(this, customItems);
        getServer().getPluginManager().registerEvents(buffGolems, this);
        buffGolems.start();

        // What some prize items do (Mjölnir lightning, Nokia totem, Nether Reactor Core look when placed)
        getServer().getPluginManager().registerEvents(new PrizePowers(this, customItems), this);

        // Titles for advancements (titles.yml from the planner), shown in chat and the tab list, /titles
        titles = new Titles(this);
        titles.load();
        getServer().getPluginManager().registerEvents(titles, this);
        bind("titles", titles);

        // Advancements only count in survival, BACAP rewards once per player (not again on world changes)
        advancementGate = new AdvancementGate(this, customItems);
        getServer().getPluginManager().registerEvents(advancementGate, this);
        advancementGate.start();

        // Runs once the server has finished starting, when every plugin is enabled
        getServer().getScheduler().runTask(this, () -> {
            if (getServer().getPluginManager().getPlugin("Paper_Stasis_Fixer") != null
                    || getServer().getPluginManager().getPlugin("Paper-Stasis-Fixer") != null) {
                getLogger().warning("Paper Stasis Fixer is installed. It puts its own armor stands on stasis plates"
                        + " and breaks FriendsTools' stasis after a relog. Delete its jar.");
            }
            if (getServer().getPluginManager().isPluginEnabled("TabListPing")) {
                getLogger().warning("TabListPing is installed. It overwrites tab names, which hides team tags."
                        + " FriendsTools' tab list (team tag + ping) stays off until TabListPing is removed.");
            } else {
                tabList.start();
            }
        });

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
        if (tabList != null) {
            tabList.stop();
        }
        if (login != null) {
            login.stop();
        }
        if (pvp != null) {
            pvp.stop();
        }
        if (discUpload != null) {
            discUpload.stop();
        }
        if (buffGolems != null) {
            buffGolems.stop();
        }
        if (advancementGate != null) {
            advancementGate.stop();
        }
        if (titles != null) {
            titles.save();
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
            if (tabList != null && !getServer().getPluginManager().isPluginEnabled("TabListPing")) {
                tabList.start();
            }
            if (pvp != null) {
                pvp.reload();
            }
            if (discUpload != null) {
                discUpload.start();
            }
            if (serverPack != null) {
                serverPack.load();
            }
            if (customItems != null) {
                customItems.load();
            }
            if (buffGolems != null) {
                buffGolems.start();
            }
            if (titles != null) {
                titles.load();
            }
            if (advancementGate != null) {
                advancementGate.start();
            }
            refreshTab();
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

    PvpManager pvp() {
        return pvp;
    }

    DiscUpload discUpload() {
        return discUpload;
    }

    ServerPack serverPack() {
        return serverPack;
    }

    Titles titles() {
        return titles;
    }

    /** Updates the tab names right away (after /pvp). */
    void refreshTab() {
        if (tabList != null) {
            tabList.refresh();
        }
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
