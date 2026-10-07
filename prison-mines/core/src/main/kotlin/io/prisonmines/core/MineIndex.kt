package io.prisonmines.core

/**
 * Immutable spatial lookup over all mines. Built once, swapped atomically on reload, safe to read
 * from the netty threads and the main thread alike. Lookups are O(1) hash hits per chunk.
 */
class MineIndex(mines: Collection<MineDefinition>) {
    val all: List<MineDefinition> = mines.toList()
    private val byId = all.associateBy { it.id }
    private val safeByChunk = HashMap<String, HashMap<Long, MutableList<MineDefinition>>>()
    private val mineByChunk = HashMap<String, HashMap<Long, MutableList<MineDefinition>>>()

    init {
        for (m in all) {
            for (o in all) require(m === o || m.world != o.world || !m.mine.intersects(o.mine)) {
                "mine cuboids of ${m.id} and ${o.id} overlap"
            }
            m.safe.forEachChunk { cx, cz ->
                safeByChunk.getOrPut(m.world) { HashMap() }.getOrPut(ChunkKey.of(cx, cz)) { ArrayList(1) }.add(m)
            }
            m.mine.forEachChunk { cx, cz ->
                mineByChunk.getOrPut(m.world) { HashMap() }.getOrPut(ChunkKey.of(cx, cz)) { ArrayList(1) }.add(m)
            }
        }
    }

    fun get(id: String): MineDefinition? = byId[id]
    fun hasMines(world: String): Boolean = mineByChunk.containsKey(world)

    /** Mines whose breakable cuboid has blocks in this chunk. */
    fun minesInChunk(world: String, cx: Int, cz: Int): List<MineDefinition> =
        mineByChunk[world]?.get(ChunkKey.of(cx, cz)) ?: emptyList()

    /** The mine whose breakable cuboid contains this block, if any. */
    fun mineAt(world: String, x: Int, y: Int, z: Int): MineDefinition? {
        val list = mineByChunk[world]?.get(ChunkKey.of(x shr 4, z shr 4)) ?: return null
        for (m in list) if (m.mine.contains(x, y, z)) return m
        return null
    }

    /** The mine whose protected region (safe area, which includes the mine) contains this block, if any. */
    fun safeAt(world: String, x: Int, y: Int, z: Int): MineDefinition? {
        val list = safeByChunk[world]?.get(ChunkKey.of(x shr 4, z shr 4)) ?: return null
        for (m in list) if (m.safe.contains(x, y, z)) return m
        return null
    }

    /** Mines whose safe area touches the chunk; used for protection checks. */
    fun safeMinesInChunk(world: String, cx: Int, cz: Int): List<MineDefinition> =
        safeByChunk[world]?.get(ChunkKey.of(cx, cz)) ?: emptyList()
}
