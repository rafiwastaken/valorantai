package io.prisonmines.core

/**
 * One entry of a mine's palette. [key] / [drop] are namespaced ids ("minecraft:stone"); the core
 * never interprets them, the platform layer maps them to real block states and items.
 */
data class BlockKind(val key: String, val weight: Int, val drop: String = key, val dropAmount: Int = 1) {
    init { require(weight > 0) { "weight must be > 0 for $key" } }
}

data class SpawnPoint(val x: Double, val y: Double, val z: Double, val yaw: Float = 0f, val pitch: Float = 0f)

enum class ResetMode {
    /** Same seed again: only the positions the player mined are re-sent. O(mined blocks). Cheapest. */
    RESTORE,

    /** New seed, so the ore layout changes: the whole mine is re-sent, paced over several ticks. */
    RESHUFFLE,
}

/**
 * @param thresholdFraction auto-reset once this fraction of the mine is mined (0 disables)
 * @param intervalTicks     auto-reset this long after the last reset (0 disables)
 */
data class ResetPolicy(
    val mode: ResetMode = ResetMode.RESHUFFLE,
    val thresholdFraction: Double = 0.75,
    val intervalTicks: Long = 0,
)

/**
 * Static, immutable description of a physical mine location. Shared by every player's instance.
 *
 * @property mine the only region whose blocks are virtual and breakable
 * @property safe the protected region around it (must contain [mine]); also the region in which
 *                a player's instance is "interested"
 */
class MineDefinition(
    val id: String,
    val world: String,
    val mine: Cuboid,
    val safe: Cuboid,
    val kinds: List<BlockKind>,
    val spawn: SpawnPoint,
    val reset: ResetPolicy = ResetPolicy(),
    val generator: MineGenerator = WeightedGenerator(kinds.map { it.weight }.toIntArray()),
) {
    init {
        require(kinds.isNotEmpty()) { "mine $id needs at least one block kind" }
        require(safe.contains(mine)) { "safe area of $id must contain the mine cuboid" }
        require(mine.volume <= Int.MAX_VALUE) { "mine $id is too large" }
        require(generator.kindCount == kinds.size) { "generator/kinds mismatch in $id" }
    }

    val sizeX = mine.sizeX
    val sizeZ = mine.sizeZ
    private val layer = sizeX * sizeZ
    val volume: Int = mine.volume.toInt()

    fun indexOf(x: Int, y: Int, z: Int): Int =
        ((y - mine.minY) * sizeZ + (z - mine.minZ)) * sizeX + (x - mine.minX)

    fun xOf(i: Int): Int = mine.minX + i % sizeX
    fun zOf(i: Int): Int = mine.minZ + (i / sizeX) % sizeZ
    fun yOf(i: Int): Int = mine.minY + i / layer

    fun kindAtIndex(seed: Long, i: Int): Int = generator.kindAt(seed, xOf(i), yOf(i), zOf(i))
}
