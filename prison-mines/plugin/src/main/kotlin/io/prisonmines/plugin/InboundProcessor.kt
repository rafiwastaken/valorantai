package io.prisonmines.plugin

import io.prisonmines.core.DigAction
import io.prisonmines.core.DigRequest
import io.prisonmines.core.MineService
import org.bukkit.Bukkit
import org.bukkit.GameMode
import java.util.concurrent.ConcurrentLinkedQueue

/** Main-thread half of the interception layer: same timing as vanilla packet handling. */
class InboundProcessor(
    private val service: MineService,
    private val enchants: EnchantRegistry,
    private val settings: () -> Settings,
    val queue: ConcurrentLinkedQueue<Inbound>,
) {
    fun drain() {
        while (true) {
            val a = queue.poll() ?: return
            val player = Bukkit.getPlayer(a.player) ?: continue
            if (player.world.name != a.mine.world) continue // changed world while the packet was queued
            when (a) {
                is Inbound.Dig -> dig(player, a)
                is Inbound.Place -> place(player, a)
            }
        }
    }

    private fun dig(player: org.bukkit.entity.Player, a: Inbound.Dig) {
        val mode = player.gameMode
        val canMine = mode == GameMode.SURVIVAL || mode == GameMode.CREATIVE
        val eye = player.eyeLocation
        val dx = a.x + 0.5 - eye.x; val dy = a.y + 0.5 - eye.y; val dz = a.z + 0.5 - eye.z
        val reach = settings().reach
        val inReach = canMine && dx * dx + dy * dy + dz * dz <= reach * reach

        // Enchants only matter when a break can actually happen; skip the PDC reads otherwise.
        val breaks = inReach && (a.action == DigAction.FINISH || (a.action == DigAction.START && mode == GameMode.CREATIVE))
        service.dig(
            a.player, a.mine,
            DigRequest(a.x, a.y, a.z, a.action, a.sequence, mode == GameMode.CREATIVE, inReach,
                if (breaks) enchants.enchantsFor(player) else emptyList()),
        )
    }

    /** Cancelled placement: restore what the client predicted away, then confirm. */
    private fun place(player: org.bukkit.entity.Player, a: Inbound.Place) {
        val session = service.session(a.player, a.mine)
        session.invalidate(a.x, a.y, a.z)
        session.invalidate(a.tx, a.ty, a.tz)
        service.ack(a.player, a.sequence)
        player.updateInventory() // client already decremented the stack it tried to place
    }
}
