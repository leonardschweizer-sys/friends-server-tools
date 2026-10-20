package net.friendsserver.tools;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-of-a-kind items (the only-once advancement prizes). Their look comes from the server pack through the
 * item_model component, which anvils, crafting and commands without op can't set, so naming a normal item
 * the same never gives it the texture. Every item carries its id, so the plugin knows the real ones.
 */
final class CustomItems implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** One entry of custom-items in config.yml. */
    record Definition(String id, Material material, Key model, Key worn, boolean onHead, String rig, String ability,
                      boolean placedModel, Map<Key, Integer> enchantments, String name, List<String> lore,
                      String forAdvancement) {
    }

    private final FriendsTools plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey earnedByKey;
    private final NamespacedKey earnedAtKey;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();

    CustomItems(FriendsTools plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "custom_item");
        this.earnedByKey = new NamespacedKey(plugin, "earned_by");
        this.earnedAtKey = new NamespacedKey(plugin, "earned_at");
    }

    void load() {
        definitions.clear();
        ConfigurationSection all = plugin.getConfig().getConfigurationSection("custom-items");
        if (all == null) {
            return;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Material material = Material.matchMaterial(s.getString("material", ""));
            if (material == null || !material.isItem()) {
                plugin.getLogger().warning("custom-items." + id + ": unknown material " + s.getString("material"));
                continue;
            }
            Key model = key(s.getString("model"), id, "model");
            Key worn = key(s.getString("worn"), id, "worn");
            if (s.getString("model") != null && model == null || s.getString("worn") != null && worn == null) {
                continue;
            }
            if (worn != null && material.getEquipmentSlot() != EquipmentSlot.HEAD
                    && material.getEquipmentSlot() != EquipmentSlot.CHEST) {
                plugin.getLogger().warning("custom-items." + id + ": worn only works for helmets and elytras");
                worn = null;
            }
            Map<Key, Integer> enchants = new LinkedHashMap<>();
            ConfigurationSection ench = s.getConfigurationSection("enchantments");
            if (ench != null) {
                for (String e : ench.getKeys(false)) {
                    Key k = key(e.contains(":") ? e : "minecraft:" + e, id, "enchantments");
                    if (k != null) {
                        enchants.put(k, ench.getInt(e, 1));
                    }
                }
            }
            definitions.put(id.toLowerCase(Locale.ROOT), new Definition(id.toLowerCase(Locale.ROOT), material, model,
                    worn, s.getBoolean("on-head", false), s.getString("golem"), s.getString("ability"),
                    s.getBoolean("placed-model", false), enchants, s.getString("name", id), s.getStringList("lore"),
                    s.getString("for", "")));
        }
    }

    private Key key(String text, String id, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Key.key(text.trim());
        } catch (Exception e) {
            plugin.getLogger().warning("custom-items." + id + "." + field + ": not a valid id: " + text);
            return null;
        }
    }

    Definition definition(String id) {
        return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT));
    }

    /** The custom item id of a stack, or null for normal items. */
    String idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    /** Who earned a custom item and when, as stored in it ("" when unknown). */
    String[] earnedOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return new String[]{"", ""};
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return new String[]{pdc.getOrDefault(earnedByKey, PersistentDataType.STRING, ""),
                pdc.getOrDefault(earnedAtKey, PersistentDataType.STRING, "")};
    }

    /** Builds the item. earnedBy/earnedAt may be empty. */
    ItemStack create(Definition def, String earnedBy, String earnedAt) {
        ItemStack item = ItemStack.of(def.material());
        item.setData(DataComponentTypes.ITEM_NAME, MM.deserialize(def.name()));
        item.setData(DataComponentTypes.RARITY, ItemRarity.EPIC);
        if (def.model() != null) {
            item.setData(DataComponentTypes.ITEM_MODEL, def.model());
        }
        boolean elytra = def.material() == Material.ELYTRA;
        if (def.worn() != null) {
            // Same as the normal item, but with its own look when worn (elytra wings or a helmet texture)
            EquipmentSlot slot = elytra ? EquipmentSlot.CHEST : EquipmentSlot.HEAD;
            item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(slot)
                    .equipSound(Key.key("minecraft", elytra ? "item.armor.equip_elytra" : "item.armor.equip_netherite"))
                    .assetId(def.worn())
                    .damageOnHurt(!elytra)
                    .build());
        } else if (def.onHead()) {
            // Worn on the head and shown as its own 3D model there (hats, halo)
            item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD)
                    .equipSound(Key.key("minecraft", "item.armor.equip_netherite"))
                    .build());
        }
        if ("reusable_totem".equals(def.ability())) {
            // own cooldown group, so the recharge overlay only shows on this item
            item.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(1f)
                    .cooldownGroup(Key.key("friendstools", def.id())).build());
        }
        for (Map.Entry<Key, Integer> e : def.enchantments().entrySet()) {
            Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                    .get(new NamespacedKey(e.getKey().namespace(), e.getKey().value()));
            if (enchantment == null) {
                plugin.getLogger().warning("custom-items." + def.id() + ": unknown enchantment " + e.getKey().asString());
            } else {
                item.addUnsafeEnchantment(enchantment, e.getValue());
            }
        }
        if (def.rig() != null) {
            item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        List<Component> lore = new ArrayList<>();
        for (String line : def.lore()) {
            lore.add(plain(MM.deserialize(line)));
        }
        if (!def.forAdvancement().isBlank()) {
            lore.add(plain(MM.deserialize("<dark_gray>Only-once prize: <for>",
                    Placeholder.unparsed("for", def.forAdvancement()))));
        }
        if (!earnedBy.isBlank()) {
            lore.add(plain(MM.deserialize("<dark_gray>Earned by <who><when>", Placeholder.unparsed("who", earnedBy),
                    Placeholder.unparsed("when", earnedAt.isBlank() ? "" : ", " + earnedAt))));
        }
        if (!lore.isEmpty()) {
            item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        }
        item.editPersistentDataContainer(pdc -> {
            pdc.set(idKey, PersistentDataType.STRING, def.id());
            if (!earnedBy.isBlank()) {
                pdc.set(earnedByKey, PersistentDataType.STRING, earnedBy);
                pdc.set(earnedAtKey, PersistentDataType.STRING, earnedAt);
            }
        });
        return item;
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static String today() {
        return LocalDate.now().format(DATE);
    }

    // ---- /customitem give <player> <id> | list

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            if (definitions.isEmpty()) {
                msg.send(sender, "customitem-none");
            } else {
                msg.send(sender, "customitem-list", "items", String.join(", ", definitions.keySet()));
            }
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg.send(sender, "customitem-no-player", "player", args[1]);
                return true;
            }
            Definition def = definition(args[2]);
            if (def == null) {
                msg.send(sender, "customitem-unknown", "item", args[2]);
                return true;
            }
            ItemStack item = create(def, target.getName(), today());
            target.getInventory().addItem(item).values()
                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
            msg.send(sender, "customitem-given", "item", def.id(), "player", target.getName());
            plugin.getLogger().info(sender.getName() + " gave custom item " + def.id() + " to " + target.getName());
            return true;
        }
        msg.send(sender, "customitem-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("give", "list"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(new ArrayList<>(definitions.keySet()), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
