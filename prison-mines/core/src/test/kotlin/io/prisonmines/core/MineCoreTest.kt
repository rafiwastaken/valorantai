package io.prisonmines.core

import io.prisonmines.core.enchant.EnchantApplication
import io.prisonmines.core.enchant.EnchantContext
import io.prisonmines.core.enchant.MineEnchant
import kotlin.random.Random
import kotlin.test.*

class MineCoreTest {

    // ---- primitives ------------------------------------------------------------------------------

    @Test fun keysRoundTripIncludingNegatives() {
        for ((cx, cz) in listOf(0 to 0, -1 to 5, 7 to -9, -1_000_000 to 1_000_000, 33_554_431 to -33_554_432)) {
            val k = ChunkKey.of(cx, cz)
            assertEquals(cx, ChunkKey.x(k)); assertEquals(cz, ChunkKey.z(k))
        }
        for (sy in listOf(-4, -1, 0, 5, 19)) {
            val k = SectionKey.of(-12, sy, 99)
            assertEquals(-12, SectionKey.chunkX(k)); assertEquals(sy, SectionKey.sectionY(k)); assertEquals(99, SectionKey.chunkZ(k))
        }
    }

    @Test fun indexRoundTrip() {
        val m = testMine(mine = Cuboid(-20, -64, 100, 10, -40, 130))
        for (i in 0 until m.volume step 7) {
            assertEquals(i, m.indexOf(m.xOf(i), m.yOf(i), m.zOf(i)))
            assertTrue(m.mine.contains(m.xOf(i), m.yOf(i), m.zOf(i)))
        }
    }

    @Test fun brokenSetIsSparseAndCorrect() {
        val s = BrokenSet(100_000)
        val ref = HashSet<Int>()
        val r = Random(1)
        repeat(5000) { val i = r.nextInt(100_000); assertEquals(ref.add(i), s.add(i)) }
        assertEquals(ref.size, s.count)
        for (i in 0 until 100_000) assertEquals(i in ref, s[i])
        val seen = HashSet<Int>(); s.forEach { assertTrue(seen.add(it)) }
        assertEquals(ref, seen)
        s.clear(); assertEquals(0, s.count); assertFalse(s[ref.first()])
    }

    @Test fun generatorIsDeterministicAndRoughlyWeighted() {
        val g = WeightedGenerator(intArrayOf(70, 20, 10))
        val counts = IntArray(3)
        for (x in 0 until 40) for (y in 0 until 40) for (z in 0 until 40) {
            val k = g.kindAt(42, x, y, z); assertEquals(k, g.kindAt(42, x, y, z)); counts[k]++
        }
        val total = 64_000.0
        assertEquals(0.70, counts[0] / total, 0.02); assertEquals(0.20, counts[1] / total, 0.02); assertEquals(0.10, counts[2] / total, 0.02)
        assertNotEquals(g.kindAt(1, 3, 3, 3).let { (0 until 50).map { s -> g.kindAt(s.toLong(), 3, 3, 3) } }.toSet().size, 1)
    }

    // ---- instancing ------------------------------------------------------------------------------

    @Test fun playersHaveIndependentInstances() {
        val h = Harness()
        val a = h.join(); val b = h.join()
        h.dig(a, 5, 5, 5)
        h.service.tick()

        assertEquals(AIR, h.service.session(a, h.mine).kindAt(5, 5, 5))
        assertNotEquals(AIR, h.service.session(b, h.mine).kindAt(5, 5, 5), "b must still see the block")
        assertTrue(h.sink.forPlayer(b).isEmpty(), "nothing is sent to b")
        assertEquals(1, h.sink.forPlayer(a).sumOf { it.count })
    }

    @Test fun initialSnapshotMatchesSessionStateAndSkipsMinedBlocks() {
        val h = Harness()
        val a = h.join()
        h.dig(a, 1, 1, 1); h.dig(a, 2, 1, 1); h.service.tick(); h.sink.clear()

        // client re-receives chunk (0,0) (e.g. view-distance re-entry)
        h.service.unloadChunk(a, 0, 0)
        val snap = h.service.snapshotChunk(a, 0, 0)
        var blocks = 0
        for ((s, u) in snap) for (i in 0 until u.count) {
            blocks++
            assertEquals(s.kindAt(u.x(i), u.y(i), u.z(i)), u.kinds[i])
            assertNotEquals(AIR, u.kinds[i])
        }
        assertEquals(16 * 16 * 16 - 2, blocks)
    }

    @Test fun updatesAreCoalescedPerSectionAndOnlyForLoadedChunks() {
        val h = Harness()
        val a = h.join()
        // 100 blocks in one section + 1 block elsewhere
        for (i in 0 until 100) h.dig(a, i % 10, i / 10, 0, creative = true)
        h.dig(a, 20, 12, 20)
        h.service.tick()
        val ups = h.sink.forPlayer(a)
        assertEquals(2, ups.size, "one packet per touched section")
        assertEquals(101, ups.sumOf { it.count })

        // chunk the client doesn't have: no packet, but state still changes
        h.sink.clear()
        h.service.unloadChunk(a, 1, 1)
        h.dig(a, 20, 3, 20); h.service.tick()
        assertTrue(h.sink.forPlayer(a).isEmpty())
        assertEquals(AIR, h.service.session(a, h.mine).kindAt(20, 3, 20))
        val snap = h.service.snapshotChunk(a, 1, 1).flatMap { (_, u) -> (0 until u.count).map { u.x(it) to u.z(it) to u.y(it) } }
        assertFalse(snap.contains(20 to 20 to 3), "mined block excluded from later snapshot")
    }

    // ---- digging state machine --------------------------------------------------------------------

    @Test fun survivalNeedsStartThenFinishOnSameBlock() {
        val h = Harness()
        val a = h.join()
        h.dig(a, 3, 3, 3, creative = false, action = DigAction.FINISH)           // no start -> rejected
        h.service.tick()
        assertNotEquals(AIR, h.service.session(a, h.mine).kindAt(3, 3, 3))
        assertTrue(h.sink.forPlayer(a).any { it.count == 1 && it.kinds[0] != AIR }, "authoritative state re-sent")

        h.dig(a, 3, 3, 3, creative = false, action = DigAction.START)
        h.dig(a, 4, 3, 3, creative = false, action = DigAction.FINISH)           // different block -> rejected
        assertNotEquals(AIR, h.service.session(a, h.mine).kindAt(4, 3, 3))

        h.dig(a, 3, 3, 3, creative = false, action = DigAction.START)
        h.dig(a, 3, 3, 3, creative = false, action = DigAction.FINISH)
        assertEquals(AIR, h.service.session(a, h.mine).kindAt(3, 3, 3))
        assertEquals(1, h.hooks.outcomes.size)
    }

    @Test fun outOfReachDigsAreRejectedAndResynced() {
        val h = Harness()
        val a = h.join()
        h.service.dig(a, h.mine, DigRequest(3, 3, 3, DigAction.START, -1, true, inReach = false, emptyList()))
        h.service.tick()
        assertNotEquals(AIR, h.service.session(a, h.mine).kindAt(3, 3, 3))
        assertTrue(h.hooks.outcomes.isEmpty())
    }

    @Test fun acksFollowBlockUpdatesAndKeepHighestSequence() {
        val h = Harness()
        val a = h.join()
        h.dig(a, 1, 1, 1, seq = 5); h.dig(a, 2, 1, 1, seq = 7); h.dig(a, 3, 1, 1, seq = 6)
        h.service.tick()
        assertEquals(listOf(a to 7), h.sink.acks)
        h.sink.clear(); h.service.tick()
        assertTrue(h.sink.acks.isEmpty())
    }

    @Test fun perTickBreakCapBoundsBurstWork() {
        val h = Harness(config = ServiceConfig(limits = BreakLimits(maxBreaksPerTick = 3)))
        val a = h.join()
        for (i in 0 until 10) h.dig(a, i, 0, 0)
        assertEquals(3, h.hooks.outcomes.size)
        h.service.tick()
        h.dig(a, 9, 0, 0)
        assertEquals(4, h.hooks.outcomes.size, "budget refreshes next tick")
    }

    // ---- enchants: the safety guarantee ------------------------------------------------------------

    private class Rogue : MineEnchant {
        override val id = "rogue"
        override fun apply(ctx: EnchantContext, level: Int) {
            // try everything to escape the mine: giant shapes, corners, far away, int extremes
            ctx.addCuboid(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
            ctx.addSphere(1000)
            ctx.add(-1, 0, 0); ctx.add(32, 5, 5); ctx.add(5, -1, 5); ctx.add(5, 16, 5); ctx.add(5, 5, 32)
            ctx.add(Int.MAX_VALUE, 0, 0)
        }
    }

    @Test fun enchantsCanNeverEscapeTheMineCuboid() {
        val mine = testMine(mine = Cuboid(10, 10, 10, 41, 25, 41))
        val h = Harness(mine, ServiceConfig(limits = BreakLimits(maxBlocksPerBreak = 1_000_000)))
        val a = h.join()
        h.dig(a, 20, 20, 20, enchants = listOf(EnchantApplication(Rogue(), 1)))
        val o = h.hooks.outcomes.single()
        assertEquals(mine.volume, o.count, "rogue enchant clears exactly the mine, nothing more")
        for (i in o.indices) assertTrue(mine.mine.contains(mine.xOf(i), mine.yOf(i), mine.zOf(i)))
        h.service.tick()
        for (u in h.sink.forPlayer(a)) for (i in 0 until u.count) assertTrue(mine.mine.contains(u.x(i), u.y(i), u.z(i)))
    }

    @Test fun perBreakCapAppliesAndOriginIsAlwaysIncluded() {
        val h = Harness(config = ServiceConfig(limits = BreakLimits(maxBlocksPerBreak = 50)))
        val a = h.join()
        h.dig(a, 16, 8, 16, enchants = listOf(EnchantApplication(Rogue(), 1)))
        val o = h.hooks.outcomes.single()
        assertEquals(50, o.count)
        assertEquals(h.mine.indexOf(16, 8, 16), o.indices[0])
    }

    @Test fun crashingEnchantDoesNotStopMining() {
        var reported: String? = null
        val h = Harness()
        val svc = MineService(MineIndex(listOf(h.mine)), h.sink, h.hooks, onEnchantError = { id, _ -> reported = id })
        val id = java.util.UUID.randomUUID(); svc.player(id); svc.onPlayerWorld(id, "w")
        val boom = object : MineEnchant { override val id = "boom"; override fun apply(ctx: EnchantContext, level: Int) = error("x") }
        svc.dig(id, h.mine, DigRequest(1, 1, 1, DigAction.START, -1, true, true, listOf(EnchantApplication(boom, 1))))
        assertEquals("boom", reported)
        assertEquals(AIR, svc.session(id, h.mine).kindAt(1, 1, 1))
    }

    @Test fun enchantsDoNotDoubleCountAlreadyMinedBlocks() {
        val h = Harness()
        val a = h.join()
        val sphere = object : MineEnchant { override val id = "s"; override fun apply(ctx: EnchantContext, level: Int) { ctx.addSphere(3) } }
        val app = listOf(EnchantApplication(sphere, 1))
        h.dig(a, 16, 8, 16, enchants = app)
        val first = h.hooks.outcomes.last().count
        h.dig(a, 20, 8, 16, enchants = app) // unmined origin, but its sphere overlaps the first crater
        val second = h.hooks.outcomes.last()
        assertTrue(second.count in 1 until first)
        assertEquals(second.count, second.tallies.sum())
        assertEquals(first + second.count, h.service.session(a, h.mine).brokenCount)
    }

    @Test fun externalBreakApiIsClippedToo() {
        val h = Harness()
        val a = h.join()
        h.service.breakBlocks(a, h.mine, listOf(Triple(-1, 0, 0), Triple(0, 0, 0), Triple(100, 100, 100), Triple(31, 15, 31)))
        assertEquals(2, h.service.session(a, h.mine).brokenCount)
    }

    // ---- resets --------------------------------------------------------------------------------------

    @Test fun restoreResendsOnlyWhatWasMinedAndKeepsLayout() {
        val h = Harness(testMine(reset = ResetPolicy(ResetMode.RESTORE, 0.0)))
        val a = h.join(); val s = h.service.session(a, h.mine)
        val before = s.kindAt(5, 5, 5)
        h.dig(a, 5, 5, 5); h.dig(a, 6, 5, 5); h.service.tick(); h.sink.clear()
        h.service.reset(s); h.service.tick()
        assertEquals(2, h.sink.forPlayer(a).sumOf { it.count })
        assertEquals(before, s.kindAt(5, 5, 5))
        assertEquals(0, s.brokenCount)
    }

    @Test fun reshuffleChangesLayoutAndResendsPacedWithinBudget() {
        val mine = testMine(reset = ResetPolicy(ResetMode.RESHUFFLE, 0.0))
        val h = Harness(mine, ServiceConfig(resendChunksPerTick = 1))
        val a = h.join(); val s = h.service.session(a, mine)
        val oldKinds = (0 until 200).map { s.kindAt(it % 32, it % 16, (it * 7) % 32) }
        h.dig(a, 0, 0, 0); h.service.tick(); h.sink.clear()

        h.service.reset(s)
        val newKinds = (0 until 200).map { s.kindAt(it % 32, it % 16, (it * 7) % 32) }
        assertNotEquals(oldKinds, newKinds)

        var ticks = 0
        val chunks = HashSet<Long>()
        while (ticks < 10) {
            h.sink.clear(); h.service.tick(); ticks++
            val ups = h.sink.forPlayer(a)
            if (ups.isEmpty()) break
            assertTrue(ups.map { ChunkKey.of(it.chunkX, it.chunkZ) }.toSet().size <= 1, "at most one chunk per tick")
            ups.forEach { chunks.add(ChunkKey.of(it.chunkX, it.chunkZ)) }
        }
        assertEquals(4, chunks.size, "all four chunk columns re-sent")
        assertEquals(mine.volume, s.let { var n = 0; for (i in 0 until mine.volume) if (s.kindAt(mine.xOf(i), mine.yOf(i), mine.zOf(i)) != AIR) n++; n })
    }

    @Test fun autoResetAtThreshold() {
        val mine = testMine(mine = Cuboid(0, 0, 0, 3, 3, 3), reset = ResetPolicy(ResetMode.RESTORE, 0.5))
        val h = Harness(mine, ServiceConfig(limits = BreakLimits(maxBreaksPerTick = 1000)))
        val a = h.join()
        for (i in 0 until 32) h.dig(a, i % 4, (i / 4) % 4, i / 16)
        assertEquals(1, h.hooks.resets)
        assertTrue(h.service.session(a, mine).brokenCount < 32)
    }

    @Test fun timedReset() {
        val mine = testMine(reset = ResetPolicy(ResetMode.RESTORE, 0.0, intervalTicks = 40))
        val h = Harness(mine)
        val a = h.join(); h.dig(a, 1, 1, 1)
        repeat(39) { h.service.tick() }
        assertEquals(0, h.hooks.resets)
        repeat(2) { h.service.tick() }
        assertEquals(1, h.hooks.resets)
    }

    // ---- self-healing / foreign updates -----------------------------------------------------------

    @Test fun reconcileAdoptsChunksTheClientAlreadyHas() {
        val h = Harness()
        val id = java.util.UUID.randomUUID()
        h.service.player(id); h.service.onPlayerWorld(id, "w")   // no snapshots seen
        h.service.reconcile(id) { cx, cz -> cx == 0 && cz == 0 }
        h.service.tick()
        val chunks = h.sink.forPlayer(id).map { ChunkKey.of(it.chunkX, it.chunkZ) }.toSet()
        assertEquals(setOf(ChunkKey.of(0, 0)), chunks)
    }

    @Test fun worldChangeForgetsLoadedChunks() {
        val h = Harness()
        val a = h.join()
        h.service.onPlayerWorld(a, "other")
        h.dig(a, 1, 1, 1); h.service.tick()
        assertTrue(h.sink.forPlayer(a).isEmpty())
        assertTrue(h.service.snapshotChunk(a, 0, 0).isEmpty(), "no mine in that world")
    }

    @Test fun invalidateResendsAuthoritativeState() {
        val h = Harness()
        val a = h.join(); val s = h.service.session(a, h.mine)
        s.invalidate(2, 2, 2); s.invalidateSection(1, 0, 1); h.service.tick()
        val ups = h.sink.forPlayer(a)
        assertEquals(2, ups.size)
        assertEquals(1 + 16 * 16 * 16, ups.sumOf { it.count })
        for (u in ups) for (i in 0 until u.count) assertEquals(s.kindAt(u.x(i), u.y(i), u.z(i)), u.kinds[i])
    }

    // ---- scale smoke ---------------------------------------------------------------------------------

    @Test fun manyPlayersOnOneMineStaySeparateAndCheap() {
        val mine = testMine(mine = Cuboid(0, 0, 0, 63, 31, 63))
        val h = Harness(mine, ServiceConfig(limits = BreakLimits(maxBreaksPerTick = 100)))
        val ids = List(500) { h.join() }
        val r = Random(7)
        val expected = HashMap<java.util.UUID, MutableSet<Int>>()
        val t0 = System.nanoTime()
        repeat(20) {
            for (id in ids) {
                val x = r.nextInt(64); val y = r.nextInt(32); val z = r.nextInt(64)
                h.dig(id, x, y, z); expected.getOrPut(id) { HashSet() }.add(mine.indexOf(x, y, z))
            }
            h.service.tick()
        }
        val ms = (System.nanoTime() - t0) / 1e6
        for (id in ids) {
            val s = h.service.session(id, mine)
            assertEquals(expected[id]!!.size, s.brokenCount)
            for (i in expected[id]!!) assertEquals(AIR, s.kindAt(mine.xOf(i), mine.yOf(i), mine.zOf(i)))
        }
        println("500 players x 20 ticks x 1 break: ${"%.1f".format(ms)} ms total")
    }
}
