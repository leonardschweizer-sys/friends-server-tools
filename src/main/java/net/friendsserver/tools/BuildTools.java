package net.friendsserver.tools;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Hangable;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Snowable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Piston;
import org.bukkit.block.data.type.PistonHead;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.data.type.Wall;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCanBuildEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Survival versions of Axiom's Force Place, No Updates and Tinker, each switched on and off by command
 * (only in the survival worlds, build-tools.worlds). Blocks still cost items like normal.
 * <ul>
 *   <li>/tinker: right-click a block with an empty hand to change its shape like a debug stick (facing, stair shape,
 *       fence/wall sides, rail shape, slab top/bottom, ...); sneak + right-click picks what to change. Things that
 *       would make items (double slabs, candles, layers, crop age, ...) and two-block blocks (doors, beds, tall
 *       flowers) can't be changed.</li>
 *   <li>/noupdates: blocks you place don't update the blocks around them, and gravity blocks stay where you put
 *       them. Only placing; breaking stays normal (breaking without updates could duplicate doors and such).</li>
 *   <li>/forceplace: blocks you place may go where they normally can't stay: flowers and crops on any block, carpets,
 *       rails and redstone in the air, floating torches, lanterns, buttons, levers and ladders. Not inside players
 *       or mobs.</li>
 * </ul>
 */
final class BuildTools implements Listener, TabExecutor {

    private final FriendsTools plugin;
    private final Set<UUID> tinker = new HashSet<>();
    private final Set<UUID> noUpdates = new HashSet<>();
    private final Set<UUID> forcePlace = new HashSet<>();
    private final Map<UUID, String> selected = new HashMap<>();
    /** Blocks just placed with /noupdates or /forceplace -> tick until which their updates are blocked. */
    private final Map<String, Integer> guarded = new HashMap<>();
    /** /forceplace clicks that vanilla may still refuse (torches in the air, ...): handled next tick. */
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final PhysicsGuard physicsGuard = new PhysicsGuard();
    private boolean guardRegistered;

    private record Pending(Block target, BlockFace face, Material type, int tick) {
    }

    BuildTools(FriendsTools plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        String tool = command.getName().toLowerCase(Locale.ROOT);
        Set<UUID> set = switch (tool) {
            case "tinker" -> tinker;
            case "noupdates" -> noUpdates;
            default -> forcePlace;
        };
        boolean on = args.length > 0 ? args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("an")
                : !set.contains(p.getUniqueId());
        if (on) {
            set.add(p.getUniqueId());
            plugin.messages().send(p, "build-" + tool + "-on");
            if (!allowedWorld(p)) {
                plugin.messages().send(p, "build-tools-wrong-world");
            }
        } else {
            set.remove(p.getUniqueId());
            plugin.messages().send(p, "build-tool-off", "tool", "/" + tool);
        }
        updateGuardListener();
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("on", "off").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList()
                : List.of();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        tinker.remove(id);
        noUpdates.remove(id);
        forcePlace.remove(id);
        selected.remove(id);
        pending.remove(id);
        updateGuardListener();
    }

    private boolean allowedWorld(Player p) {
        List<String> worlds = plugin.getConfig().getStringList("build-tools.worlds");
        return worlds.isEmpty() || worlds.stream().anyMatch(w -> w.equalsIgnoreCase(p.getWorld().getName()));
    }

    private boolean active(Set<UUID> set, Player p) {
        return set.contains(p.getUniqueId()) && allowedWorld(p) && p.getGameMode() != GameMode.ADVENTURE
                && p.getGameMode() != GameMode.SPECTATOR;
    }

    // ------------------------------------------------------------------ tinker

    private record Prop(String name, Function<BlockData, String> value, UnaryOperator<BlockData> next) {
    }

    private static final Set<Material> NO_TINKER = EnumSet.noneOf(Material.class);

    static {
        for (String name : List.of("GLOW_LICHEN", "SCULK_VEIN", "RESIN_CLUMP", "VINE", "CAVE_VINES", "CAVE_VINES_PLANT",
                "TWISTING_VINES", "WEEPING_VINES", "CHORUS_PLANT", "CHORUS_FLOWER", "TRIPWIRE", "TRIPWIRE_HOOK",
                "END_PORTAL_FRAME", "BIG_DRIPLEAF", "BIG_DRIPLEAF_STEM", "PITCHER_CROP", "SCAFFOLDING")) {
            Material m = Material.matchMaterial(name);
            if (m != null) {
                NO_TINKER.add(m);
            }
        }
    }

    /** What can be changed on this block. Never anything that adds items or splits two-block blocks. */
    private static List<Prop> props(BlockData d) {
        List<Prop> out = new ArrayList<>();
        Material m = d.getMaterial();
        if (NO_TINKER.contains(m) || d instanceof Bed || d instanceof Door || d instanceof PistonHead
                || d instanceof Piston piston && piston.isExtended()
                || d instanceof Bisected && !(d instanceof Stairs) && !(d instanceof TrapDoor)) {
            return out;
        }
        if (d instanceof Directional dir && dir.getFaces().size() > 1
                && !(d instanceof Chest chest && chest.getType() != Chest.Type.SINGLE)) {
            List<BlockFace> faces = sortedFaces(dir.getFaces());
            out.add(new Prop("facing", x -> name(((Directional) x).getFacing()), x -> {
                Directional c = (Directional) x.clone();
                c.setFacing(cycle(faces, c.getFacing()));
                return c;
            }));
        }
        if (d instanceof Orientable o && o.getAxes().size() > 1) {
            List<Axis> axes = new ArrayList<>(o.getAxes());
            axes.sort(Comparator.naturalOrder());
            out.add(new Prop("axis", x -> name(((Orientable) x).getAxis()), x -> {
                Orientable c = (Orientable) x.clone();
                c.setAxis(cycle(axes, c.getAxis()));
                return c;
            }));
        }
        if (d instanceof Rotatable) {
            List<BlockFace> rotations = List.of(BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST,
                    BlockFace.WEST_SOUTH_WEST, BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST,
                    BlockFace.NORTH_NORTH_WEST, BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST,
                    BlockFace.EAST_NORTH_EAST, BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST,
                    BlockFace.SOUTH_SOUTH_EAST);
            out.add(new Prop("rotation", x -> name(((Rotatable) x).getRotation()), x -> {
                Rotatable c = (Rotatable) x.clone();
                c.setRotation(cycle(rotations, c.getRotation()));
                return c;
            }));
        }
        if (d instanceof Stairs) {
            out.add(new Prop("shape", x -> name(((Stairs) x).getShape()), x -> {
                Stairs c = (Stairs) x.clone();
                c.setShape(cycle(List.of(Stairs.Shape.values()), c.getShape()));
                return c;
            }));
        }
        if (d instanceof Stairs || d instanceof TrapDoor) {
            out.add(new Prop("half", x -> name(((Bisected) x).getHalf()), x -> {
                Bisected c = (Bisected) x.clone();
                c.setHalf(c.getHalf() == Bisected.Half.TOP ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
                return c;
            }));
        }
        if (d instanceof Rail rail && rail.getShapes().size() > 1) {
            List<Rail.Shape> shapes = new ArrayList<>(rail.getShapes());
            shapes.sort(Comparator.naturalOrder());
            out.add(new Prop("shape", x -> name(((Rail) x).getShape()), x -> {
                Rail c = (Rail) x.clone();
                c.setShape(cycle(shapes, c.getShape()));
                return c;
            }));
        }
        if (d instanceof Slab slab && slab.getType() != Slab.Type.DOUBLE) {
            out.add(new Prop("type", x -> name(((Slab) x).getType()), x -> {
                Slab c = (Slab) x.clone();
                c.setType(c.getType() == Slab.Type.TOP ? Slab.Type.BOTTOM : Slab.Type.TOP);
                return c;
            }));
        }
        if (d instanceof Openable) {
            out.add(new Prop("open", x -> String.valueOf(((Openable) x).isOpen()), x -> {
                Openable c = (Openable) x.clone();
                c.setOpen(!c.isOpen());
                return c;
            }));
        }
        if (d instanceof MultipleFacing mf) {
            for (BlockFace face : sortedFaces(mf.getAllowedFaces())) {
                out.add(new Prop(name(face), x -> String.valueOf(((MultipleFacing) x).hasFace(face)), x -> {
                    MultipleFacing c = (MultipleFacing) x.clone();
                    c.setFace(face, !c.hasFace(face));
                    return c;
                }));
            }
        }
        if (d instanceof Wall) {
            for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
                out.add(new Prop(name(face), x -> name(((Wall) x).getHeight(face)), x -> {
                    Wall c = (Wall) x.clone();
                    c.setHeight(face, cycle(List.of(Wall.Height.values()), c.getHeight(face)));
                    return c;
                }));
            }
            out.add(new Prop("up", x -> String.valueOf(((Wall) x).isUp()), x -> {
                Wall c = (Wall) x.clone();
                c.setUp(!c.isUp());
                return c;
            }));
        }
        if (d instanceof FaceAttachable) {
            out.add(new Prop("face", x -> name(((FaceAttachable) x).getAttachedFace()), x -> {
                FaceAttachable c = (FaceAttachable) x.clone();
                c.setAttachedFace(cycle(List.of(FaceAttachable.AttachedFace.values()), c.getAttachedFace()));
                return c;
            }));
        }
        if (d instanceof Hangable) {
            out.add(new Prop("hanging", x -> String.valueOf(((Hangable) x).isHanging()), x -> {
                Hangable c = (Hangable) x.clone();
                c.setHanging(!c.isHanging());
                return c;
            }));
        }
        if (d instanceof Lightable) {
            out.add(new Prop("lit", x -> String.valueOf(((Lightable) x).isLit()), x -> {
                Lightable c = (Lightable) x.clone();
                c.setLit(!c.isLit());
                return c;
            }));
        }
        if (d instanceof Snowable) {
            out.add(new Prop("snowy", x -> String.valueOf(((Snowable) x).isSnowy()), x -> {
                Snowable c = (Snowable) x.clone();
                c.setSnowy(!c.isSnowy());
                return c;
            }));
        }
        return out;
    }

    private static List<BlockFace> sortedFaces(Set<BlockFace> faces) {
        List<BlockFace> order = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
                BlockFace.UP, BlockFace.DOWN);
        return order.stream().filter(faces::contains).toList();
    }

    private static <T> T cycle(List<T> values, T current) {
        int i = values.indexOf(current);
        return values.get((i + 1) % values.size());
    }

    private static String name(Object o) {
        return o.toString().toLowerCase(Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTinker(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || !active(tinker, p)
                || !p.getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        List<Prop> props = props(block.getBlockData());
        if (props.isEmpty()) {
            return;
        }
        // the empty-hand click is ours, also the off-hand one that would follow it
        event.setUseInteractedBlock(PlayerInteractEvent.Result.DENY);
        event.setUseItemInHand(PlayerInteractEvent.Result.DENY);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String chosen = selected.get(p.getUniqueId());
        int index = 0;
        for (int i = 0; i < props.size(); i++) {
            if (props.get(i).name().equals(chosen)) {
                index = i;
                break;
            }
        }
        BlockData data = block.getBlockData();
        Prop prop;
        if (p.isSneaking()) {
            prop = props.get((index + (props.get(index).name().equals(chosen) ? 1 : 0)) % props.size());
            selected.put(p.getUniqueId(), prop.name());
        } else {
            prop = props.get(index);
            selected.put(p.getUniqueId(), prop.name());
            data = prop.next().apply(data);
            block.setBlockData(data, false);
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.UI_BUTTON_CLICK,
                    SoundCategory.BLOCKS, 0.4f, 1.6f);
        }
        plugin.messages().actionBar(p, "build-tinker-shown", "property", prop.name(), "value", prop.value().apply(data));
    }

    // ------------------------------------------------------------------ no updates + force place

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private void guard(Block b) {
        int now = Bukkit.getCurrentTick();
        guarded.values().removeIf(until -> until < now);
        // a few ticks, so a gravity block's first fall check (2 ticks later) finds it guarded too
        guarded.put(key(b), now + 5);
        updateGuardListener();
    }

    private boolean isGuarded(Block b) {
        Integer until = guarded.get(key(b));
        return until != null && until >= Bukkit.getCurrentTick();
    }

    /** The physics listener costs a little on every block update, so it's only there while someone uses the tools. */
    private void updateGuardListener() {
        int now = Bukkit.getCurrentTick();
        guarded.values().removeIf(until -> until < now);
        boolean needed = !noUpdates.isEmpty() || !forcePlace.isEmpty() || !guarded.isEmpty();
        if (needed && !guardRegistered) {
            Bukkit.getPluginManager().registerEvents(physicsGuard, plugin);
            guardRegistered = true;
        } else if (!needed && guardRegistered) {
            HandlerList.unregisterAll(physicsGuard);
            guardRegistered = false;
        }
    }

    final class PhysicsGuard implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onPhysics(BlockPhysicsEvent event) {
            if (!guarded.isEmpty() && isGuarded(event.getSourceBlock())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onFall(EntityChangeBlockEvent event) {
            if (!guarded.isEmpty() && event.getEntityType() == EntityType.FALLING_BLOCK && isGuarded(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        pending.remove(p.getUniqueId());
        if (active(noUpdates, p)) {
            guard(event.getBlockPlaced());
        }
    }

    /** Vanilla says "can't stay here" (flower on stone, carpet in the air, ...): allow it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCanBuild(BlockCanBuildEvent event) {
        Player p = event.getPlayer();
        if (event.isBuildable() || p == null || !active(forcePlace, p) || blockedByMob(event.getBlock())) {
            return;
        }
        event.setBuildable(true);
        guard(event.getBlock());
    }

    private static boolean blockedByMob(Block b) {
        return !b.getWorld().getNearbyEntities(BoundingBox.of(b), e -> e instanceof LivingEntity).isEmpty();
    }

    /** Blocks vanilla won't even try to place without support: torches, lanterns, buttons, levers, ladders. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onForceClick(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || clicked == null || event.getHand() != EquipmentSlot.HAND
                || event.useItemInHand() == PlayerInteractEvent.Result.DENY || !active(forcePlace, p)) {
            return;
        }
        Material type = p.getInventory().getItemInMainHand().getType();
        if (!needsHelp(type) || clicked.getType().isInteractable() && !p.isSneaking()) {
            return;
        }
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(event.getBlockFace());
        pending.put(p.getUniqueId(), new Pending(target, event.getBlockFace(), type, Bukkit.getCurrentTick()));
        // vanilla places it right after this event if it can (then onPlace clears this); otherwise we do
        Bukkit.getScheduler().runTask(plugin, () -> {
            Pending todo = pending.remove(p.getUniqueId());
            if (todo != null && p.isOnline()) {
                placeByHand(p, todo);
            }
        });
    }

    private static boolean needsHelp(Material type) {
        if (!type.isBlock()) {
            return false;
        }
        String n = type.name();
        return n.endsWith("TORCH") && !n.contains("WALL") || n.endsWith("_BUTTON") || type == Material.LEVER
                || type == Material.LADDER || n.endsWith("LANTERN") && type.createBlockData() instanceof Hangable;
    }

    private void placeByHand(Player p, Pending todo) {
        Block target = todo.target();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() != todo.type() || !target.isReplaceable() || blockedByMob(target)
                || p.getLocation().distanceSquared(target.getLocation().add(0.5, 0.5, 0.5)) > 64) {
            return;
        }
        BlockData data = dataFor(p, todo.type(), todo.face());
        if (data == null) {
            return;
        }
        target.setBlockData(data, false);
        guard(target);
        if (p.getGameMode() != GameMode.CREATIVE) {
            hand.subtract();
        }
        target.getWorld().playSound(target.getLocation().add(0.5, 0.5, 0.5), data.getSoundGroup().getPlaceSound(),
                SoundCategory.BLOCKS, 1f, 0.8f);
        p.swingMainHand();
    }

    private static BlockData dataFor(Player p, Material type, BlockFace face) {
        String n = type.name();
        boolean side = face == BlockFace.NORTH || face == BlockFace.EAST || face == BlockFace.SOUTH || face == BlockFace.WEST;
        if (n.endsWith("TORCH")) {
            if (side) {
                Material wall = Material.matchMaterial(n.substring(0, n.length() - "TORCH".length()) + "WALL_TORCH");
                if (wall != null && wall.createBlockData() instanceof Directional d) {
                    d.setFacing(face);
                    return d;
                }
            }
            return type.createBlockData();
        }
        BlockData data = type.createBlockData();
        if (data instanceof Hangable h) {
            h.setHanging(face == BlockFace.DOWN);
        }
        if (data instanceof FaceAttachable f && data instanceof Directional d) {
            if (side) {
                f.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                d.setFacing(face);
            } else {
                f.setAttachedFace(face == BlockFace.UP ? FaceAttachable.AttachedFace.FLOOR : FaceAttachable.AttachedFace.CEILING);
                d.setFacing(p.getFacing());
            }
        } else if (type == Material.LADDER) {
            if (!side || !(data instanceof Directional d)) {
                return null;
            }
            d.setFacing(face);
        }
        return data;
    }
}
