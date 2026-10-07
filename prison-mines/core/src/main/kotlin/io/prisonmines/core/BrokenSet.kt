package io.prisonmines.core

/**
 * Sparse bitset of "this position was mined" flags, indexed by mine-relative block index.
 *
 * Pages of 4096 bits are allocated on first write, so a player who mined 20 blocks of a
 * 100x100x100 mine costs one 512-byte page rather than 125 KB. This is the *only* per-player
 * block state: everything un-mined is regenerated from the seed on demand.
 */
class BrokenSet(val capacity: Int) {
    private val pages = arrayOfNulls<LongArray>((capacity + PAGE_BITS - 1) / PAGE_BITS)
    var count = 0; private set

    operator fun get(i: Int): Boolean {
        val page = pages[i ushr PAGE_SHIFT] ?: return false
        val b = i and PAGE_MASK
        return (page[b ushr 6] ushr (b and 63)) and 1L != 0L
    }

    /** @return true if the bit was newly set. */
    fun add(i: Int): Boolean {
        val p = i ushr PAGE_SHIFT
        val page = pages[p] ?: LongArray(PAGE_BITS / 64).also { pages[p] = it }
        val b = i and PAGE_MASK
        val mask = 1L shl (b and 63)
        if (page[b ushr 6] and mask != 0L) return false
        page[b ushr 6] = page[b ushr 6] or mask
        count++
        return true
    }

    fun clear() {
        pages.fill(null)
        count = 0
    }

    fun forEach(fn: (Int) -> Unit) {
        for (p in pages.indices) {
            val page = pages[p] ?: continue
            for (w in page.indices) {
                var bits = page[w]
                while (bits != 0L) {
                    val t = java.lang.Long.numberOfTrailingZeros(bits)
                    fn((p shl PAGE_SHIFT) + (w shl 6) + t)
                    bits = bits and (bits - 1)
                }
            }
        }
    }

    companion object {
        private const val PAGE_SHIFT = 12
        private const val PAGE_BITS = 1 shl PAGE_SHIFT
        private const val PAGE_MASK = PAGE_BITS - 1
    }
}
