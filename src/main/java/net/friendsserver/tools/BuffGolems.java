package net.friendsserver.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Prize iron golems with a custom look. Plain Minecraft can't load custom mob models, so the golem itself is made
 * invisible and carries display entities (one per body piece, models from the server pack) that are moved like
 * the vanilla iron golem model: walking legs and arms, head looking around, arms swinging on attacks.
 *
 * <p>The piece layout comes from friendstools/rig_&lt;name&gt;.json inside pack.zip. Display entities are never
 * saved; they are rebuilt whenever a prize golem is loaded. When the golem dies it drops its egg again.</p>
 */
final class BuffGolems implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record Piece(Key model, String part, Vector3f offset, Quaternionf rotation) {
    }

    private record RigDef(Map<String, Vector3f> parts, List<Piece> pieces) {
    }

    /** A loaded prize golem and its display pieces. */
    private static final class Rig {
        final IronGolem golem;
        final RigDef def;
        final List<ItemDisplay> displays = new ArrayList<>();
        final List<Matrix4f> last = new ArrayList<>();
        double lastX;
        double lastZ;
        float walkPos;
        float walkSpeed;
        int attackTicks;

        Rig(IronGolem golem, RigDef def) {
            this.golem = golem;
            this.def = def;
            this.lastX = golem.getX();
            this.lastZ = golem.getZ();
        }
    }

    private final FriendsTools plugin;
    private final CustomItems items;
    private final NamespacedKey golemKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey eggKey;
    private final NamespacedKey earnedByKey;
    private final NamespacedKey earnedAtKey;
    private final NamespacedKey pieceKey;
    private final Map<String, RigDef> rigDefs = new HashMap<>();
    private final Map<UUID, Rig> rigs = new HashMap<>();
    private BukkitTask task;

    BuffGolems(FriendsTools plugin, CustomItems items) {
        this.plugin = plugin;
        this.items = items;
        this.golemKey = new NamespacedKey(plugin, "buff_golem");
        this.ownerKey = new NamespacedKey(plugin, "golem_owner");
        this.eggKey = new NamespacedKey(plugin, "golem_egg");
        this.earnedByKey = new NamespacedKey(plugin, "golem_earned_by");
        this.earnedAtKey = new NamespacedKey(plugin, "golem_earned_at");
        this.pieceKey = new NamespacedKey(plugin, "golem_piece");
    }

    /** (Re)reads the rigs from the server pack and rebuilds every loaded prize golem. */
    void start() {
        stop();
        loadRigs();
        for (World world : Bukkit.getWorlds()) {
            for (IronGolem golem : world.getEntitiesByClass(IronGolem.class)) {
                if (isPrize(golem)) {
                    attach(golem);
                }
            }
            // leftovers from a crash (pieces are never saved, but be safe)
            for (ItemDisplay d : world.getEntitiesByClass(ItemDisplay.class)) {
                if (d.getPersistentDataContainer().has(pieceKey) && !isTracked(d)) {
                    d.remove();
                }
            }
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Rig rig : rigs.values()) {
            rig.displays.forEach(Entity::remove);
        }
        rigs.clear();
    }

    private boolean isTracked(ItemDisplay d) {
        for (Rig rig : rigs.values()) {
            if (rig.displays.contains(d)) {
                return true;
            }
        }
        return false;
    }

    private void loadRigs() {
        rigDefs.clear();
        ServerPack pack = plugin.serverPack();
        byte[] zip = pack == null ? null : pack.bytes();
        if (zip == null) {
            return;
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.startsWith("friendstools/rig_") || !name.endsWith(".json")) {
                    continue;
                }
                String id = name.substring("friendstools/rig_".length(), name.length() - ".json".length());
                try {
                    rigDefs.put(id, parseRig(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Golem rig " + name + " in the server pack is broken: " + ex);
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not read the golem rigs from the server pack: " + ex);
        }
    }

    private static RigDef parseRig(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Vector3f> parts = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("parts").entrySet()) {
            parts.put(e.getKey(), vec(e.getValue().getAsJsonArray()));
        }
        List<Piece> pieces = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("pieces")) {
            JsonObject p = el.getAsJsonObject();
            JsonArray q = p.getAsJsonArray("rotation");
            String part = p.get("part").getAsString();
            if (!parts.containsKey(part)) {
                throw new IllegalArgumentException("unknown part " + part);
            }
            pieces.add(new Piece(Key.key(p.get("model").getAsString()), part, vec(p.getAsJsonArray("offset")),
                    new Quaternionf(q.get(0).getAsFloat(), q.get(1).getAsFloat(), q.get(2).getAsFloat(),
                            q.get(3).getAsFloat())));
        }
        return new RigDef(parts, pieces);
    }

    private static Vector3f vec(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    private boolean isPrize(Entity e) {
        return e instanceof IronGolem && e.getPersistentDataContainer().has(golemKey);
    }

    // ---- spawning from the egg

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onUseEgg(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        ItemStack item = event.getItem();
        CustomItems.Definition def = items.definition(items.idOf(item));
        if (def == null || def.rig() == null) {
            return;
        }
        // a protection plugin already said no
        boolean denied = event.useItemInHand() == org.bukkit.event.Event.Result.DENY;
        // Never let vanilla use it (no normal golem, no spawner change)
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        Block clicked = event.getClickedBlock();
        if (denied || event.getAction() != Action.RIGHT_CLICK_BLOCK || clicked == null) {
            return;
        }
        Block at = clicked.isPassable() ? clicked : clicked.getRelative(event.getBlockFace());
        Player player = event.getPlayer();
        Location loc = at.getLocation().add(0.5, 0, 0.5);
        loc.setYaw(player.getLocation().getYaw() + 180f);
        String[] earned = items.earnedOf(item);
        spawn(loc, def, player, earned[0], earned[1]);
        if (player.getGameMode() != GameMode.CREATIVE) {
            EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
            player.getInventory().getItem(hand).subtract();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEggOnEntity(PlayerInteractEntityEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        CustomItems.Definition def = items.definition(items.idOf(item));
        if (def != null && def.rig() != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDispense(BlockDispenseEvent event) {
        CustomItems.Definition def = items.definition(items.idOf(event.getItem()));
        if (def != null && def.rig() != null) {
            event.setCancelled(true);
        }
    }

    private void spawn(Location loc, CustomItems.Definition def, Player owner, String earnedBy, String earnedAt) {
        double health = plugin.getConfig().getDouble("buff-golem.health", 200);
        double damage = plugin.getConfig().getDouble("buff-golem.attack-damage", 22);
        IronGolem golem = loc.getWorld().spawn(loc, IronGolem.class, g -> {
            g.setPlayerCreated(true);
            g.setRemoveWhenFarAway(false);
            g.customName(MM.deserialize(def.name()));
            g.setCustomNameVisible(false);
            AttributeInstance max = g.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                max.setBaseValue(health);
                g.setHealth(health);
            }
            AttributeInstance attack = g.getAttribute(Attribute.ATTACK_DAMAGE);
            if (attack != null) {
                attack.setBaseValue(damage);
            }
            PersistentDataContainer pdc = g.getPersistentDataContainer();
            pdc.set(golemKey, PersistentDataType.STRING, def.rig());
            pdc.set(eggKey, PersistentDataType.STRING, def.id());
            pdc.set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
            pdc.set(earnedByKey, PersistentDataType.STRING, earnedBy);
            pdc.set(earnedAtKey, PersistentDataType.STRING, earnedAt);
        });
        loc.getWorld().spawnParticle(Particle.POOF, loc.clone().add(0, 1.2, 0), 30, 0.6, 1.0, 0.6, 0.02);
        attach(golem);
    }

    // ---- rig life cycle

    private void attach(IronGolem golem) {
        if (rigs.containsKey(golem.getUniqueId()) || !golem.isValid()) {
            return;
        }
        String rigId = golem.getPersistentDataContainer().get(golemKey, PersistentDataType.STRING);
        RigDef def = rigDefs.get(rigId);
        if (def == null) {
            // No pack (or rig missing): show the normal golem instead of an invisible one
            golem.setInvisible(false);
            return;
        }
        // stale pieces from before (e.g. after /friendstools reload)
        for (Entity p : golem.getPassengers()) {
            if (p.getPersistentDataContainer().has(pieceKey)) {
                p.remove();
            }
        }
        golem.setInvisible(true);
        Rig rig = new Rig(golem, def);
        for (Piece piece : def.pieces()) {
            ItemStack look = ItemStack.of(Material.PAPER);
            look.setData(DataComponentTypes.ITEM_MODEL, piece.model());
            ItemDisplay d = golem.getWorld().spawn(golem.getLocation(), ItemDisplay.class, e -> {
                e.setItemStack(look);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setPersistent(false);
                e.setBillboard(Display.Billboard.FIXED);
                e.setInterpolationDuration(2);
                e.setViewRange(1.5f);
                e.getPersistentDataContainer().set(pieceKey, PersistentDataType.BYTE, (byte) 1);
            });
            golem.addPassenger(d);
            rig.displays.add(d);
            rig.last.add(null);
        }
        rig.displays.getFirst().setShadowRadius(0.9f);
        rig.displays.getFirst().setShadowStrength(0.6f);
        rigs.put(golem.getUniqueId(), rig);
        update(rig);
    }

    private void detach(UUID golemId) {
        Rig rig = rigs.remove(golemId);
        if (rig != null) {
            rig.displays.forEach(Entity::remove);
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (isPrize(e)) {
                IronGolem golem = (IronGolem) e;
                Bukkit.getScheduler().runTask(plugin, () -> attach(golem));
            } else if (e instanceof ItemDisplay && e.getPersistentDataContainer().has(pieceKey)) {
                e.remove(); // a piece that got saved somehow
            }
        }
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            if (isPrize(e)) {
                detach(e.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Rig rig = rigs.get(event.getDamager().getUniqueId());
        if (rig != null) {
            rig.attackTicks = 10;
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!isPrize(event.getEntity())) {
            return;
        }
        detach(event.getEntity().getUniqueId());
        PersistentDataContainer pdc = event.getEntity().getPersistentDataContainer();
        CustomItems.Definition egg = items.definition(pdc.get(eggKey, PersistentDataType.STRING));
        if (egg != null) {
            // The prize is never lost: the egg comes back
            event.getDrops().add(items.create(egg, pdc.getOrDefault(earnedByKey, PersistentDataType.STRING, ""),
                    pdc.getOrDefault(earnedAtKey, PersistentDataType.STRING, "")));
        }
        Location loc = event.getEntity().getLocation().add(0, 1.2, 0);
        loc.getWorld().spawnParticle(Particle.POOF, loc, 40, 0.6, 1.0, 0.6, 0.03);
    }

    // ---- animation (same rules as the vanilla iron golem model)

    private void tick() {
        Iterator<Rig> it = rigs.values().iterator();
        while (it.hasNext()) {
            Rig rig = it.next();
            if (!rig.golem.isValid() || rig.golem.isDead()) {
                rig.displays.forEach(Entity::remove);
                it.remove();
                // through a portal the golem becomes a new entity with the same id: rebuild it there
                UUID id = rig.golem.getUniqueId();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (Bukkit.getEntity(id) instanceof IronGolem moved && moved.isValid() && isPrize(moved)) {
                        attach(moved);
                    }
                });
                continue;
            }
            update(rig);
        }
    }

    private void update(Rig rig) {
        IronGolem g = rig.golem;
        if (!g.isInvisible()) {
            g.setInvisible(true);
        }
        // walk animation: same smoothing as LivingEntity.updateWalkAnimation
        double dx = g.getX() - rig.lastX;
        double dz = g.getZ() - rig.lastZ;
        rig.lastX = g.getX();
        rig.lastZ = g.getZ();
        float target = (float) Math.min(Math.sqrt(dx * dx + dz * dz) * 4.0, 1.0);
        rig.walkSpeed += (target - rig.walkSpeed) * 0.4f;
        rig.walkPos += rig.walkSpeed;
        float wave = triangleWave(rig.walkPos, 13f);
        float speed = Math.min(rig.walkSpeed, 1f);

        float armRight;
        float armLeft;
        if (rig.attackTicks > 0) {
            armRight = armLeft = -2.0f + 1.5f * triangleWave(rig.attackTicks, 10f);
            rig.attackTicks--;
        } else {
            armRight = (-0.2f + 1.5f * wave) * speed;
            armLeft = (-0.2f - 1.5f * wave) * speed;
        }
        float bodyYaw = g.getBodyYaw();
        Location head = g.getLocation(); // yaw = head yaw for mobs
        float netHeadYaw = wrapDegrees(head.getYaw() - bodyYaw);

        Quaternionf yaw = new Quaternionf().rotationY((float) Math.toRadians(-bodyYaw));
        if (rig.walkSpeed >= 0.01f) {
            // the side-to-side sway of a walking golem (IronGolemRenderer.setupRotations)
            yaw.rotateZ((float) Math.toRadians(-6.5f * triangleWave(rig.walkPos + 6f, 13f)));
        }
        Map<String, Quaternionf> anim = Map.of(
                "head", new Quaternionf().rotationY((float) Math.toRadians(-netHeadYaw))
                        .rotateX((float) Math.toRadians(head.getPitch())),
                "body", new Quaternionf(),
                "right_arm", new Quaternionf().rotationX(armRight),
                "left_arm", new Quaternionf().rotationX(armLeft),
                "right_leg", new Quaternionf().rotationX(-1.5f * wave * speed),
                "left_leg", new Quaternionf().rotationX(1.5f * wave * speed));
        float rideHeight = (float) g.getHeight(); // pieces ride at the top of the golem's hitbox

        List<Piece> pieces = rig.def.pieces();
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            ItemDisplay d = rig.displays.get(i);
            if (!d.isValid()) {
                continue;
            }
            Matrix4f m = new Matrix4f()
                    .translate(0, -rideHeight, 0)
                    .rotate(yaw)
                    .translate(rig.def.parts().get(p.part()))
                    .rotate(anim.getOrDefault(p.part(), new Quaternionf()))
                    .translate(p.offset())
                    .rotate(p.rotation())
                    .rotateY((float) Math.PI); // item displays turn their item around; undo that
            Matrix4f before = rig.last.get(i);
            if (before != null && before.equals(m, 1e-4f)) {
                continue;
            }
            rig.last.set(i, m);
            d.setTransformationMatrix(m);
            d.setInterpolationDelay(0); // (re)starts the smooth move to the new pose
        }
    }

    private static float triangleWave(float x, float period) {
        return (Math.abs(x % period - period * 0.5f) - period * 0.25f) / (period * 0.25f);
    }

    private static float wrapDegrees(float deg) {
        float f = deg % 360f;
        if (f >= 180f) {
            f -= 360f;
        }
        if (f < -180f) {
            f += 360f;
        }
        return f;
    }
}
