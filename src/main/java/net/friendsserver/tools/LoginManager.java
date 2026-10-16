package net.friendsserver.tools;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Login system for running in offline mode (so cracked players can join) without putting anyone's data at risk.
 *
 * <ul>
 *   <li><b>Same IDs as before.</b> Every name that played before the switch keeps the exact UUID it has today
 *       (AsyncPlayerPreLoginEvent#setPlayerProfile), so inventories, advancements, teams and ranks are untouched.
 *       A known name never gets in with a different ID: the join is refused instead.</li>
 *   <li><b>Protected names.</b> All existing (Mojang) players are protected automatically. Joining with a protected
 *       name requires passing Mojang's login check ({@link PremiumVerifier}); cracked clients can't. No password.</li>
 *   <li><b>Cracked players.</b> Must be whitelisted, /register on the first join, /login later. The same IP logs in
 *       automatically for a while. Until then they can't move, chat, use commands, items, inventories or voice chat.</li>
 * </ul>
 *
 * <p>While online-mode=true this does nothing except remember every player who joins as a protected name.</p>
 */
final class LoginManager implements Listener, CommandExecutor, TabCompleter {

    interface PremiumCheck {
        VerifiedLogin consume(String name, String ip);

        void close();
    }

    record VerifiedLogin(UUID mojangId, String name, List<MojangApi.Prop> properties, String ip, long time) {
    }

    private static final Set<String> ALLOWED_WHILE_LOCKED = Set.of("login", "l", "register", "reg");

    private final FriendsTools plugin;
    private final Accounts accounts;
    private PremiumCheck premiumCheck;
    private boolean lockdown;

    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> verifiedThisLogin = ConcurrentHashMap.newKeySet();
    private final Set<String> onlineNames = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private final Map<UUID, BukkitTask> timers = new HashMap<>();
    private final Map<UUID, PermissionAttachment> voiceBlocks = new HashMap<>();
    private final Map<UUID, Long> premiumConfirm = new HashMap<>();

    LoginManager(FriendsTools plugin) {
        this.plugin = plugin;
        this.accounts = new Accounts(plugin);
    }

    void start() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            onlineNames.add(Accounts.key(p.getName()));
        }
        if (!enabled()) {
            if (!Bukkit.getOnlineMode()) {
                plugin.getLogger().severe("Login: offline mode with login.enabled: false. Known players get NEW IDs"
                        + " (empty inventories) and anyone can use any name. Turn it back on!");
            }
            return;
        }
        try {
            accounts.load();
        } catch (IllegalStateException e) {
            // Never guess: without the account list known names could get in with new IDs.
            lockdown = true;
            plugin.getLogger().severe(e.getMessage() + " -- nobody can join in offline mode until this is fixed.");
            return;
        }
        int imported = importKnownPlayers();
        if (imported > 0) {
            plugin.getLogger().info("Login: protected " + imported + " existing player name(s) with their current IDs.");
        }
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            try {
                premiumCheck = PremiumVerifier.create(plugin, this);
            } catch (Throwable t) {
                plugin.getLogger().severe("Login: could not start the Mojang check for protected names: " + t);
            }
        }
        if (!Bukkit.getOnlineMode()) {
            plugin.getLogger().info("Login: offline mode, " + accounts.premiumCount() + " protected name(s), "
                    + (accounts.all().size() - accounts.premiumCount()) + " cracked account(s).");
            if (premiumVerification() && premiumCheck == null) {
                plugin.getLogger().severe("Login: PacketEvents is missing, so protected names can't pass the Mojang"
                        + " check and will be refused. Install PacketEvents or set login.premium-verification: false.");
            }
            if (Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false)) {
                plugin.getLogger().severe("Login: spigot.yml has bungeecord: true. Without a proxy that lets anyone fake"
                        + " any player ID. Set it to false!");
            }
            if (!Bukkit.hasWhitelist()) {
                plugin.getLogger().warning("Login: the whitelist is off, so any cracked name can join and register.");
            }
        }
    }

    void stop() {
        if (premiumCheck != null) {
            premiumCheck.close();
            premiumCheck = null;
        }
        timers.values().forEach(BukkitTask::cancel);
        timers.clear();
        if (enabled() && !lockdown) {
            accounts.saveNow();
        }
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("login.enabled", true);
    }

    private boolean premiumVerification() {
        return plugin.getConfig().getBoolean("login.premium-verification", true);
    }

    /** Used by the verifier on the network thread. */
    boolean needsMojangCheck() {
        return enabled() && !lockdown && premiumVerification();
    }

    boolean isProtected(String name) {
        Accounts.Account a = accounts.get(name);
        return a != null && a.premium;
    }

    /**
     * Protects every whitelisted or known player with a Mojang ID (version-4 UUIDs only come from Mojang), keeping
     * the ID they already have. Existing accounts are never changed.
     */
    private int importKnownPlayers() {
        Map<String, OfflinePlayer> candidates = new LinkedHashMap<>();
        for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
            if (p.getName() != null && p.getUniqueId().version() == 4) {
                candidates.putIfAbsent(Accounts.key(p.getName()), p);
            }
        }
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null && p.getUniqueId().version() == 4) {
                candidates.putIfAbsent(Accounts.key(p.getName()), p);
            }
        }
        int added = 0;
        for (OfflinePlayer p : candidates.values()) {
            if (accounts.get(p.getName()) == null) {
                Accounts.Account a = new Accounts.Account();
                a.name = p.getName();
                a.uuid = p.getUniqueId().toString();
                a.premium = true;
                a.mojangUuid = a.uuid;
                accounts.all().put(Accounts.key(a.name), a);
                added++;
            }
        }
        if (added > 0) {
            accounts.save();
        }
        return added;
    }

    // ------------------------------------------------------------------ joining

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!enabled() || Bukkit.getOnlineMode()
                || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String name = event.getName();
        String ip = event.getAddress().getHostAddress();
        Messages msg = plugin.messages();

        if (lockdown) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg.component("login-kick-lockdown"));
            return;
        }
        if (onlineNames.contains(Accounts.key(name))) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg.component("login-kick-already-online"));
            return;
        }

        Accounts.Account acct = accounts.get(name);
        if (acct == null) {
            // New cracked player: keeps the offline ID Paper gave them, has to /register after joining.
            repairWhitelist(name, event.getUniqueId());
            return;
        }

        if (acct.premium && premiumVerification()) {
            VerifiedLogin v = premiumCheck == null ? null : premiumCheck.consume(name, ip);
            if (v == null) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg.component(premiumCheck == null
                        ? "login-kick-check-unavailable" : "login-kick-protected", "player", acct.name));
                return;
            }
            if (acct.mojangUuid == null || !v.mojangId().equals(acct.mojangUuid())) {
                plugin.getLogger().warning("Login: " + name + " passed Mojang's check as account " + v.mojangId()
                        + ", but the name is protected for " + acct.mojangUuid + ". Refused.");
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg.component("login-kick-wrong-account"));
                return;
            }
            PlayerProfile profile = Bukkit.createProfile(acct.uuid(), v.name());
            for (MojangApi.Prop prop : v.properties()) {
                profile.setProperty(prop.signature() == null
                        ? new ProfileProperty(prop.name(), prop.value())
                        : new ProfileProperty(prop.name(), prop.value(), prop.signature()));
            }
            event.setPlayerProfile(profile);
            verifiedThisLogin.add(acct.uuid());
            return;
        }

        // Cracked account, or a protected name while the Mojang check is switched off: always the stored ID.
        if (acct.premium && acct.password == null && !ip.equals(acct.lastIp)) {
            // Password fallback: only someone on the owner's last known IP may set the first password.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg.component("login-kick-needs-password"));
            return;
        }
        event.setPlayerProfile(Bukkit.createProfile(acct.uuid(), name));
        repairWhitelist(name, acct.uuid());
    }

    /**
     * Whitelist by name: /whitelist add stores an ID, which for cracked names can be the wrong one (different
     * capitalisation, or a Mojang account with the same name). If the name is whitelisted, also whitelist the ID
     * this player will actually use.
     */
    private void repairWhitelist(String name, UUID id) {
        if (!Bukkit.hasWhitelist()) {
            return;
        }
        boolean nameListed = false;
        for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
            if (id.equals(p.getUniqueId())) {
                return;
            }
            if (p.getName() != null && p.getName().equalsIgnoreCase(name)) {
                nameListed = true;
            }
        }
        if (!nameListed) {
            return;
        }
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Bukkit.getOfflinePlayer(id).setWhitelisted(true);
                return true;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Login: could not whitelist the ID of " + name + ": " + e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        onlineNames.add(Accounts.key(player.getName()));
        if (!enabled() || lockdown) {
            return;
        }
        UUID id = player.getUniqueId();
        String ip = ipOf(player);
        Accounts.Account acct = accounts.get(player.getName());

        if (Bukkit.getOnlineMode()) {
            // Mojang checked this player: remember the name as protected (never overwrite an existing account)
            if (acct == null) {
                Accounts.Account a = new Accounts.Account();
                a.name = player.getName();
                a.uuid = id.toString();
                a.premium = true;
                a.mojangUuid = a.uuid;
                a.lastIp = ip;
                accounts.put(a);
            } else if (acct.premium && id.equals(acct.uuid())) {
                acct.name = player.getName();
                acct.lastIp = ip;
                accounts.save();
            }
            return;
        }

        if (verifiedThisLogin.remove(id) && acct != null) {
            acct.name = player.getName();
            acct.lastIp = ip;
            accounts.save();
            return; // Mojang-checked protected name
        }
        if (acct != null && acct.password != null && acct.sessionIp != null && acct.sessionIp.equals(ip)
                && acct.sessionUntil > System.currentTimeMillis()) {
            acct.lastIp = ip;
            accounts.save();
            plugin.messages().send(player, "login-auto");
            return;
        }
        lock(player, acct == null || acct.password == null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        onlineNames.remove(Accounts.key(event.getPlayer().getName()));
        locked.remove(id);
        verifiedThisLogin.remove(id);
        attempts.remove(id);
        premiumConfirm.remove(id);
        voiceBlocks.remove(id);
        BukkitTask t = timers.remove(id);
        if (t != null) {
            t.cancel();
        }
    }

    private void lock(Player player, boolean mustRegister) {
        UUID id = player.getUniqueId();
        locked.add(id);
        PermissionAttachment voice = player.addAttachment(plugin);
        voice.setPermission("voicechat.listen", false);
        voice.setPermission("voicechat.speak", false);
        voice.setPermission("voicechat.groups", false);
        voiceBlocks.put(id, voice);

        String key = mustRegister ? "login-please-register" : "login-please-login";
        plugin.messages().send(player, key);
        int timeout = Math.max(15, plugin.getConfig().getInt("login.timeout-seconds", 90));
        long[] elapsed = {0};
        timers.put(id, Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            elapsed[0] += 10;
            if (!locked.contains(id) || !player.isOnline()) {
                BukkitTask t = timers.remove(id);
                if (t != null) {
                    t.cancel();
                }
                return;
            }
            if (elapsed[0] >= timeout) {
                player.kick(plugin.messages().component("login-kick-timeout"));
                return;
            }
            plugin.messages().send(player, key);
        }, 200L, 200L));
    }

    private void unlock(Player player, Accounts.Account acct) {
        UUID id = player.getUniqueId();
        locked.remove(id);
        attempts.remove(id);
        BukkitTask t = timers.remove(id);
        if (t != null) {
            t.cancel();
        }
        PermissionAttachment voice = voiceBlocks.remove(id);
        if (voice != null) {
            player.removeAttachment(voice);
        }
        String ip = ipOf(player);
        acct.lastIp = ip;
        int days = Math.max(0, plugin.getConfig().getInt("login.session-days", 30));
        acct.sessionIp = days > 0 ? ip : null;
        acct.sessionUntil = days > 0 ? System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days) : 0;
        accounts.save();
    }

    boolean isLocked(Player player) {
        return locked.contains(player.getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (!enabled()) {
            msg.send(sender, "login-disabled");
            return true;
        }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("auth")) {
            return admin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return true;
        }
        switch (cmd) {
            case "register" -> register(player, args);
            case "login" -> login(player, args);
            case "changepassword" -> changePassword(player, args);
            case "premium" -> premium(player, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void register(Player player, String[] args) {
        Messages msg = plugin.messages();
        Accounts.Account acct = accounts.get(player.getName());
        if (!isLocked(player) || (acct != null && acct.password != null)) {
            msg.send(player, "login-already-registered");
            return;
        }
        if (args.length != 2) {
            msg.send(player, "login-register-usage");
            return;
        }
        if (!args[0].equals(args[1])) {
            msg.send(player, "login-passwords-differ");
            return;
        }
        int min = Math.max(4, plugin.getConfig().getInt("login.min-password-length", 6));
        if (args[0].length() < min) {
            msg.send(player, "login-password-short", "min", String.valueOf(min));
            return;
        }
        String password = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String hash = Accounts.hash(password);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !isLocked(player)) {
                    return;
                }
                Accounts.Account a = accounts.get(player.getName());
                if (a == null) {
                    a = new Accounts.Account();
                    a.name = player.getName();
                    a.uuid = player.getUniqueId().toString();
                    a.premium = false;
                    accounts.all().put(Accounts.key(a.name), a);
                } else if (a.password != null) {
                    msg.send(player, "login-already-registered");
                    return;
                }
                a.password = hash;
                unlock(player, a);
                plugin.getLogger().info("Login: " + player.getName() + " registered.");
                msg.send(player, "login-registered");
            });
        });
    }

    private void login(Player player, String[] args) {
        Messages msg = plugin.messages();
        Accounts.Account acct = accounts.get(player.getName());
        if (!isLocked(player)) {
            msg.send(player, "login-already-logged-in");
            return;
        }
        if (acct == null || acct.password == null) {
            msg.send(player, "login-please-register");
            return;
        }
        if (args.length != 1) {
            msg.send(player, "login-login-usage");
            return;
        }
        String password = args[0];
        String stored = acct.password;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = Accounts.matches(password, stored);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !isLocked(player)) {
                    return;
                }
                if (ok) {
                    unlock(player, acct);
                    msg.send(player, "login-success");
                    return;
                }
                int max = Math.max(1, plugin.getConfig().getInt("login.max-attempts", 5));
                int used = attempts.merge(player.getUniqueId(), 1, Integer::sum);
                plugin.getLogger().warning("Login: wrong password for " + player.getName() + " from " + ipOf(player)
                        + " (" + used + "/" + max + ").");
                if (used >= max) {
                    player.kick(msg.component("login-kick-attempts"));
                } else {
                    msg.send(player, "login-wrong", "left", String.valueOf(max - used));
                }
            });
        });
    }

    private void changePassword(Player player, String[] args) {
        Messages msg = plugin.messages();
        Accounts.Account acct = accounts.get(player.getName());
        if (isLocked(player) || acct == null || acct.password == null) {
            msg.send(player, "login-no-password");
            return;
        }
        if (args.length != 2) {
            msg.send(player, "login-changepassword-usage");
            return;
        }
        int min = Math.max(4, plugin.getConfig().getInt("login.min-password-length", 6));
        if (args[1].length() < min) {
            msg.send(player, "login-password-short", "min", String.valueOf(min));
            return;
        }
        String old = args[0];
        String fresh = args[1];
        String stored = acct.password;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = Accounts.matches(old, stored);
            String hash = ok ? Accounts.hash(fresh) : null;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!ok) {
                    msg.send(player, "login-wrong", "left", "-");
                    return;
                }
                acct.password = hash;
                accounts.save();
                msg.send(player, "login-password-changed");
            });
        });
    }

    /** A cracked account protects its name with the Mojang account of the same name. Keeps the current ID. */
    private void premium(Player player, String[] args) {
        Messages msg = plugin.messages();
        if (Bukkit.getOnlineMode()) {
            msg.send(player, "login-premium-online-mode");
            return;
        }
        Accounts.Account acct = accounts.get(player.getName());
        if (acct != null && acct.premium) {
            msg.send(player, "login-premium-already");
            return;
        }
        if (isLocked(player) || acct == null) {
            msg.send(player, "login-no-password");
            return;
        }
        UUID id = player.getUniqueId();
        Long until = premiumConfirm.get(id);
        if (args.length == 0 || !args[0].equalsIgnoreCase("confirm") || until == null
                || until < System.currentTimeMillis()) {
            premiumConfirm.put(id, System.currentTimeMillis() + 60_000);
            msg.send(player, "login-premium-explain");
            return;
        }
        premiumConfirm.remove(id);
        String name = player.getName();
        MojangApi.lookupName(name).whenComplete((profile, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || profile.isEmpty()) {
                msg.send(player, "login-premium-not-found", "player", name);
                return;
            }
            acct.premium = true;
            acct.mojangUuid = profile.get().id().toString();
            accounts.save();
            plugin.getLogger().info("Login: " + name + " protected their name (Mojang account "
                    + acct.mojangUuid + ").");
            msg.send(player, "login-premium-done");
        }));
    }

    // ------------------------------------------------------------------ /auth (admins)

    private boolean admin(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (!sender.hasPermission("friendstools.auth.admin")) {
            msg.send(sender, "login-no-permission");
            return true;
        }
        if (args.length == 0) {
            msg.send(sender, "login-auth-usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                List<String> protectedNames = new ArrayList<>();
                List<String> crackedNames = new ArrayList<>();
                for (Accounts.Account a : accounts.all().values()) {
                    (a.premium ? protectedNames : crackedNames).add(a.name);
                }
                msg.sendRaw(sender, "login-auth-list", "protected", String.join(", ", protectedNames),
                        "cracked", String.join(", ", crackedNames));
            }
            case "info" -> {
                if (args.length != 2) {
                    msg.send(sender, "login-auth-usage");
                    return true;
                }
                Accounts.Account a = accounts.get(args[1]);
                if (a == null) {
                    msg.send(sender, "login-auth-unknown", "player", args[1]);
                    return true;
                }
                msg.sendRaw(sender, "login-auth-info", "player", a.name, "uuid", a.uuid,
                        "type", a.premium ? "protected (Mojang check)" : "cracked (password)",
                        "password", a.password != null ? "yes" : "no");
            }
            case "setpassword" -> {
                if (args.length != 3) {
                    msg.send(sender, "login-auth-usage");
                    return true;
                }
                String name = args[1];
                String password = args[2];
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String hash = Accounts.hash(password);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Accounts.Account a = accounts.get(name);
                        if (a == null) {
                            // pre-register a cracked friend, so nobody else can take the name first
                            a = new Accounts.Account();
                            a.name = name;
                            a.uuid = offlineUuid(name).toString();
                            a.premium = false;
                            accounts.all().put(Accounts.key(name), a);
                        }
                        a.password = hash;
                        a.sessionIp = null;
                        a.sessionUntil = 0;
                        accounts.save();
                        msg.send(sender, "login-auth-done");
                    });
                });
            }
            case "unregister" -> {
                if (args.length != 2) {
                    msg.send(sender, "login-auth-usage");
                    return true;
                }
                Accounts.Account a = accounts.get(args[1]);
                if (a == null) {
                    msg.send(sender, "login-auth-unknown", "player", args[1]);
                    return true;
                }
                a.password = null;
                a.sessionIp = null;
                a.sessionUntil = 0;
                accounts.save();
                msg.send(sender, "login-auth-done");
            }
            case "protect" -> {
                if (args.length != 2) {
                    msg.send(sender, "login-auth-usage");
                    return true;
                }
                String name = args[1];
                MojangApi.lookupName(name).whenComplete((profile, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || profile.isEmpty()) {
                        msg.send(sender, "login-premium-not-found", "player", name);
                        return;
                    }
                    MojangApi.Profile pr = profile.get();
                    Accounts.Account a = accounts.get(name);
                    if (a == null) {
                        // a new premium friend: give them their real Mojang ID from the start
                        a = new Accounts.Account();
                        a.name = pr.name();
                        a.uuid = pr.id().toString();
                        accounts.all().put(Accounts.key(name), a);
                        Bukkit.getOfflinePlayer(pr.id()).setWhitelisted(true);
                    }
                    a.premium = true;
                    a.mojangUuid = pr.id().toString();
                    accounts.save();
                    msg.send(sender, "login-auth-done");
                }));
            }
            case "unprotect" -> {
                if (args.length != 2) {
                    msg.send(sender, "login-auth-usage");
                    return true;
                }
                Accounts.Account a = accounts.get(args[1]);
                if (a == null) {
                    msg.send(sender, "login-auth-unknown", "player", args[1]);
                    return true;
                }
                if (a.password == null) {
                    // otherwise anyone could register the name first
                    msg.send(sender, "login-auth-set-password-first");
                    return true;
                }
                a.premium = false;
                accounts.save();
                msg.send(sender, "login-auth-done");
            }
            default -> msg.send(sender, "login-auth-usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("auth") || !sender.hasPermission("friendstools.auth.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "info", "setpassword", "unregister", "protect", "unprotect").stream()
                    .filter(s -> s.startsWith(start)).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("list")) {
            String start = args[1].toLowerCase(Locale.ROOT);
            return accounts.all().values().stream().map(a -> a.name)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(start)).sorted().limit(50).toList();
        }
        return List.of();
    }

    // ------------------------------------------------------------------ nothing works until logged in

    private boolean blocked(Player player, Cancellable event) {
        if (locked.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (locked.contains(event.getPlayer().getUniqueId()) && event.hasChangedPosition()) {
            // can look around, can't walk
            org.bukkit.Location stay = event.getFrom().clone();
            stay.setYaw(event.getTo().getYaw());
            stay.setPitch(event.getTo().getPitch());
            event.setTo(stay);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!locked.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("friendstools:")) {
            label = label.substring("friendstools:".length());
        }
        if (!ALLOWED_WHILE_LOCKED.contains(label)) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "login-please-login-first");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (locked.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        event.viewers().removeIf(a -> a instanceof Player p && locked.contains(p.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p) {
            blocked(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        blocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            blocked(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            blocked(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p) {
            blocked(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            blocked(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            blocked(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p) {
            blocked(p, event);
        }
    }

    // ------------------------------------------------------------------ helpers

    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String ipOf(Player player) {
        return player.getAddress() == null ? "" : player.getAddress().getAddress().getHostAddress();
    }
}
