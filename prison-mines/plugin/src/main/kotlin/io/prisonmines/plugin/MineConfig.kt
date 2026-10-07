package io.prisonmines.plugin

import io.prisonmines.core.BlockKind
import io.prisonmines.core.BreakLimits
import io.prisonmines.core.Cuboid
import io.prisonmines.core.MineDefinition
import io.prisonmines.core.MineIndex
import io.prisonmines.core.ResetMode
import io.prisonmines.core.ResetPolicy
import io.prisonmines.core.ServiceConfig
import io.prisonmines.core.SpawnPoint
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration

data class Settings(
    val reach: Double,
    val breakEffectCap: Int,
    val flightGuard: Boolean,
    val blockMobSpawns: Boolean,
    val pvpInSafeArea: Boolean,
    val teleportOnReset: Boolean,
    val resetMessage: String,
    val service: ServiceConfig,
)

object MineConfig {
    fun settings(cfg: FileConfiguration): Settings {
        val s = cfg.getConfigurationSection("settings")
        fun d(k: String, def: Double) = s?.getDouble(k, def) ?: def
        fun i(k: String, def: Int) = s?.getInt(k, def) ?: def
        fun b(k: String, def: Boolean) = s?.getBoolean(k, def) ?: def
        return Settings(
            reach = d("reach", 6.0),
            breakEffectCap = i("break-effect-cap", 6),
            flightGuard = b("flight-guard", true),
            blockMobSpawns = b("block-mob-spawns", true),
            pvpInSafeArea = b("pvp-in-safe-area", false),
            teleportOnReset = b("teleport-on-reset", true),
            resetMessage = s?.getString("messages.reset", "") ?: "",
            service = ServiceConfig(
                resendChunksPerTick = i("resend-chunks-per-tick", 8),
                limits = BreakLimits(i("max-blocks-per-break", 2048), i("max-breaks-per-tick", 8)),
            ),
        )
    }

    /** @throws IllegalArgumentException with a readable message for a bad mine entry */
    fun mines(cfg: FileConfiguration): MineIndex {
        val root = cfg.getConfigurationSection("mines") ?: return MineIndex(emptyList())
        return MineIndex(root.getKeys(false).map { id -> parse(id, root.getConfigurationSection(id)!!) })
    }

    private fun parse(id: String, s: ConfigurationSection): MineDefinition {
        fun err(msg: String): Nothing = throw IllegalArgumentException("mine '$id': $msg")
        fun triple(sec: ConfigurationSection?, key: String): List<Int> =
            sec?.getIntegerList(key)?.takeIf { it.size == 3 } ?: err("'$key' must be [x, y, z]")

        val m = s.getConfigurationSection("mine") ?: err("missing 'mine'")
        val a = triple(m, "min"); val b = triple(m, "max")
        val mine = Cuboid.between(a[0], a[1], a[2], b[0], b[1], b[2])

        val safe = s.getConfigurationSection("safe")?.let {
            val c = triple(it, "min"); val d = triple(it, "max")
            Cuboid.between(c[0], c[1], c[2], d[0], d[1], d[2])
        } ?: mine.expand(s.getInt("safe-margin", 5))

        val spawnList = s.getDoubleList("spawn")
        val spawn = if (spawnList.size >= 3) {
            SpawnPoint(spawnList[0], spawnList[1], spawnList[2], spawnList.getOrNull(3)?.toFloat() ?: 0f, spawnList.getOrNull(4)?.toFloat() ?: 0f)
        } else SpawnPoint((mine.minX + mine.maxX) / 2.0 + 0.5, mine.maxY + 2.0, (mine.minZ + mine.maxZ) / 2.0 + 0.5)

        val r = s.getConfigurationSection("reset")
        val policy = ResetPolicy(
            mode = if (r?.getString("mode", "reshuffle").equals("restore", true)) ResetMode.RESTORE else ResetMode.RESHUFFLE,
            thresholdFraction = r?.getDouble("threshold", 0.75) ?: 0.75,
            intervalTicks = (r?.getLong("interval-seconds", 0) ?: 0) * 20,
        )

        val kinds = s.getMapList("blocks").map { raw ->
            val block = raw["block"]?.toString() ?: err("a block entry is missing 'block'")
            BlockKind(
                key = block,
                weight = (raw["weight"] as? Number)?.toInt() ?: 1,
                drop = raw["drop"]?.toString() ?: block,
                dropAmount = (raw["amount"] as? Number)?.toInt() ?: 1,
            )
        }
        if (kinds.isEmpty()) err("no 'blocks'")

        return MineDefinition(id, s.getString("world") ?: err("missing 'world'"), mine, safe, kinds, spawn, policy)
    }
}
