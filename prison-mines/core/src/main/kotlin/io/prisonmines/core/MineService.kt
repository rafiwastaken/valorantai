package io.prisonmines.core

import io.prisonmines.core.enchant.BreakOutcome
import io.prisonmines.core.enchant.EnchantApplication
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.random.RandomGenerator

/** Platform output. Implementations must not block; they typically enqueue packets. */
interface ViewSink {
    fun sendSection(session: MineSession, update: SectionUpdate)

    /** Confirms client-side block predictions up to [sequence] (1.19+); sent after the block updates. */
    fun sendAck(player: UUID, sequence: Int)
}

/** Platform callbacks around a break. Always invoked on the main thread. */
interface MineHooks {
    /** Return false to veto the whole break (region plugins, cooldowns, ...). */
    fun beforeBreak(plan: BreakPlan): Boolean = true
    fun afterBreak(outcome: BreakOutcome) {}
    fun onReset(session: MineSession) {}
}

data class ServiceConfig(
    /** Global budget of full-chunk re-sends per tick, shared fairly between all resetting players. */
    val resendChunksPerTick: Int = 8,
    val limits: BreakLimits = BreakLimits(),
)

enum class DigAction { START, ABORT, FINISH }

class DigRequest(
    val x: Int, val y: Int, val z: Int,
    val action: DigAction,
    /** client prediction sequence (1.19+), or -1 */
    val sequence: Int,
    val creative: Boolean,
    /** computed by the platform from the player's eye position */
    val inReach: Boolean,
    val enchants: List<EnchantApplication>,
)

/** Per-player container; the netty thread and the main thread both touch it. */
class PlayerState internal constructor(val id: UUID) {
    internal val sessions = ConcurrentHashMap<String, MineSession>()
    @Volatile var world: String? = null
    @Volatile internal var pendingAck = -1
    internal var ackQueued = false

    fun sessions(): Collection<MineSession> = sessions.values
    fun session(mineId: String): MineSession? = sessions[mineId]
}

/**
 * Orchestrates all instances. Everything except [snapshotChunk]/[unloadChunk]/[onPlayerWorld]
 * must run on the main thread.
 *
 * Per tick the platform calls [tick], which flushes every session that has changes: one packet per
 * touched section per player, regardless of how many blocks changed.
 */
class MineService(
    index: MineIndex,
    private val sink: ViewSink,
    private val hooks: MineHooks,
    private val config: ServiceConfig = ServiceConfig(),
    /** Called from netty threads too (sessions are created lazily on chunk packets), so it must be thread-safe. */
    private val seedSource: () -> Long = { ThreadLocalRandom.current().nextLong() },
    onEnchantError: (String, Throwable) -> Unit = { _, _ -> },
) {
    @Volatile var index: MineIndex = index

    /** Main thread only, hence a plain (non thread-safe) generator is fine. */
    val engine = BreakEngine(config.limits, RandomGenerator.getDefault(), onEnchantError)

    private val players = ConcurrentHashMap<UUID, PlayerState>()
    private val active = ConcurrentLinkedQueue<MineSession>()
    private val ackPlayers = ConcurrentLinkedQueue<PlayerState>()
    @Volatile var currentTick = 0L; private set

    // ---- players / sessions ----------------------------------------------------------------------

    fun player(id: UUID): PlayerState = players.computeIfAbsent(id) { PlayerState(it) }
    fun playerIfPresent(id: UUID): PlayerState? = players[id]
    fun removePlayer(id: UUID) { players.remove(id) }
    fun onlinePlayers(): Collection<PlayerState> = players.values

    fun session(id: UUID, mine: MineDefinition): MineSession =
        player(id).sessions.computeIfAbsent(mine.id) {
            MineSession(mine, id, seedSource(), currentTick) { active.add(it) }
        }

    /** Replaces the mine set (reload). Sessions of removed/changed mines are dropped. */
    fun replaceIndex(newIndex: MineIndex) {
        index = newIndex
        for (p in players.values) p.sessions.entries.removeIf { (id, s) -> newIndex.get(id) !== s.mine }
    }

    /** Confirm a client block prediction without any dig (e.g. a cancelled block placement). */
    fun ack(id: UUID, sequence: Int) {
        if (sequence < 0) return
        val p = player(id)
        p.pendingAck = maxOf(p.pendingAck, sequence)
        queueAck(p)
    }

    fun onPlayerWorld(id: UUID, world: String) {
        val p = player(id)
        if (p.world != world) {
            p.world = world
            p.sessions.values.forEach { it.clearLoaded() } // client discarded its chunks
        }
    }

    // ---- chunk lifecycle (netty thread) --------------------------------------------------------

    /**
     * The server is sending chunk (cx,cz) to this player. Returns the section updates to send
     * right after it (empty for 99.9% of chunks).
     */
    fun snapshotChunk(id: UUID, cx: Int, cz: Int): List<Pair<MineSession, SectionUpdate>> {
        val p = players[id] ?: return emptyList()
        val world = p.world ?: return emptyList()
        val mines = index.minesInChunk(world, cx, cz)
        if (mines.isEmpty()) return emptyList()
        val out = ArrayList<Pair<MineSession, SectionUpdate>>()
        for (m in mines) {
            val s = session(id, m)
            for (u in s.snapshotChunk(cx, cz)) out.add(s to u)
        }
        return out
    }

    fun unloadChunk(id: UUID, cx: Int, cz: Int) {
        val p = players[id] ?: return
        val world = p.world ?: return
        for (m in index.minesInChunk(world, cx, cz)) p.sessions[m.id]?.unloadChunk(cx, cz)
    }

    /**
     * Self-healing: for the player's current world, adopt any mine chunk the client already has but
     * we never snapshotted (join races, teleports). [hasChunk] is the platform's "was it sent" probe.
     */
    fun reconcile(id: UUID, hasChunk: (cx: Int, cz: Int) -> Boolean) {
        val p = playerIfPresent(id) ?: return
        val world = p.world ?: return
        for (m in index.all) {
            if (m.world != world) continue
            val s = session(id, m)
            m.mine.forEachChunk { cx, cz -> if (!s.isChunkLoaded(cx, cz) && hasChunk(cx, cz)) s.adoptChunk(cx, cz) }
        }
    }

    // ---- digging (main thread) ---------------------------------------------------------------------

    /** Handles one intercepted dig packet for a block inside [mine]'s cuboid. */
    fun dig(id: UUID, mine: MineDefinition, req: DigRequest) {
        val session = session(id, mine)
        ack(id, req.sequence)

        val idx = mine.indexOf(req.x, req.y, req.z)
        fun reject() { session.digIndex = -1; session.invalidate(req.x, req.y, req.z) }

        when (req.action) {
            DigAction.START -> {
                if (!req.inReach || session.isBroken(idx)) return reject()
                if (req.creative) breakAt(session, idx, req.enchants) else {
                    session.digIndex = idx
                    session.digStartTick = currentTick
                }
            }
            DigAction.ABORT -> {
                session.digIndex = -1
                session.invalidate(req.x, req.y, req.z)
            }
            DigAction.FINISH -> {
                // a legitimate client always sent START for the same block first
                if (!req.inReach || session.digIndex != idx || session.isBroken(idx)) return reject()
                session.digIndex = -1
                breakAt(session, idx, req.enchants)
            }
        }
    }

    /** Public entry for other plugins: break blocks (clipped to the mine) as if the player had mined them. */
    fun breakBlocks(
        id: UUID, mine: MineDefinition, positions: Iterable<Triple<Int, Int, Int>>,
        enchants: List<EnchantApplication> = emptyList(),
    ): BreakOutcome? {
        val session = session(id, mine)
        var outcome: BreakOutcome? = null
        for ((x, y, z) in positions) {
            if (!mine.mine.contains(x, y, z)) continue // clip: never outside the mine
            outcome = breakAt(session, mine.indexOf(x, y, z), enchants) ?: outcome
        }
        return outcome
    }

    private fun breakAt(session: MineSession, idx: Int, enchants: List<EnchantApplication>): BreakOutcome? {
        if (session.breakTick != currentTick) { session.breakTick = currentTick; session.breaksThisTick = 0 }
        if (++session.breaksThisTick > config.limits.maxBreaksPerTick) {
            session.invalidate(session.mine.xOf(idx), session.mine.yOf(idx), session.mine.zOf(idx))
            return null
        }
        val plan = engine.plan(session, idx, enchants)
        if (plan == null || !hooks.beforeBreak(plan)) {
            session.invalidate(session.mine.xOf(idx), session.mine.yOf(idx), session.mine.zOf(idx))
            return null
        }
        val outcome = engine.commit(plan)
        hooks.afterBreak(outcome)
        val policy = session.mine.reset
        if (policy.thresholdFraction > 0 && session.minedFraction >= policy.thresholdFraction) reset(session)
        return outcome
    }

    // ---- reset ------------------------------------------------------------------------------------------

    fun reset(session: MineSession, mode: ResetMode = session.mine.reset.mode) {
        session.reset(mode, seedSource(), currentTick)
        hooks.onReset(session)
    }

    // ---- tick ----------------------------------------------------------------------------------------------

    /** Call exactly once per server tick, after inbound packets were processed. */
    fun tick() {
        currentTick++

        var budget = config.resendChunksPerTick
        // Only look at what is queued right now; sessions that re-activate go to the tail for next tick.
        var n = active.size
        while (n-- > 0) {
            val s = active.poll() ?: break
            budget -= s.flush(maxOf(budget, 0)) { sink.sendSection(s, it) }
        }

        // Acks must follow the block updates they confirm.
        while (true) {
            val p = ackPlayers.poll() ?: break
            p.ackQueued = false
            val seq = p.pendingAck
            p.pendingAck = -1
            if (seq >= 0) sink.sendAck(p.id, seq)
        }

        if (currentTick % 20L == 0L) checkTimedResets()
    }

    private fun queueAck(p: PlayerState) {
        if (!p.ackQueued) { p.ackQueued = true; ackPlayers.add(p) }
    }

    private fun checkTimedResets() {
        for (p in players.values) for (s in p.sessions.values) {
            val every = s.mine.reset.intervalTicks
            if (every > 0 && currentTick - s.lastResetTick >= every) reset(s)
        }
    }
}
