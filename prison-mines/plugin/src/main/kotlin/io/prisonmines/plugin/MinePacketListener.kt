package io.prisonmines.plugin

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.event.UserLoginEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk
import io.prisonmines.core.DigAction
import io.prisonmines.core.MineService
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The interception layer. Runs on netty threads, so it only:
 *  - classifies packets with O(1) lookups into the immutable [io.prisonmines.core.MineIndex],
 *  - cancels the ones that belong to a mine and hands them to the main thread via [inbound],
 *  - appends the player's virtual blocks right after a chunk packet that overlaps a mine.
 * Everything else (99.99% of traffic) is returned from after one map lookup.
 */
class MinePacketListener(
    private val service: MineService,
    private val sink: PacketSink,
    private val states: BlockStates,
    private val inbound: ConcurrentLinkedQueue<Inbound>,
) : PacketListenerAbstract(PacketListenerPriority.NORMAL) {

    override fun onUserLogin(event: UserLoginEvent) {
        val uuid = event.user.uuid ?: return
        sink.users[uuid] = event.user
        service.player(uuid)
    }

    override fun onUserDisconnect(event: UserDisconnectEvent) {
        val uuid = event.user.uuid ?: return
        sink.users.remove(uuid)
        service.removePlayer(uuid)
    }

    // ---- client -> server --------------------------------------------------------------------------

    override fun onPacketReceive(event: PacketReceiveEvent) {
        val type = event.packetType
        if (type != PacketType.Play.Client.PLAYER_DIGGING && type != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return
        val uuid = event.user.uuid ?: return
        val world = service.playerIfPresent(uuid)?.world ?: return
        val index = service.index
        if (!index.hasMines(world)) return
        val sequenced = event.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_19)

        if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            val w = WrapperPlayClientPlayerDigging(event)
            val action = when (w.action) {
                DiggingAction.START_DIGGING -> DigAction.START
                DiggingAction.CANCELLED_DIGGING -> DigAction.ABORT
                DiggingAction.FINISHED_DIGGING -> DigAction.FINISH
                else -> return // drop item, swap hands, release use item...
            }
            val p = w.blockPosition
            val mine = index.mineAt(world, p.x, p.y, p.z) ?: return
            event.isCancelled = true // vanilla must never see it: its world is air here
            inbound.add(Inbound.Dig(uuid, mine, action, p.x, p.y, p.z, if (sequenced) w.sequence else -1))
        } else {
            val w = WrapperPlayClientPlayerBlockPlacement(event)
            val p = w.blockPosition
            val face = w.face
            val tx = p.x + (face?.modX ?: 0)
            val ty = p.y + (face?.modY ?: 0)
            val tz = p.z + (face?.modZ ?: 0)
            // Clicking a virtual block, or placing into the mine cuboid, is never allowed.
            val mine = index.mineAt(world, p.x, p.y, p.z) ?: index.mineAt(world, tx, ty, tz) ?: return
            event.isCancelled = true
            inbound.add(Inbound.Place(uuid, mine, p.x, p.y, p.z, tx, ty, tz, if (sequenced) w.sequence else -1))
        }
    }

    // ---- server -> client --------------------------------------------------------------------------

    override fun onPacketSend(event: PacketSendEvent) {
        val type = event.packetType
        if (type != PacketType.Play.Server.CHUNK_DATA &&
            type != PacketType.Play.Server.UNLOAD_CHUNK &&
            type != PacketType.Play.Server.BLOCK_CHANGE &&
            type != PacketType.Play.Server.MULTI_BLOCK_CHANGE
        ) return
        val uuid = event.user.uuid ?: return
        val state = service.playerIfPresent(uuid) ?: return
        val world = state.world ?: return
        val index = service.index
        if (!index.hasMines(world)) return

        when (type) {
            PacketType.Play.Server.CHUNK_DATA -> {
                val col = WrapperPlayServerChunkData(event).column
                val cx = col.x
                val cz = col.z
                if (index.minesInChunk(world, cx, cz).isEmpty()) return
                // Runs after the chunk is written, so the client has the chunk when our blocks arrive.
                event.tasksAfterSend.add(Runnable {
                    for ((session, update) in service.snapshotChunk(uuid, cx, cz)) sink.send(event.user, session.mine, update)
                })
            }

            PacketType.Play.Server.UNLOAD_CHUNK -> {
                val w = WrapperPlayServerUnloadChunk(event)
                service.unloadChunk(uuid, w.chunkX, w.chunkZ)
            }

            // Another plugin / the server touches a block inside a mine: make sure the client keeps
            // seeing this player's version instead.
            PacketType.Play.Server.BLOCK_CHANGE -> {
                val w = WrapperPlayServerBlockChange(event)
                val p = w.blockPosition
                val mine = index.mineAt(world, p.x, p.y, p.z) ?: return
                val session = state.session(mine.id) ?: return
                w.blockId = states.globalId(mine, session.kindAt(p.x, p.y, p.z))
                event.markForReEncode(true)
            }

            PacketType.Play.Server.MULTI_BLOCK_CHANGE -> {
                val sec = WrapperPlayServerMultiBlockChange(event).chunkPosition
                for (mine in index.minesInChunk(world, sec.x, sec.z)) {
                    state.session(mine.id)?.invalidateSection(sec.x, sec.y, sec.z)
                }
            }
        }
    }
}
