package io.prisonmines.plugin

import io.prisonmines.core.MineDefinition
import io.prisonmines.core.enchant.BreakOutcome
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack

/**
 * Fired (main thread) before a virtual break is applied. Because the real world never sees these
 * breaks, Bukkit's BlockBreakEvent is NOT fired for them; plugins that care hook these instead.
 */
class MineBlockBreakEvent(
    val player: Player,
    val mine: MineDefinition,
    /** origin + enchant-expanded blocks */
    val blockCount: Int,
) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/**
 * Fired after blocks were removed from the player's instance. [drops] is mutable: auto-sell / token
 * plugins can consume or replace it; clear it (or set [dropsHandled]) to suppress default delivery.
 */
class MineBlocksBrokenEvent(
    val player: Player,
    val mine: MineDefinition,
    val outcome: BreakOutcome,
    val drops: MutableList<ItemStack>,
) : Event() {
    var dropsHandled = false
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}
