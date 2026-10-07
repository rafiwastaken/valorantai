package io.prisonmines.core

/** Packing helpers. Chunk keys match Minecraft's `ChunkPos.asLong` layout. */
object ChunkKey {
    fun of(cx: Int, cz: Int): Long = (cx.toLong() and 0xFFFFFFFFL) or (cz.toLong() shl 32)
    fun x(key: Long): Int = key.toInt()
    fun z(key: Long): Int = (key shr 32).toInt()
}

/** A 16x16x16 section: 26 bits chunkX | 26 bits chunkZ | 12 bits sectionY (all signed). */
object SectionKey {
    fun of(cx: Int, sy: Int, cz: Int): Long =
        ((cx.toLong() and 0x3FFFFFF) shl 38) or ((cz.toLong() and 0x3FFFFFF) shl 12) or (sy.toLong() and 0xFFF)

    fun chunkX(key: Long): Int = (key shr 38).toInt()
    fun chunkZ(key: Long): Int = ((((key shr 12) and 0x3FFFFFF) shl 38) shr 38).toInt()
    fun sectionY(key: Long): Int = (((key and 0xFFF) shl 52) shr 52).toInt()
}

/** Small primitive growable list; avoids boxing on the hot paths. */
class IntList(initial: Int = 16) {
    var data = IntArray(initial); private set
    var size = 0; private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
    inline fun forEach(fn: (Int) -> Unit) { for (i in 0 until size) fn(data[i]) }
}
