package io.prisonmines.core.enchant

import io.prisonmines.core.Cuboid
import io.prisonmines.core.IntList
import io.prisonmines.core.MineSession
import java.util.random.RandomGenerator

/**
 * A pickaxe enchant that reacts to a block being mined.
 *
 * Enchants never touch the world. They only *nominate* extra positions through [EnchantContext];
 * every nomination is clipped to the mine cuboid before it is recorded. That clip is the single
 * choke point that guarantees an enchant can't reach outside the mine or into the safe area, no
 * matter how buggy or hostile its shape maths is.
 */
interface MineEnchant {
    val id: String
    fun apply(ctx: EnchantContext, level: Int)
}

data class EnchantApplication(val enchant: MineEnchant, val level: Int)

class EnchantContext internal constructor(
    private val session: MineSession,
    private val batch: BreakBatch,
    val originX: Int, val originY: Int, val originZ: Int,
    val random: RandomGenerator,
) {
    /** The only region this context can ever affect. Use it to size shapes; clipping is enforced anyway. */
    val bounds: Cuboid get() = session.mine.mine

    /** Multiplies the rewards (drops) of the whole break, e.g. a fortune-style enchant. */
    var rewardMultiplier = 1.0

    val remainingCapacity: Int get() = batch.remaining

    /** @return true if the block was newly added (inside the mine, not yet mined, capacity left). */
    fun add(x: Int, y: Int, z: Int): Boolean = batch.add(x, y, z)

    /** Adds the intersection of the box with the mine. @return blocks added */
    fun addCuboid(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Int {
        val b = bounds
        val minX = maxOf(minOf(x1, x2), b.minX); val maxX = minOf(maxOf(x1, x2), b.maxX)
        val minY = maxOf(minOf(y1, y2), b.minY); val maxY = minOf(maxOf(y1, y2), b.maxY)
        val minZ = maxOf(minOf(z1, z2), b.minZ); val maxZ = minOf(maxOf(z1, z2), b.maxZ)
        if (minX > maxX || minY > maxY || minZ > maxZ) return 0
        var added = 0
        for (y in minY..maxY) for (z in minZ..maxZ) for (x in minX..maxX) {
            if (batch.isFull) return added
            if (batch.add(x, y, z)) added++
        }
        return added
    }

    /** Ball of [radius] around the origin, clipped to the mine. */
    fun addSphere(radius: Int): Int {
        val b = bounds
        val r2 = radius.toLong() * radius + radius // slightly rounded-out ball
        val minX = maxOf(originX - radius, b.minX); val maxX = minOf(originX + radius, b.maxX)
        val minY = maxOf(originY - radius, b.minY); val maxY = minOf(originY + radius, b.maxY)
        val minZ = maxOf(originZ - radius, b.minZ); val maxZ = minOf(originZ + radius, b.maxZ)
        var added = 0
        for (y in minY..maxY) for (z in minZ..maxZ) for (x in minX..maxX) {
            val dx = (x - originX).toLong(); val dy = (y - originY).toLong(); val dz = (z - originZ).toLong()
            if (dx * dx + dy * dy + dz * dz > r2) continue
            if (batch.isFull) return added
            if (batch.add(x, y, z)) added++
        }
        return added
    }

    /** The horizontal slice of the mine at the origin's height. */
    fun addLayer(): Int = addCuboid(bounds.minX, originY, bounds.minZ, bounds.maxX, originY, bounds.maxZ)
}

/**
 * Blocks queued for removal in one break. Dedupes against itself and against already-mined
 * blocks, enforces the per-break cap, and rejects anything outside the mine.
 */
class BreakBatch internal constructor(
    private val session: MineSession,
    private val scratch: BatchScratch,
    private val cap: Int,
) {
    internal val indices = IntList()
    private val epoch = scratch.nextEpoch()

    val size: Int get() = indices.size
    val remaining: Int get() = cap - indices.size
    val isFull: Boolean get() = indices.size >= cap

    fun add(x: Int, y: Int, z: Int): Boolean {
        val mine = session.mine
        if (!mine.mine.contains(x, y, z)) return false
        return addIndex(mine.indexOf(x, y, z))
    }

    internal fun addIndex(i: Int): Boolean {
        if (isFull || scratch.stamp[i] == epoch || session.isBroken(i)) return false
        scratch.stamp[i] = epoch
        indices.add(i)
        return true
    }
}

/** Per-mine dedupe stamps, shared by all sessions of the mine (main thread only). */
class BatchScratch(volume: Int) {
    internal val stamp = IntArray(volume)
    private var epoch = 0

    internal fun nextEpoch(): Int {
        if (++epoch == Int.MAX_VALUE) { stamp.fill(0); epoch = 1 }
        return epoch
    }
}

/** What actually happened in one committed break. */
class BreakOutcome(
    val session: MineSession,
    val originIndex: Int,
    val count: Int,
    /** mine-relative indices that were newly mined (origin first) */
    val indices: IntArray,
    /** palette index of each entry in [indices] */
    val kinds: IntArray,
    /** per palette index: how many blocks of that kind were mined */
    val tallies: IntArray,
    val rewardMultiplier: Double,
)
