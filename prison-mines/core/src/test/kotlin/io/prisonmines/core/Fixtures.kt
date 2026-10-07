package io.prisonmines.core

import io.prisonmines.core.enchant.BreakOutcome
import java.util.UUID

fun testMine(
    id: String = "a",
    mine: Cuboid = Cuboid(0, 0, 0, 31, 15, 31),
    reset: ResetPolicy = ResetPolicy(thresholdFraction = 0.0),
) = MineDefinition(
    id = id, world = "w", mine = mine, safe = mine.expand(4),
    kinds = listOf(BlockKind("stone", 70), BlockKind("coal", 20), BlockKind("diamond", 10)),
    spawn = SpawnPoint(0.0, 20.0, 0.0), reset = reset,
)

class RecordingSink : ViewSink {
    val sections = ArrayList<Pair<MineSession, SectionUpdate>>()
    val acks = ArrayList<Pair<UUID, Int>>()
    override fun sendSection(session: MineSession, update: SectionUpdate) { sections.add(session to update) }
    override fun sendAck(player: UUID, sequence: Int) { acks.add(player to sequence) }

    fun forPlayer(id: UUID) = sections.filter { it.first.owner == id }.map { it.second }
    fun clear() { sections.clear(); acks.clear() }
}

open class RecordingHooks : MineHooks {
    val outcomes = ArrayList<BreakOutcome>()
    var resets = 0
    override fun afterBreak(outcome: BreakOutcome) { outcomes.add(outcome) }
    override fun onReset(session: MineSession) { resets++ }
}

/** Service + a player who already has every chunk of [mine] on the client. */
class Harness(val mine: MineDefinition = testMine(), config: ServiceConfig = ServiceConfig(), hooks: RecordingHooks = RecordingHooks()) {
    val sink = RecordingSink()
    val hooks = hooks
    val service = MineService(MineIndex(listOf(mine)), sink, hooks, config)

    fun join(): UUID {
        val id = UUID.randomUUID()
        service.player(id)
        service.onPlayerWorld(id, mine.world)
        mine.mine.forEachChunk { cx, cz -> service.snapshotChunk(id, cx, cz) }
        return id
    }

    fun dig(id: UUID, x: Int, y: Int, z: Int, creative: Boolean = true, action: DigAction = DigAction.START, seq: Int = -1,
            enchants: List<io.prisonmines.core.enchant.EnchantApplication> = emptyList()) =
        service.dig(id, mine, DigRequest(x, y, z, action, seq, creative, true, enchants))
}
