package party.morino.fukurou.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

/**
 * サーバーのオブジェクトをスナップショットの JSON にする（v3 設計 §1.3）。メインスレッドで呼ぶ。
 *
 * <p>Paper 1.20.4 から 26.x まで同じバイトコードで動かすため、版によってクラスとインターフェースが入れ替わった型
 * （InventoryView、Attribute など）にはリフレクションで触り、キーは enum の name() ではなく {@link Keyed#getKey()} で取る。
 * 型の名前が変わっても invokeinterface の相手が変わらないよう、キーを取るときはいったん Object にしてから Keyed にキャストする。
 */
final class Snapshots {
    private Snapshots() {}

    /** Keyed のキーの文字列（minecraft:diamond）。キーを持たなければ null。 */
    static String key(Object keyed) {
        if (!(keyed instanceof Keyed)) return null;
        try {
            return ((Keyed) keyed).getKey().toString();
        } catch (RuntimeException | LinkageError e) {
            // EntityType.UNKNOWN などはキーを持たず投げる
            return null;
        }
    }

    /** ワールドのキーの文字列。Keyed でなければ minecraft:<名前>。 */
    static String worldKey(World world) {
        if (world == null) return null;
        String key = key(world);
        return key != null ? key : "minecraft:" + world.getName().toLowerCase(Locale.ROOT);
    }

    /** Component の JSON。null は JSON の null。 */
    static JsonElement component(Component component) {
        if (component == null) return JsonNull.INSTANCE;
        return GsonComponentSerializer.gson().serializeToTree(component);
    }

    /** Location。 */
    static JsonObject location(Location location) {
        JsonObject json = new JsonObject();
        World world = null;
        try {
            world = location.getWorld();
        } catch (IllegalArgumentException e) {
            // ワールドがアンロード済み
        }
        json.addProperty("world", worldKey(world));
        json.addProperty("x", location.getX());
        json.addProperty("y", location.getY());
        json.addProperty("z", location.getZ());
        json.addProperty("yaw", location.getYaw());
        json.addProperty("pitch", location.getPitch());
        return json;
    }

    /** Item。空のスロット（null・空気・0 個）は JSON の null。 */
    static JsonElement item(ItemStack item) {
        if (item == null) return JsonNull.INSTANCE;
        Material type = item.getType();
        if (type == null || type.isAir() || item.getAmount() <= 0) return JsonNull.INSTANCE;
        JsonObject json = new JsonObject();
        json.addProperty("type", key((Object) type));
        json.addProperty("amount", item.getAmount());
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        JsonElement name = JsonNull.INSTANCE;
        JsonArray lore = new JsonArray();
        Integer customModelData = null;
        JsonObject enchantments = new JsonObject();
        Integer damage = null;
        boolean unbreakable = false;
        if (meta != null) {
            try {
                if (meta.hasDisplayName()) name = component(meta.displayName());
            } catch (RuntimeException | LinkageError e) {
                // 名前を読めない版
            }
            try {
                List<Component> lines = meta.lore();
                if (lines != null) for (Component line : lines) lore.add(component(line));
            } catch (RuntimeException | LinkageError e) {
                // 説明文を読めない版
            }
            try {
                // 1.21.4 以降は非推奨。新しい形式だけのデータでは投げることがある
                if (meta.hasCustomModelData()) customModelData = meta.getCustomModelData();
            } catch (RuntimeException | LinkageError e) {
                customModelData = null;
            }
            try {
                for (Map.Entry<?, Integer> entry : meta.getEnchants().entrySet()) {
                    String enchantment = key(entry.getKey());
                    if (enchantment != null) enchantments.addProperty(enchantment, entry.getValue());
                }
            } catch (RuntimeException | LinkageError e) {
                // エンチャントを読めない版
            }
            try {
                if (meta instanceof Damageable && type.getMaxDurability() > 0) damage = ((Damageable) meta).getDamage();
            } catch (RuntimeException | LinkageError e) {
                damage = null;
            }
            try {
                unbreakable = meta.isUnbreakable();
            } catch (RuntimeException | LinkageError e) {
                unbreakable = false;
            }
        }
        json.add("name", name);
        json.add("lore", lore);
        json.addProperty("customModelData", customModelData);
        json.add("enchantments", enchantments);
        json.addProperty("damage", damage);
        json.addProperty("unbreakable", unbreakable);
        return json;
    }

    /** アイテムの配列。 */
    static JsonArray items(ItemStack[] items) {
        JsonArray json = new JsonArray();
        if (items != null) for (ItemStack item : items) json.add(item(item));
        return json;
    }

    /** PlayerSnapshot。 */
    static JsonObject player(Player player) {
        JsonObject json = new JsonObject();
        json.addProperty("name", player.getName());
        json.addProperty("uuid", player.getUniqueId().toString());
        json.add("location", location(player.getLocation()));
        json.addProperty("gameMode", player.getGameMode().name().toLowerCase(Locale.ROOT));
        json.addProperty("health", player.getHealth());
        json.addProperty("maxHealth", maxHealth(player));
        json.addProperty("food", player.getFoodLevel());
        json.addProperty("saturation", player.getSaturation());
        json.addProperty("level", player.getLevel());
        json.addProperty("exp", player.getExp());
        json.addProperty("flying", player.isFlying());
        json.addProperty("sneaking", player.isSneaking());
        json.addProperty("sprinting", player.isSprinting());
        json.addProperty("op", player.isOp());
        json.addProperty("selectedSlot", player.getInventory().getHeldItemSlot());
        // クライアントの言語（ja_jp など）。Bukkit の getLocale は 1.12 からある
        json.addProperty("locale", player.getLocale());
        json.add("inventory", items(player.getInventory().getContents()));
        json.add("openInventory", openInventory(player));
        JsonArray effects = new JsonArray();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", key((Object) effect.getType()));
            entry.addProperty("amplifier", effect.getAmplifier());
            entry.addProperty("duration", effect.getDuration());
            effects.add(entry);
        }
        json.add("effects", effects);
        json.add("tags", tags(player));
        return json;
    }

    /** BlockSnapshot。 */
    static JsonObject block(Block block) {
        JsonObject json = new JsonObject();
        json.addProperty("world", worldKey(block.getWorld()));
        json.addProperty("x", block.getX());
        json.addProperty("y", block.getY());
        json.addProperty("z", block.getZ());
        json.addProperty("type", key((Object) block.getType()));
        json.addProperty("data", block.getBlockData().getAsString());
        return json;
    }

    /** EntitySnapshot。 */
    static JsonObject entity(Entity entity) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", entity.getUniqueId().toString());
        String type = key((Object) entity.getType());
        json.addProperty("type", type != null ? type : "minecraft:unknown");
        json.add("location", location(entity.getLocation()));
        JsonElement name = JsonNull.INSTANCE;
        try {
            name = component(entity.name());
        } catch (RuntimeException | LinkageError e) {
            // 名前を読めない版
        }
        json.add("name", name);
        JsonElement customName = JsonNull.INSTANCE;
        try {
            customName = component(entity.customName());
        } catch (RuntimeException | LinkageError e) {
            // 名前を読めない版
        }
        json.add("customName", customName);
        json.add("tags", tags(entity));
        json.addProperty("health", entity instanceof LivingEntity ? ((LivingEntity) entity).getHealth() : null);
        json.addProperty("dead", entity.isDead());
        return json;
    }

    /** WorldSnapshot。 */
    static JsonObject world(World world) {
        JsonObject json = new JsonObject();
        json.addProperty("key", worldKey(world));
        json.addProperty("name", world.getName());
        json.addProperty("time", world.getTime());
        json.addProperty("fullTime", world.getFullTime());
        json.addProperty("storm", world.hasStorm());
        json.addProperty("thundering", world.isThundering());
        json.addProperty("difficulty", world.getDifficulty().name().toLowerCase(Locale.ROOT));
        JsonArray players = new JsonArray();
        for (Player player : world.getPlayers()) players.add(player.getName());
        json.add("players", players);
        return json;
    }

    /** スコアボードのタグ（名前の順）。 */
    private static JsonArray tags(Entity entity) {
        JsonArray json = new JsonArray();
        for (String tag : new TreeSet<>(entity.getScoreboardTags())) json.add(tag);
        return json;
    }

    // ---- 版によって形の変わる API ----

    /** 最大体力の属性（1.21.3 で GENERIC_MAX_HEALTH → MAX_HEALTH、enum → インターフェース）。見つからなければ null。 */
    private static final Object MAX_HEALTH_ATTRIBUTE = findMaxHealthAttribute();

    private static Object findMaxHealthAttribute() {
        for (String name : new String[] {"MAX_HEALTH", "GENERIC_MAX_HEALTH"}) {
            try {
                Field field = Attribute.class.getField(name);
                Object value = field.get(null);
                if (value != null) return value;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                // 次の名前を試す
            }
        }
        return null;
    }

    /** 最大体力。属性が引けなければ非推奨の getMaxHealth、それも無ければ今の体力。 */
    static double maxHealth(LivingEntity entity) {
        if (MAX_HEALTH_ATTRIBUTE != null) {
            try {
                AttributeInstance instance = entity.getAttribute((Attribute) MAX_HEALTH_ATTRIBUTE);
                if (instance != null) return instance.getValue();
            } catch (RuntimeException | LinkageError e) {
                // 下の方法を試す
            }
        }
        try {
            Method method = org.bukkit.entity.Damageable.class.getMethod("getMaxHealth");
            return ((Number) method.invoke(entity)).doubleValue();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return entity.getHealth();
        }
    }

    /** InventoryView のメソッド（1.21 で抽象クラス → インターフェースなので、直接は呼ばない）。 */
    private static final Method VIEW_TYPE = viewMethod("getType");
    private static final Method VIEW_TOP = viewMethod("getTopInventory");
    private static final Method VIEW_TITLE = viewMethod("title");

    private static Method viewMethod(String name) {
        try {
            return InventoryView.class.getMethod(name);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** 開いているコンテナの画面。自分のインベントリ（CRAFTING / CREATIVE）なら null。 */
    static JsonElement openInventory(Player player) {
        if (VIEW_TYPE == null || VIEW_TOP == null) return JsonNull.INSTANCE;
        try {
            Object view = player.getOpenInventory();
            if (view == null) return JsonNull.INSTANCE;
            Object type = VIEW_TYPE.invoke(view);
            String typeName = type instanceof Enum<?> ? ((Enum<?>) type).name() : String.valueOf(type);
            if (typeName.equals("CRAFTING") || typeName.equals("CREATIVE")) return JsonNull.INSTANCE;
            Inventory top = (Inventory) VIEW_TOP.invoke(view);
            JsonObject json = new JsonObject();
            json.addProperty("type", typeName);
            Object title = VIEW_TITLE == null ? null : VIEW_TITLE.invoke(view);
            json.add("title", title instanceof Component ? component((Component) title) : component(Component.empty()));
            json.addProperty("size", top.getSize());
            json.add("contents", items(top.getContents()));
            return json;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return JsonNull.INSTANCE;
        }
    }
}
