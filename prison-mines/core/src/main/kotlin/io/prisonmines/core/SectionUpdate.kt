package io.prisonmines.core

/**
 * A batch of block changes inside one 16x16x16 section: the unit the platform turns into one
 * multi-block-change packet. [kinds] hold palette indices, or [AIR].
 */
class SectionUpdate(
    val chunkX: Int, val sectionY: Int, val chunkZ: Int,
    val count: Int,
    val local: ShortArray,
    val kinds: IntArray,
) {
    fun x(i: Int): Int = (chunkX shl 4) + ((local[i].toInt() shr 8) and 15)
    fun z(i: Int): Int = (chunkZ shl 4) + ((local[i].toInt() shr 4) and 15)
    fun y(i: Int): Int = (sectionY shl 4) + (local[i].toInt() and 15)

    companion object {
        fun pack(x: Int, y: Int, z: Int): Short = (((x and 15) shl 8) or ((z and 15) shl 4) or (y and 15)).toShort()
    }
}
