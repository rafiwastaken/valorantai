package io.prisonmines.core

import io.prisonmines.core.enchant.BatchScratch
import io.prisonmines.core.enchant.BreakBatch
import io.prisonmines.core.enchant.BreakOutcome
import io.prisonmines.core.enchant.EnchantApplication
import io.prisonmines.core.enchant.EnchantContext
import java.util.random.RandomGenerator

data class BreakLimits(
    /** Hard cap on blocks removed by a single break, enchants included. */
    val maxBlocksPerBreak: Int = 2048,
    /** Breaks accepted per session per tick (creative can send bursts; this bounds the work). */
    val maxBreaksPerTick: Int = 8,
)

/** A break that has been planned (enchants expanded and clipped) but not applied yet. */
class BreakPlan internal constructor(
    val session: MineSession,
    internal val batch: BreakBatch,
    val originIndex: Int,
    val rewardMultiplier: Double,
) {
    val size: Int get() = batch.size
}

/**
 * Turns "player mined X" into a clipped set of blocks, then applies it. Main thread only.
 *
 * plan -> (hooks may veto) -> commit. Nothing in here can modify the real world.
 */
class BreakEngine(
    private val limits: BreakLimits = BreakLimits(),
    private val random: RandomGenerator = RandomGenerator.getDefault(),
    private val onEnchantError: (id: String, Throwable) -> Unit = { _, _ -> },
) {
    private val scratch = HashMap<String, BatchScratch>()

    val breakLimits: BreakLimits get() = limits

    fun plan(session: MineSession, originIdx: Int, enchants: List<EnchantApplication>): BreakPlan? {
        if (session.isBroken(originIdx)) return null
        val mine = session.mine
        val sc = scratch.getOrPut(mine.id) { BatchScratch(mine.volume) }
        val batch = BreakBatch(session, sc, limits.maxBlocksPerBreak)
        batch.addIndex(originIdx) // origin is always first

        val ctx = EnchantContext(
            session, batch, mine.xOf(originIdx), mine.yOf(originIdx), mine.zOf(originIdx),
            random,
        )
        for (app in enchants) {
            try {
                app.enchant.apply(ctx, app.level)
            } catch (t: Throwable) {
                onEnchantError(app.enchant.id, t) // a faulty enchant must not stop normal mining
            }
        }
        return BreakPlan(session, batch, originIdx, ctx.rewardMultiplier)
    }

    fun commit(plan: BreakPlan): BreakOutcome {
        val session = plan.session
        val idxs = plan.batch.indices.data
        val n = plan.batch.size
        val tallies = IntArray(session.mine.kinds.size)
        val kinds = IntArray(n)
        val count = session.applyBreaks(idxs, n, tallies, kinds)
        return BreakOutcome(
            session, plan.originIndex, count,
            idxs.copyOf(n), kinds, tallies, plan.rewardMultiplier,
        )
    }
}
