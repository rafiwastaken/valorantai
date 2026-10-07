package io.prisonmines.plugin

import io.prisonmines.core.ChunkKey
import io.prisonmines.core.MineService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin

/**
 * Keeps the core's idea of "which world is this client in" current, and self-heals: after anything
 * that can make us miss a chunk packet, we ask the server which chunks the client really has
 * (Paper's `isChunkSent`) and adopt the ones we haven't seen.
 */
class PlayerListener(private val plugin: Plugin, private val service: MineService) : Listener {

    private fun reconcileLater(player: Player, delayTicks: Long) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { reconcile(player) }, delayTicks)
    }

    fun reconcile(player: Player) {
        if (!player.isOnline) return
        service.reconcile(player.uniqueId) { cx, cz -> player.isChunkSent(ChunkKey.of(cx, cz)) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) {
        service.onPlayerWorld(e.player.uniqueId, e.player.world.name)
        reconcileLater(e.player, 5)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(e: PlayerQuitEvent) { service.removePlayer(e.player.uniqueId) }

    // Fires before the destination chunks are sent, so chunk packets already see the right world.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(e: PlayerTeleportEvent) {
        val to = e.to.world ?: return
        if (e.from.world != to) {
            service.onPlayerWorld(e.player.uniqueId, to.name)
            reconcileLater(e.player, 20)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(e: PlayerChangedWorldEvent) {
        service.onPlayerWorld(e.player.uniqueId, e.player.world.name)
        reconcileLater(e.player, 10)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(e: PlayerRespawnEvent) {
        service.onPlayerWorld(e.player.uniqueId, e.respawnLocation.world.name)
        reconcileLater(e.player, 10)
    }
}
