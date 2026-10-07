package io.prisonmines.plugin

import io.prisonmines.core.MineService
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class MineCommand(
    private val plugin: PrisonMinesPlugin,
    private val service: MineService,
    private val enchants: EnchantRegistry,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reset" -> {
                val p = sender as? Player ?: return msg(sender, "Players only.")
                if (!p.hasPermission("prisonmines.reset")) return msg(sender, "No permission.")
                val l = p.location
                val mine = args.getOrNull(1)?.let { service.index.get(it) }
                    ?: service.index.safeAt(p.world.name, l.blockX, l.blockY, l.blockZ)
                    ?: return msg(sender, "Stand near a mine or name one: /pmine reset <mine>")
                service.reset(service.session(p.uniqueId, mine))
            }
            "info" -> {
                val p = sender as? Player ?: return msg(sender, "Players only.")
                val sessions = service.playerIfPresent(p.uniqueId)?.sessions().orEmpty()
                if (sessions.isEmpty()) return msg(sender, "No active mine instances.")
                for (s in sessions) msg(sender, "${s.mine.id}: ${"%.1f".format(s.minedFraction * 100)}% mined, ${s.resetCount} resets")
            }
            "reload" -> {
                if (!sender.hasPermission("prisonmines.admin")) return msg(sender, "No permission.")
                msg(sender, if (plugin.reloadMines()) "Reloaded ${service.index.all.size} mine(s)." else "Reload failed, see console. Old config kept.")
            }
            "enchant" -> {
                if (!sender.hasPermission("prisonmines.admin")) return msg(sender, "No permission.")
                val p = sender as? Player ?: return msg(sender, "Players only.")
                val id = args.getOrNull(1); val level = args.getOrNull(2)?.toIntOrNull()
                if (id == null || level == null) return msg(sender, "/pmine enchant <${enchants.ids().joinToString("|")}> <level>")
                val ok = enchants.setLevel(p.inventory.itemInMainHand, id, level)
                msg(sender, if (ok) "Set $id=$level on your held item." else "Unknown enchant or no item in hand.")
            }
            else -> msg(sender, "/pmine <reset [mine]|info|reload|enchant <id> <level>>")
        }
        return true
    }

    private fun msg(to: CommandSender, text: String): Boolean { to.sendMessage(text); return true }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> = when (args.size) {
        1 -> listOf("reset", "info", "reload", "enchant").filter { it.startsWith(args[0], true) }
        2 -> when (args[0].lowercase()) {
            "reset" -> service.index.all.map { it.id }
            "enchant" -> enchants.ids().toList()
            else -> emptyList()
        }.filter { it.startsWith(args[1], true) }
        else -> emptyList()
    }
}
