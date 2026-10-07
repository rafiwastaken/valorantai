package io.prisonmines.core

import java.util.UUID

/**
 * One player's private instance of one [MineDefinition].
 *
 * State is deliberately tiny: a seed plus a sparse "mined" bitset. The visible block at any
 * position is `if (mined) AIR else generator(seed, pos)`.
 *
 * Threading: mutations happen on the server main thread only. The netty thread (chunk packets)
 * reads through [snapshotChunk] / [kindAt]. Every access to shared state goes through [lock],
 * which also makes "mark chunk loaded + snapshot it" atomic against "break + flush", so a client
 * can never end up with a stale block after a chunk (re)send.
 */
class MineSession internal constructor(
    val mine: MineDefinition,
    val owner: UUID,
    seed: Long,
    createdTick: Long,
    private val activate: (MineSession) -> Unit,
) {
    private val lock = Any()
    private val broken = BrokenSet(mine.volume)
    private val dirty = IntList()
    private val loaded = HashSet<Long>()
    private val resend = LinkedHashSet<Long>()
    private var queued = false

    @Volatile var seed: Long = seed; private set
    @Volatile var resetCount = 0; private set
    @Volatile var lastResetTick: Long = createdTick; private set

    /** Main-thread-only bookkeeping used by the dig state machine. */
    internal var digIndex = -1
    internal var digStartTick = 0L
    internal var breaksThisTick = 0
    internal var breakTick = -1L

    val brokenCount: Int get() = synchronized(lock) { broken.count }
    val minedFraction: Double get() = brokenCount.toDouble() / mine.volume

    /** Main thread only (the only writer). */
    fun isBroken(idx: Int): Boolean = broken[idx]

    /** Visible block at a position: palette index, or [AIR] if mined / outside the mine. */
    fun kindAt(x: Int, y: Int, z: Int): Int {
        if (!mine.mine.contains(x, y, z)) return AIR
        val i = mine.indexOf(x, y, z)
        return synchronized(lock) { if (broken[i]) AIR else mine.kindAtIndex(seed, i) }
    }

    fun isChunkLoaded(cx: Int, cz: Int): Boolean = synchronized(lock) { ChunkKey.of(cx, cz) in loaded }

    // ---- mutation (main thread) ------------------------------------------------------------

    /**
     * Marks [idxs] mined. Already-mined entries are skipped. Adds each kind to [tallies]
     * and writes the kind of every newly mined block to [kindsOut] (parallel to [idxs]; AIR if skipped).
     * @return number of newly mined blocks
     */
    internal fun applyBreaks(idxs: IntArray, n: Int, tallies: IntArray, kindsOut: IntArray): Int {
        var done = 0
        synchronized(lock) {
            for (k in 0 until n) {
                val i = idxs[k]
                if (broken.add(i)) {
                    val kind = mine.kindAtIndex(seed, i)
                    tallies[kind]++
                    kindsOut[k] = kind
                    dirty.add(i)
                    done++
                } else kindsOut[k] = AIR
            }
            if (done > 0) activateLocked()
        }
        return done
    }

    /** Re-send the authoritative state of one position on the next flush (rejected action, foreign packet). */
    fun invalidate(x: Int, y: Int, z: Int) {
        if (!mine.mine.contains(x, y, z)) return
        synchronized(lock) { dirty.add(mine.indexOf(x, y, z)); activateLocked() }
    }

    /** Re-send everything of one section that overlaps the mine (foreign multi-block packet). */
    fun invalidateSection(cx: Int, sy: Int, cz: Int) {
        val c = mine.mine
        val x0 = maxOf(c.minX, cx shl 4); val x1 = minOf(c.maxX, (cx shl 4) + 15)
        val y0 = maxOf(c.minY, sy shl 4); val y1 = minOf(c.maxY, (sy shl 4) + 15)
        val z0 = maxOf(c.minZ, cz shl 4); val z1 = minOf(c.maxZ, (cz shl 4) + 15)
        if (x0 > x1 || y0 > y1 || z0 > z1) return
        synchronized(lock) {
            for (y in y0..y1) for (z in z0..z1) for (x in x0..x1) dirty.add(mine.indexOf(x, y, z))
            activateLocked()
        }
    }

    /**
     * Resets the instance. [ResetMode.RESTORE] keeps the seed and re-sends only what was mined;
     * [ResetMode.RESHUFFLE] picks [newSeed] and queues a paced re-send of every loaded chunk.
     */
    internal fun reset(mode: ResetMode, newSeed: Long, tick: Long) {
        synchronized(lock) {
            when (mode) {
                ResetMode.RESTORE -> {
                    broken.forEach { dirty.add(it) }
                    broken.clear()
                }
                ResetMode.RESHUFFLE -> {
                    seed = newSeed
                    broken.clear()
                    dirty.clear()
                    resend.clear()
                    resend.addAll(loaded)
                }
            }
            resetCount++
            lastResetTick = tick
            digIndex = -1
            activateLocked()
        }
    }

    // ---- chunk tracking (netty / main) ----------------------------------------------------------

    /**
     * Called when the server sends a chunk overlapping the mine. Marks it loaded and returns the
     * sections to send *after* the chunk packet. Only un-mined blocks are included: the physical
     * world inside the mine is air, so mined positions are already correct.
     */
    fun snapshotChunk(cx: Int, cz: Int): List<SectionUpdate> {
        if (!mine.mine.overlapsChunk(cx, cz)) return emptyList()
        val out = ArrayList<SectionUpdate>()
        synchronized(lock) {
            loaded.add(ChunkKey.of(cx, cz))
            resend.remove(ChunkKey.of(cx, cz))
            buildChunk(cx, cz, includeAir = false) { out.add(it) }
        }
        return out
    }

    fun unloadChunk(cx: Int, cz: Int) {
        synchronized(lock) {
            val k = ChunkKey.of(cx, cz)
            loaded.remove(k); resend.remove(k)
        }
    }

    /** Forget everything the client has (world change / respawn). */
    fun clearLoaded() = synchronized(lock) { loaded.clear(); resend.clear(); dirty.clear() }

    /** Self-healing path: the client has this chunk but we never saw the packet. Queues a paced resend. */
    fun adoptChunk(cx: Int, cz: Int) {
        if (!mine.mine.overlapsChunk(cx, cz)) return
        synchronized(lock) {
            val k = ChunkKey.of(cx, cz)
            if (loaded.add(k)) { resend.add(k); activateLocked() }
        }
    }

    // ---- flushing (main thread) ------------------------------------------------------------

    /**
     * Called by the service for sessions that activated themselves. Emits, in order:
     *  1. pending single-block changes, grouped per section so a section costs one packet no matter
     *     how many blocks changed this tick (changes in chunks the client doesn't have are dropped;
     *     the chunk snapshot carries them), then
     *  2. up to [chunkBudget] queued full-chunk re-sends (paced reset).
     * Re-activates itself if re-sends remain.
     * @return chunks of re-send budget consumed
     */
    internal fun flush(chunkBudget: Int, out: (SectionUpdate) -> Unit): Int {
        synchronized(lock) {
            queued = false
            drainDirtyLocked(out)
            var used = 0
            val it = resend.iterator()
            while (used < chunkBudget && it.hasNext()) {
                val k = it.next(); it.remove(); used++
                buildChunk(ChunkKey.x(k), ChunkKey.z(k), includeAir = true, out)
            }
            if (resend.isNotEmpty()) activateLocked()
            return used
        }
    }

    private fun drainDirtyLocked(out: (SectionUpdate) -> Unit) {
        if (dirty.size == 0) return
        val groups = HashMap<Long, IntList>()
        dirty.forEach { i ->
            val x = mine.xOf(i); val z = mine.zOf(i); val y = mine.yOf(i)
            if (ChunkKey.of(x shr 4, z shr 4) !in loaded) return@forEach
            groups.getOrPut(SectionKey.of(x shr 4, y shr 4, z shr 4)) { IntList() }.add(i)
        }
        dirty.clear()
        for ((key, list) in groups) {
            val idx = list.toArray().also { it.sort() }
            var n = 0
            for (j in idx.indices) if (j == 0 || idx[j] != idx[j - 1]) idx[n++] = idx[j]
            val local = ShortArray(n)
            val kinds = IntArray(n)
            for (j in 0 until n) {
                val i = idx[j]
                local[j] = SectionUpdate.pack(mine.xOf(i), mine.yOf(i), mine.zOf(i))
                kinds[j] = if (broken[i]) AIR else mine.kindAtIndex(seed, i)
            }
            out(SectionUpdate(SectionKey.chunkX(key), SectionKey.sectionY(key), SectionKey.chunkZ(key), n, local, kinds))
        }
    }

    private fun activateLocked() {
        if (!queued) { queued = true; activate(this) }
    }

    private inline fun buildChunk(cx: Int, cz: Int, includeAir: Boolean, out: (SectionUpdate) -> Unit) {
        val c = mine.mine
        val x0 = maxOf(c.minX, cx shl 4); val x1 = minOf(c.maxX, (cx shl 4) + 15)
        val z0 = maxOf(c.minZ, cz shl 4); val z1 = minOf(c.maxZ, (cz shl 4) + 15)
        if (x0 > x1 || z0 > z1) return
        for (sy in (c.minY shr 4)..(c.maxY shr 4)) {
            val y0 = maxOf(c.minY, sy shl 4); val y1 = minOf(c.maxY, (sy shl 4) + 15)
            val cap = (x1 - x0 + 1) * (z1 - z0 + 1) * (y1 - y0 + 1)
            val local = ShortArray(cap)
            val kinds = IntArray(cap)
            var n = 0
            for (y in y0..y1) for (z in z0..z1) for (x in x0..x1) {
                val i = mine.indexOf(x, y, z)
                val kind = if (broken[i]) { if (!includeAir) continue; AIR } else mine.kindAtIndex(seed, i)
                local[n] = SectionUpdate.pack(x, y, z); kinds[n] = kind; n++
            }
            if (n > 0) out(SectionUpdate(cx, sy, cz, n, local, kinds))
        }
    }
}
