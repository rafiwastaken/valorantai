package io.prisonmines.plugin

import io.prisonmines.core.BreakPlan
import io.prisonmines.core.MineHooks
import io.prisonmines.core.MineSession
import io.prisonmines.core.enchant.BreakOutcome
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ThreadLocalRandom

/** Bridges core callbacks to Bukkit: events, rewards, break feedback, reset handling. */
class BukkitHooks(
    private val sink: PacketSink,
    private val settings: () -> Settings,
) : MineHooks {

    override fun beforeBreak(plan: BreakPlan): Boolean {
        val player = Bukkit.getPlayer(plan.session.owner) ?: return false
        val event = MineBlockBreakEvent(player, plan.session.mine, plan.size)
        Bukkit.getPluginManager().callEvent(event)
        return !event.isCancelled
    }

    override fun afterBreak(outcome: BreakOutcome) {
        val player = Bukkit.getPlayer(outcome.session.owner) ?: return
        val mine = outcome.session.mine

        val drops = ArrayList<ItemStack>()
        val rnd = ThreadLocalRandom.current()
        for ((k, kind) in mine.kinds.withIndex()) {
            val n = outcome.tallies[k]
            if (n == 0) continue
            val material = Material.matchMaterial(kind.drop) ?: continue
            val exact = n * kind.dropAmount * outcome.rewardMultiplier
            var amount = exact.toInt() + if (rnd.nextDouble() < exact - exact.toInt()) 1 else 0 // unbiased rounding
            while (amount > 0) {
                val stack = minOf(amount, material.maxStackSize)
                drops.add(ItemStack(material, stack))
                amount -= stack
            }
        }

        val event = MineBlocksBrokenEvent(player, mine, outcome, drops)
        Bukkit.getPluginManager().callEvent(event)
        if (!event.dropsHandled) give(player, event.drops)

        sink.sendBreakEffects(player.uniqueId, outcome, settings().breakEffectCap)
    }

    private fun give(player: Player, items: List<ItemStack>) {
        if (items.isEmpty()) return
        val leftover = player.inventory.addItem(*items.toTypedArray())
        for (stack in leftover.values) player.world.dropItem(player.location, stack)
    }

    override fun onReset(session: MineSession) {
        val s = settings()
        val player = Bukkit.getPlayer(session.owner) ?: return
        val mine = session.mine
        if (player.world.name != mine.world) return

        if (s.teleportOnReset) {
            val l = player.location
            // Feet or head inside the cuboid would end up inside fresh blocks.
            if (mine.mine.contains(l.blockX, l.blockY, l.blockZ) || mine.mine.contains(l.blockX, l.blockY + 1, l.blockZ)) {
                val sp = mine.spawn
                player.teleport(Location(player.world, sp.x, sp.y, sp.z, sp.yaw, sp.pitch))
            }
        }
        if (s.resetMessage.isNotEmpty()) player.sendMessage(ChatColor.translateAlternateColorCodes('&', s.resetMessage))
    }
}
