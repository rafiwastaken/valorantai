package io.prisonmines.core

const val AIR = -1

/**
 * Pure function (seed, position) -> palette index. Because it is stateless a player's un-mined
 * blocks cost zero memory, and "reset" is just swapping the seed.
 */
interface MineGenerator {
    val kindCount: Int
    fun kindAt(seed: Long, x: Int, y: Int, z: Int): Int
}

class WeightedGenerator(weights: IntArray) : MineGenerator {
    private val cumulative = IntArray(weights.size)
    private val total: Int

    init {
        require(weights.isNotEmpty() && weights.all { it > 0 })
        var sum = 0
        for (i in weights.indices) { sum += weights[i]; cumulative[i] = sum }
        total = sum
    }

    override val kindCount: Int get() = cumulative.size

    override fun kindAt(seed: Long, x: Int, y: Int, z: Int): Int {
        if (cumulative.size == 1) return 0
        val r = ((hash(seed, x, y, z) ushr 1) % total).toInt()
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (r < cumulative[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }

    companion object {
        private fun fmix(k0: Long): Long {
            var k = k0
            k = k xor (k ushr 33); k *= -49064778989728563L
            k = k xor (k ushr 33); k *= -4265267296055464877L
            return k xor (k ushr 33)
        }

        fun hash(seed: Long, x: Int, y: Int, z: Int): Long {
            var h = seed
            h = fmix(h xor (x.toLong() * -7046029254386353131L))
            h = fmix(h xor (y.toLong() * -4658895280553007687L))
            h = fmix(h xor (z.toLong() * -7723592293110705685L))
            return h
        }
    }
}
