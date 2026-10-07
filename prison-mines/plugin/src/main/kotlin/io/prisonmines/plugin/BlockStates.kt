package io.prisonmines.plugin

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import io.prisonmines.core.AIR
import io.prisonmines.core.MineDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * palette index -> PacketEvents global block-state id, resolved once per mine.
 * Ids are in the server's version space; PacketEvents/ViaVersion take care of the client.
 */
class BlockStates {
    private val airId: Int by lazy { WrappedBlockState.getByString("minecraft:air").globalId }
    private val cache = ConcurrentHashMap<String, IntArray>()

    fun idsFor(mine: MineDefinition): IntArray = cache.computeIfAbsent(mine.id) {
        IntArray(mine.kinds.size) { WrappedBlockState.getByString(mine.kinds[it].key).globalId }
    }

    fun globalId(mine: MineDefinition, kind: Int): Int = if (kind == AIR) airId else idsFor(mine)[kind]

    fun invalidate() = cache.clear()
}
