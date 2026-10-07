package io.prisonmines.plugin

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange.EncodedBlock
import io.prisonmines.core.MineDefinition
import io.prisonmines.core.MineSession
import io.prisonmines.core.SectionUpdate
import io.prisonmines.core.ViewSink
import io.prisonmines.core.enchant.BreakOutcome
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * All outbound mine traffic. Uses `sendPacketSilently` so our own packets never re-enter our
 * send listener (no feedback loop, no double work).
 */
class PacketSink(private val states: BlockStates) : ViewSink {
    val users = ConcurrentHashMap<UUID, User>()

    override fun sendSection(session: MineSession, update: SectionUpdate) {
        val user = users[session.owner] ?: return
        send(user, session.mine, update)
    }

    fun send(user: User, mine: MineDefinition, u: SectionUpdate) {
        if (u.count == 0) return
        if (u.count == 1) {
            user.sendPacketSilently(
                WrapperPlayServerBlockChange(Vector3i(u.x(0), u.y(0), u.z(0)), states.globalId(mine, u.kinds[0]))
            )
            return
        }
        val blocks = Array(u.count) { i -> EncodedBlock(states.globalId(mine, u.kinds[i]), u.x(i), u.y(i), u.z(i)) }
        user.sendPacketSilently(WrapperPlayServerMultiBlockChange(Vector3i(u.chunkX, u.sectionY, u.chunkZ), true, *blocks))
    }

    override fun sendAck(player: UUID, sequence: Int) {
        val user = users[player] ?: return
        // Sequenced block predictions only exist on 1.19+ clients.
        if (user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_19)) {
            user.sendPacketSilently(WrapperPlayServerAcknowledgeBlockChanges(sequence))
        }
    }

    /**
     * Break particles + sound for blocks removed by enchants, for the breaker only. (The client
     * already plays the effect for the block it mined itself.) Capped and evenly sampled.
     */
    fun sendBreakEffects(player: UUID, outcome: BreakOutcome, cap: Int) {
        val user = users[player] ?: return
        val mine = outcome.session.mine
        val extra = outcome.count - 1
        if (extra <= 0 || cap <= 0) return
        val step = maxOf(1, extra / cap)
        var i = 1
        while (i < outcome.indices.size) {
            val idx = outcome.indices[i]
            val kind = outcome.kinds[i]
            user.sendPacketSilently(
                WrapperPlayServerEffect(
                    EFFECT_BLOCK_BREAK,
                    Vector3i(mine.xOf(idx), mine.yOf(idx), mine.zOf(idx)),
                    states.globalId(mine, kind),
                    false,
                )
            )
            i += step
        }
    }

    private companion object {
        const val EFFECT_BLOCK_BREAK = 2001
    }
}
