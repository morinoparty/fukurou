package party.morino.fukurou.event

/** よく使うイベントのクラス名。ServerEvents には文字列をそのまま渡してもよい。 */
public object BukkitEvents {
    public const val PLAYER_JOIN: String = "org.bukkit.event.player.PlayerJoinEvent"
    public const val PLAYER_QUIT: String = "org.bukkit.event.player.PlayerQuitEvent"
    public const val PLAYER_INTERACT: String = "org.bukkit.event.player.PlayerInteractEvent"
    public const val PLAYER_INTERACT_ENTITY: String = "org.bukkit.event.player.PlayerInteractEntityEvent"
    public const val PLAYER_COMMAND_PREPROCESS: String = "org.bukkit.event.player.PlayerCommandPreprocessEvent"
    public const val PLAYER_MOVE: String = "org.bukkit.event.player.PlayerMoveEvent"
    public const val PLAYER_ITEM_HELD: String = "org.bukkit.event.player.PlayerItemHeldEvent"
    public const val PLAYER_DROP_ITEM: String = "org.bukkit.event.player.PlayerDropItemEvent"
    public const val PLAYER_TOGGLE_SNEAK: String = "org.bukkit.event.player.PlayerToggleSneakEvent"
    public const val ASYNC_CHAT: String = "io.papermc.paper.event.player.AsyncChatEvent"
    public const val BLOCK_BREAK: String = "org.bukkit.event.block.BlockBreakEvent"
    public const val BLOCK_PLACE: String = "org.bukkit.event.block.BlockPlaceEvent"
    public const val INVENTORY_OPEN: String = "org.bukkit.event.inventory.InventoryOpenEvent"
    public const val INVENTORY_CLICK: String = "org.bukkit.event.inventory.InventoryClickEvent"
    public const val INVENTORY_CLOSE: String = "org.bukkit.event.inventory.InventoryCloseEvent"
    public const val ENTITY_DAMAGE: String = "org.bukkit.event.entity.EntityDamageEvent"
    public const val ENTITY_DAMAGE_BY_ENTITY: String = "org.bukkit.event.entity.EntityDamageByEntityEvent"
    public const val ENTITY_DEATH: String = "org.bukkit.event.entity.EntityDeathEvent"
    public const val PLAYER_DEATH: String = "org.bukkit.event.entity.PlayerDeathEvent"
    public const val SERVER_COMMAND: String = "org.bukkit.event.server.ServerCommandEvent"
}
