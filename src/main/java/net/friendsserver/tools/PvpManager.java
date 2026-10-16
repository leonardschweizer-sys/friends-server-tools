package net.friendsserver.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /pvp: every player decides for themselves whether they want to fight other players. Off by default.
 *
 * <p>Damage between two players only goes through when both have PvP on. "Damage by a player" includes everything
 * the player is responsible for: hits, sweep attacks, arrows, tridents and other projectiles, splash and lingering
 * potions, tamed wolves (and other pets), fire, TNT they lit, end crystals they hit, channeling lightning, and
 * knockback from wind charges, maces and fishing rods. Pets of players with PvP off are protected the same way.</p>
 */
final class PvpManager implements Listener, CommandExecutor, TabCompleter {

    /** Shows the PvP marker above players' heads (implemented with PacketEvents). */
    interface Nametags {
        void sync();

        void viewerJoined(Player player);

        void viewerQuit(Player player);

        void close();
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int MAX_OWNER_DEPTH = 4;

    private final FriendsTools plugin;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    /** Players with PvP on. Everyone else has it off. */
    private final Set<UUID> pvpOn = ConcurrentHashMap.newKeySet();
    /** Last time a player hit or was hit by another player (PvP), for the combat timer. */
    private final Map<UUID, Long> lastFight = new HashMap<>();
    private final Map<UUID, Long> lastNotice = new HashMap<>();
    private Nametags nametags;
    private Component marker = Component.empty();

    PvpManager(FriendsTools plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("pvp.json");
    }

    // ------------------------------------------------------------------ setup

    void start() {
        load();
        reload();
    }

    /** (Re)reads the config: marker and the above-head marker. */
    void reload() {
        String raw = plugin.getConfig().getString("pvp.marker", "<red>⚔</red>");
        try {
            marker = MM.deserialize(raw == null ? "" : raw);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("pvp.marker is not valid MiniMessage, using the default: " + e.getMessage());
            marker = MM.deserialize("<red>⚔</red>");
        }

        boolean wantNametags = enabled() && plugin.getConfig().getBoolean("pvp.marker-above-head", true);
        if (wantNametags && nametags == null) {
            if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
                try {
                    nametags = PvpNametags.create(plugin, this);
                } catch (RuntimeException | LinkageError e) {
                    plugin.getLogger().warning("PvP: could not start the marker above heads (" + e
                            + "). The marker still shows in the tab list.");
                }
            } else {
                plugin.getLogger().warning("PvP: PacketEvents is not installed, so the PvP marker only shows in"
                        + " the tab list, not above heads.");
            }
        } else if (!wantNametags && nametags != null) {
            nametags.close();
            nametags = null;
        }
        if (nametags != null) {
            nametags.sync();
        }
        plugin.refreshTab();
    }

    void stop() {
        if (nametags != null) {
            nametags.close();
            nametags = null;
        }
        saveNow();
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("pvp.enabled", true);
    }

    boolean isOn(UUID id) {
        return pvpOn.contains(id);
    }

    /** The marker shown next to the name of a player with PvP on, or null if none should be shown. */
    Component markerFor(Player player, boolean forTab) {
        if (!enabled() || !isOn(player.getUniqueId())) {
            return null;
        }
        if (forTab && !plugin.getConfig().getBoolean("pvp.marker-in-tab", true)) {
            return null;
        }
        return marker;
    }

    // ------------------------------------------------------------------ /pvp

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (!enabled()) {
            msg.send(sender, "pvp-disabled");
            return true;
        }

        // /pvp <on|off> <player> (admin)
        if (args.length == 2) {
            if (!sender.hasPermission("friendstools.pvp.others")) {
                msg.send(sender, "pvp-usage");
                return true;
            }
            Boolean value = parse(args[0]);
            if (value == null) {
                msg.send(sender, "pvp-usage");
                return true;
            }
            OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                target = Bukkit.getOfflinePlayerIfCached(args[1]);
            }
            if (target == null) {
                msg.send(sender, "ping-not-online", "player", args[1]);
                return true;
            }
            set(target.getUniqueId(), value);
            String name = target.getName() == null ? args[1] : target.getName();
            msg.send(sender, value ? "pvp-other-on" : "pvp-other-off", "player", name);
            if (target instanceof Player online && online != sender) {
                msg.send(online, value ? "pvp-on" : "pvp-off");
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return true;
        }
        UUID id = player.getUniqueId();
        boolean wanted;
        if (args.length == 0) {
            wanted = !isOn(id);
        } else if (args.length == 1 && parse(args[0]) != null) {
            wanted = parse(args[0]);
        } else {
            msg.send(player, "pvp-usage");
            return true;
        }

        if (wanted == isOn(id)) {
            msg.send(player, wanted ? "pvp-already-on" : "pvp-already-off");
            return true;
        }
        if (!wanted) {
            long left = combatSecondsLeft(id);
            if (left > 0) {
                msg.send(player, "pvp-in-combat", "seconds", String.valueOf(left));
                return true;
            }
        }
        set(id, wanted);
        msg.send(player, wanted ? "pvp-on" : "pvp-off");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            return List.of("on", "off").stream().filter(s -> s.startsWith(start)).toList();
        }
        if (args.length == 2 && sender.hasPermission("friendstools.pvp.others")) {
            String start = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        return List.of();
    }

    private static Boolean parse(String arg) {
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "on", "true", "an", "1" -> Boolean.TRUE;
            case "off", "false", "aus", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private void set(UUID id, boolean on) {
        boolean changed = on ? pvpOn.add(id) : pvpOn.remove(id);
        if (!changed) {
            return;
        }
        save();
        Player player = Bukkit.getPlayer(id);
        if (!on && player != null) {
            calmPetsTargeting(player);
        }
        if (nametags != null) {
            nametags.sync();
        }
        plugin.refreshTab();
    }

    /** Tamed animals that were already attacking this player stop (their bites are blocked anyway). */
    private void calmPetsTargeting(Player player) {
        for (World world : Bukkit.getWorlds()) {
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                if (mob instanceof Tameable && player.equals(mob.getTarget())
                        && blocked(responsible(mob, 0), player.getUniqueId())) {
                    mob.setTarget(null);
                }
            }
        }
    }

    private long combatSecondsLeft(UUID id) {
        Long last = lastFight.get(id);
        if (last == null) {
            return 0;
        }
        long seconds = Math.max(0, plugin.getConfig().getLong("pvp.combat-seconds", 30));
        long leftMillis = last + seconds * 1000 - System.currentTimeMillis();
        return leftMillis <= 0 ? 0 : (leftMillis + 999) / 1000;
    }

    // ------------------------------------------------------------------ who is responsible

    /**
     * The player responsible for an entity: the player itself, the shooter of a projectile, the thrower of a potion
     * (also its lingering cloud), the owner of a tamed animal, whoever lit a TNT or called a channeling lightning.
     */
    private UUID responsible(Entity entity, int depth) {
        if (entity == null || depth > MAX_OWNER_DEPTH) {
            return null;
        }
        if (entity instanceof Player player) {
            return player.getUniqueId();
        }
        if (entity instanceof Tameable pet) {
            return pet.isTamed() ? pet.getOwnerUniqueId() : null;
        }
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity source && source != entity ? responsible(source, depth + 1) : null;
        }
        if (entity instanceof AreaEffectCloud cloud) {
            ProjectileSource source = cloud.getSource();
            return source instanceof Entity e ? responsible(e, depth + 1) : null;
        }
        if (entity instanceof TNTPrimed tnt) {
            return responsible(tnt.getSource(), depth + 1);
        }
        if (entity instanceof LightningStrike lightning) {
            return responsible(lightning.getCausingPlayer(), depth + 1);
        }
        return null;
    }

    /** Whose PvP setting protects this entity: a player protects themselves and their tamed animals. */
    private UUID protectedOwner(Entity entity) {
        if (entity instanceof Player player) {
            return player.getUniqueId();
        }
        if (entity instanceof Tameable pet && pet.isTamed()
                && plugin.getConfig().getBoolean("pvp.protect-pets", true)) {
            return pet.getOwnerUniqueId();
        }
        return null;
    }

    /** True if something {@code attacker} is responsible for may not hurt what {@code victim} protects. */
    private boolean blocked(UUID attacker, UUID victim) {
        if (attacker == null || victim == null || attacker.equals(victim) || !enabled()) {
            return false;
        }
        return !(pvpOn.contains(attacker) && pvpOn.contains(victim));
    }

    private UUID attackerOf(EntityDamageByEntityEvent event) {
        UUID id = null;
        try {
            id = responsible(event.getDamageSource().getCausingEntity(), 0);
        } catch (RuntimeException ignored) {
            // no damage source info
        }
        return id != null ? id : responsible(event.getDamager(), 0);
    }

    // ------------------------------------------------------------------ protection

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        UUID victim = protectedOwner(event.getEntity());
        if (victim == null) {
            return;
        }
        UUID attacker = attackerOf(event);
        if (!blocked(attacker, victim)) {
            return;
        }
        event.setCancelled(true);
        notifyAttacker(attacker, victim, event.getEntity());
    }

    /** Remembers real PvP hits for the combat timer (can't turn PvP off right after a fight). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDone(EntityDamageByEntityEvent event) {
        UUID victim = protectedOwner(event.getEntity());
        UUID attacker = attackerOf(event);
        if (victim == null || attacker == null || attacker.equals(victim)) {
            return;
        }
        long now = System.currentTimeMillis();
        lastFight.put(attacker, now);
        lastFight.put(victim, now);
    }

    /** Tamed wolves (and other pets) don't go after protected players or their pets. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() == null) {
            return;
        }
        UUID attacker = responsible(event.getEntity(), 0);
        if (attacker != null && blocked(attacker, protectedOwner(event.getTarget()))) {
            event.setCancelled(true);
        }
    }

    /** Splash potions: harmful ones have no effect on protected players and pets (healing ones still work). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        UUID attacker = responsible(potion, 0);
        if (attacker == null || !isHarmful(potion.getEffects())) {
            return;
        }
        for (LivingEntity hit : new ArrayList<>(event.getAffectedEntities())) {
            if (blocked(attacker, protectedOwner(hit))) {
                event.setIntensity(hit, 0);
            }
        }
    }

    /** Lingering potion clouds: same as splash potions. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        UUID attacker = responsible(cloud, 0);
        if (attacker == null) {
            return;
        }
        List<PotionEffect> effects = new ArrayList<>(cloud.getCustomEffects());
        PotionType base = cloud.getBasePotionType();
        if (base != null) {
            effects.addAll(base.getPotionEffects());
        }
        if (!isHarmful(effects)) {
            return;
        }
        event.getAffectedEntities().removeIf(entity -> blocked(attacker, protectedOwner(entity)));
    }

    private static boolean isHarmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    /** Fire aspect, flame arrows and the like. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent event) {
        if (blocked(responsible(event.getCombuster(), 0), protectedOwner(event.getEntity()))) {
            event.setCancelled(true);
        }
    }

    /** Knockback without damage: wind charges, mace smash, explosions. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKnockback(EntityPushedByEntityAttackEvent event) {
        if (blocked(responsible(event.getPushedBy(), 0), protectedOwner(event.getEntity()))) {
            event.setCancelled(true);
        }
    }

    /** Fishing rods can't hook protected players or pets (the bobber flies through)... */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof FishHook hook && event.getHitEntity() != null
                && blocked(responsible(hook, 0), protectedOwner(event.getHitEntity()))) {
            event.setCancelled(true);
        }
    }

    /** ...or pull them (if one was hooked anyway, e.g. right before turning PvP off). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() != null
                && blocked(event.getPlayer().getUniqueId(), protectedOwner(event.getCaught()))) {
            event.setCancelled(true);
            event.getHook().remove();
        }
    }

    private void notifyAttacker(UUID attacker, UUID victim, Entity hit) {
        Player player = Bukkit.getPlayer(attacker);
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(attacker);
        if (last != null && now - last < 1500) {
            return;
        }
        lastNotice.put(attacker, now);
        if (!isOn(attacker)) {
            plugin.messages().actionBar(player, "pvp-blocked-self");
        } else {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(victim);
            String name = owner.getName() != null ? owner.getName() : hit.getName();
            plugin.messages().actionBar(player, hit instanceof Player ? "pvp-blocked-other" : "pvp-blocked-pet",
                    "player", name);
        }
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (nametags != null) {
            nametags.viewerJoined(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastNotice.remove(id);
        if (nametags != null) {
            nametags.viewerQuit(event.getPlayer());
        }
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Type type = new TypeToken<List<String>>() { }.getType();
            List<String> ids = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (ids != null) {
                for (String s : ids) {
                    try {
                        pvpOn.add(UUID.fromString(s));
                    } catch (IllegalArgumentException ignored) {
                        // skip broken entries
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not read pvp.json, everyone starts with PvP off: " + e);
        }
    }

    private String json() {
        Set<String> ids = new TreeSet<>();
        pvpOn.forEach(id -> ids.add(id.toString()));
        return gson.toJson(ids);
    }

    private void save() {
        String json = json();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(json));
    }

    private void saveNow() {
        write(json());
    }

    private synchronized void write(String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("pvp.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save pvp.json: " + e);
        }
    }
}
