package io.prisonmines.plugin

import io.prisonmines.core.DigAction
import io.prisonmines.core.MineDefinition
import java.util.UUID

/** Packets cancelled on the netty thread, replayed on the main thread. */
sealed interface Inbound {
    val player: UUID
    val mine: MineDefinition

    class Dig(
        override val player: UUID, override val mine: MineDefinition, val action: DigAction,
        val x: Int, val y: Int, val z: Int, val sequence: Int,
    ) : Inbound

    class Place(
        override val player: UUID, override val mine: MineDefinition,
        val x: Int, val y: Int, val z: Int, val tx: Int, val ty: Int, val tz: Int, val sequence: Int,
    ) : Inbound
}
