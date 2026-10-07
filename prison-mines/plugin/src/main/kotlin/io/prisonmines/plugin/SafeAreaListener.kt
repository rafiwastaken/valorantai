package io.prisonmines.plugin

import io.prisonmines.core.MineIndex
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.GameMode

/**
 * Protects the REAL world in and around the mine. The mine cuboid itself is air on the server and
 * virtual per player; this guards everything physical: the walls/floor/walkways in the safe area.
 * Virtual breaks (including enchants) never reach any of these paths, they can't touch real blocks.
 */
class SafeAreaListener(
    private val index: () -> MineIndex,
    private val settings: () -> Settings,
) : Listener {

    private fun Block.inSafe(): Boolean = index().safeAt(world.name, x, y, z) != null
    private fun Player.bypass(): Boolean = hasPermission("prisonmines.bypass")

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) { if (e.block.inSafe() && !e.player.bypass()) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) { if (e.block.inSafe() && !e.player.bypass()) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketEmpty(e: PlayerBucketEmptyEvent) { if (e.block.inSafe() && !e.player.bypass()) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketFill(e: PlayerBucketFillEvent) { if (e.block.inSafe() && !e.player.bypass()) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(e: EntityExplodeEvent) { e.blockList().removeIf { it.inSafe() } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(e: BlockExplodeEvent) { e.blockList().removeIf { it.inSafe() } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFluid(e: BlockFromToEvent) { if (e.toBlock.inSafe()) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(e: BlockPistonExtendEvent) {
        if (e.blocks.any { it.inSafe() || it.getRelative(e.direction).inSafe() }) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(e: BlockPistonRetractEvent) {
        if (e.blocks.any { it.inSafe() || it.getRelative(e.direction).inSafe() }) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onIgnite(e: BlockIgniteEvent) { if (e.block.inSafe()) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBurn(e: BlockBurnEvent) { if (e.block.inSafe()) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpread(e: BlockSpreadEvent) { if (e.block.inSafe()) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpawn(e: CreatureSpawnEvent) {
        if (!settings().blockMobSpawns || e.spawnReason == CreatureSpawnEvent.SpawnReason.CUSTOM) return
        val l = e.location
        if (index().safeAt(l.world.name, l.blockX, l.blockY, l.blockZ) != null) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPvp(e: EntityDamageByEntityEvent) {
        if (settings().pvpInSafeArea || e.entity !is Player || e.damager !is Player) return
        val l = e.entity.location
        if (index().safeAt(l.world.name, l.blockX, l.blockY, l.blockZ) != null) e.isCancelled = true
    }

    /**
     * The server's world inside the mine is air, so vanilla's anti-fly check would kick anyone
     * standing on a virtual block. Operators enable `allow-flight`; this stops that from actually
     * granting flight to survival players inside a safe area.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onToggleFlight(e: PlayerToggleFlightEvent) {
        if (!settings().flightGuard || !e.isFlying) return
        val p = e.player
        if (p.gameMode == GameMode.CREATIVE || p.gameMode == GameMode.SPECTATOR || p.bypass()) return
        val l = p.location
        if (index().safeAt(l.world.name, l.blockX, l.blockY, l.blockZ) != null) {
            e.isCancelled = true
            p.isFlying = false
        }
    }
}
