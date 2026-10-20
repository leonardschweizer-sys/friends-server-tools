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
 * Prize items for advancements (everyone who gets the advancement can get one). Their look comes from the
 * server pack through the
 * item_model component, which anvils, crafting and commands without op can't set, so naming a normal item
 * the same never gives it the texture. Every item carries its id, so the plugin knows the real ones.
 */
final class CustomItems implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** One entry of custom-items in config.yml. */
    record Definition(String id, int number, Material material, Key model, Key worn, boolean onHead, String rig,
                      String ability, boolean placedModel, Map<Key, Integer> enchantments, String name,
                      List<String> lore, String forAdvancement, String advancement) {
    }

    private final FriendsTools plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey earnedByKey;
    private final NamespacedKey earnedAtKey;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<Integer, Definition> byNumber = new java.util.TreeMap<>();

    CustomItems(FriendsTools plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "custom_item");
        this.earnedByKey = new NamespacedKey(plugin, "earned_by");
        this.earnedAtKey = new NamespacedKey(plugin, "earned_at");
    }

    void load() {
        definitions.clear();
        byNumber.clear();
        // An older config.yml without custom-items uses the list from the plugin (Bukkit would hand out an
        // empty section here instead of the defaults)
        var cfg = plugin.getConfig();
        ConfigurationSection all = cfg.contains("custom-items", true) ? cfg.getConfigurationSection("custom-items")
                : cfg.getDefaults() == null ? null : cfg.getDefaults().getConfigurationSection("custom-items");
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
            // short number for /customitem; a missing or taken one gets the next free number
            int number = s.getInt("number", 0);
            if (number <= 0 || byNumber.containsKey(number)) {
                number = 1;
                while (byNumber.containsKey(number)) {
                    number++;
                }
            }
            Definition def = new Definition(id.toLowerCase(Locale.ROOT), number, material, model, worn,
                    s.getBoolean("on-head", false), s.getString("golem"), s.getString("ability"),
                    s.getBoolean("placed-model", false), enchants, s.getString("name", id), s.getStringList("lore"),
                    s.getString("for", ""), s.getString("advancement", "").trim().toLowerCase(Locale.ROOT));
            definitions.put(def.id(), def);
            byNumber.put(number, def);
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

    /** Prize items given for completing this advancement (e.g. "blazeandcave:mining/bulldozer"). */
    List<Definition> forAdvancement(String key) {
        List<Definition> out = new ArrayList<>();
        for (Definition def : byNumber.values()) {
            if (def.advancement().equals(key)) {
                out.add(def);
            }
        }
        return out;
    }

    /** By id ("katana") or by number ("21"). */
    Definition definition(String id) {
        if (id == null) {
            return null;
        }
        if (id.matches("\\d{1,6}")) {
            return byNumber.get(Integer.parseInt(id));
        }
        return definitions.get(id.toLowerCase(Locale.ROOT));
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
            lore.add(plain(MM.deserialize("<dark_gray>Reward for: <for>",
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

    // ---- /customitem [give <player>] <number|name|all>, /customitem list

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            if (definitions.isEmpty()) {
                msg.send(sender, "customitem-none");
                return true;
            }
            msg.send(sender, "customitem-list");
            String line = plugin.getConfig().getString("messages.customitem-list-line",
                    "<gray><number></gray>  <name> <dark_gray>(<id>)");
            for (Definition def : byNumber.values()) {
                sender.sendMessage(MM.deserialize(line, Placeholder.unparsed("number", String.valueOf(def.number())),
                        Placeholder.component("name", MM.deserialize(def.name())), Placeholder.unparsed("id", def.id())));
            }
            return true;
        }
        Player target;
        String what;
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            target = Bukkit.getPlayerExact(args[1]);
            what = args[2];
            if (target == null) {
                msg.send(sender, "customitem-no-player", "player", args[1]);
                return true;
            }
        } else if (args.length == 1 && sender instanceof Player self) {
            target = self;
            what = args[0];
        } else {
            msg.send(sender, "customitem-usage");
            return true;
        }
        List<Definition> give = new ArrayList<>();
        if (what.equalsIgnoreCase("all")) {
            give.addAll(byNumber.values());
        } else {
            Definition def = definition(what);
            if (def == null) {
                msg.send(sender, "customitem-unknown", "item", what);
                return true;
            }
            give.add(def);
        }
        for (Definition def : give) {
            ItemStack item = create(def, target.getName(), today());
            target.getInventory().addItem(item).values()
                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        }
        String name = give.size() == 1 ? give.getFirst().id() : give.size() + " items";
        msg.send(sender, "customitem-given", "item", name, "player", target.getName());
        plugin.getLogger().info(sender.getName() + " gave " + name + " to " + target.getName());
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        List<String> items = new ArrayList<>();
        items.add("all");
        byNumber.forEach((n, def) -> {
            items.add(String.valueOf(n));
            items.add(def.id());
        });
        if (args.length == 1) {
            List<String> first = new ArrayList<>(List.of("give", "list"));
            first.addAll(items);
            return filter(first, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(items, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
