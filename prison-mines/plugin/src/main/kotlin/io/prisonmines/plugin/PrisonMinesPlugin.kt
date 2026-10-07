package io.prisonmines.plugin

import com.github.retrooper.packetevents.PacketEvents
import io.prisonmines.core.MineIndex
import io.prisonmines.core.MineService
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level

/**
 * Wiring only. Architecture in one picture:
 *
 *   netty thread                      main thread (1x per tick)               netty thread
 *   ------------                      -------------------------               ------------
 *   MinePacketListener  --Inbound-->  InboundProcessor -> MineService.dig     PacketSink
 *    (classify, cancel)                 (validate, enchants, clip, apply)      (1 packet / touched
 *   chunk packet -> snapshot -------------------------------------------->      section / player)
 *                                       MineService.tick() -> flush sessions
 */
class PrisonMinesPlugin : JavaPlugin() {
    lateinit var service: MineService; private set
    private lateinit var states: BlockStates
    private lateinit var sink: PacketSink
    private lateinit var listener: MinePacketListener
    @Volatile private var settings: Settings? = null

    override fun onEnable() {
        saveDefaultConfig()
        val loaded = try {
            MineConfig.settings(config) to MineConfig.mines(config)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Invalid config, disabling: ${e.message}")
            server.pluginManager.disablePlugin(this); return
        }
        settings = loaded.first

        states = BlockStates()
        sink = PacketSink(states)
        val enchants = EnchantRegistry(this).apply {
            register(FortuneEnchant()); register(ExplosiveEnchant()); register(LayerEnchant())
        }
        val hooks = BukkitHooks(sink) { settings!! }
        service = MineService(loaded.second, sink, hooks, loaded.first.service) { id, t ->
            logger.log(Level.WARNING, "Enchant '$id' threw; ignoring it for this break", t)
        }

        val inbound = ConcurrentLinkedQueue<Inbound>()
        val processor = InboundProcessor(service, enchants, { settings!! }, inbound)
        listener = MinePacketListener(service, sink, states, inbound)
        PacketEvents.getAPI().eventManager.registerListener(listener)

        val playerListener = PlayerListener(this, service)
        server.pluginManager.registerEvents(playerListener, this)
        server.pluginManager.registerEvents(SafeAreaListener({ service.index }, { settings!! }), this)

        getCommand("pmine")?.let { MineCommand(this, service, enchants).also { c -> it.setExecutor(c); it.setTabCompleter(c) } }

        // Players already online (plugin reload): register their PacketEvents users and heal their view.
        for (p in server.onlinePlayers) {
            PacketEvents.getAPI().playerManager.getUser(p)?.let { u -> u.uuid?.let { id -> sink.users[id] = u } }
            service.onPlayerWorld(p.uniqueId, p.world.name)
            playerListener.reconcile(p)
        }

        // One tick = apply inbound mining on the main thread, then flush every touched session.
        server.scheduler.runTaskTimer(this, Runnable { processor.drain(); service.tick() }, 1L, 1L)
    }

    override fun onDisable() {
        if (::listener.isInitialized) PacketEvents.getAPI().eventManager.unregisterListener(listener)
    }

    /** Re-reads config.yml; keeps the old setup if the new one is invalid. */
    fun reloadMines(): Boolean = try {
        reloadConfig()
        val newSettings = MineConfig.settings(config)
        val index: MineIndex = MineConfig.mines(config)
        settings = newSettings
        states.invalidate()
        service.replaceIndex(index)
        for (p in server.onlinePlayers) {
            service.onPlayerWorld(p.uniqueId, p.world.name)
            service.reconcile(p.uniqueId) { cx, cz -> p.isChunkSent(io.prisonmines.core.ChunkKey.of(cx, cz)) }
        }
        true
    } catch (e: Exception) {
        logger.log(Level.SEVERE, "Reload failed: ${e.message}")
        false
    }
}
