package io.prisonmines.core

/** Inclusive axis-aligned block box. */
data class Cuboid(
    val minX: Int, val minY: Int, val minZ: Int,
    val maxX: Int, val maxY: Int, val maxZ: Int,
) {
    init {
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) { "Cuboid min must be <= max: $this" }
    }

    val sizeX: Int get() = maxX - minX + 1
    val sizeY: Int get() = maxY - minY + 1
    val sizeZ: Int get() = maxZ - minZ + 1
    val volume: Long get() = sizeX.toLong() * sizeY * sizeZ

    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    fun contains(o: Cuboid): Boolean =
        o.minX >= minX && o.maxX <= maxX && o.minY >= minY && o.maxY <= maxY && o.minZ >= minZ && o.maxZ <= maxZ

    fun intersects(o: Cuboid): Boolean =
        minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY && minZ <= o.maxZ && maxZ >= o.minZ

    fun overlapsChunk(cx: Int, cz: Int): Boolean =
        minX <= (cx shl 4) + 15 && maxX >= (cx shl 4) && minZ <= (cz shl 4) + 15 && maxZ >= (cz shl 4)

    fun expand(m: Int): Cuboid = Cuboid(minX - m, minY - m, minZ - m, maxX + m, maxY + m, maxZ + m)

    inline fun forEachChunk(fn: (cx: Int, cz: Int) -> Unit) {
        for (cx in (minX shr 4)..(maxX shr 4)) for (cz in (minZ shr 4)..(maxZ shr 4)) fn(cx, cz)
    }

    companion object {
        /** Builds a cuboid from any two opposite corners. */
        fun between(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) = Cuboid(
            minOf(x1, x2), minOf(y1, y2), minOf(z1, z2),
            maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2),
        )
    }
}
