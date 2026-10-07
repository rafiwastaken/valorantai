package io.prisonmines.plugin

import io.prisonmines.core.enchant.EnchantApplication
import io.prisonmines.core.enchant.EnchantContext
import io.prisonmines.core.enchant.MineEnchant
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * Enchants live on the pickaxe's PersistentDataContainer (`prisonmines:ench_<id>` = level) and are
 * registered here. Third-party plugins can register their own [MineEnchant]s: whatever they do, they
 * only get the clipped [EnchantContext] API, so they cannot touch the real world or leave the mine.
 */
class EnchantRegistry(private val plugin: Plugin) {
    private val enchants = LinkedHashMap<String, MineEnchant>()
    private val keys = HashMap<String, NamespacedKey>()

    fun register(e: MineEnchant) {
        enchants[e.id] = e
        keys[e.id] = NamespacedKey(plugin, "ench_${e.id}")
    }

    fun ids(): Set<String> = enchants.keys

    fun levelOn(item: ItemStack?, id: String): Int {
        val key = keys[id] ?: return 0
        val meta = item?.itemMeta ?: return 0
        return meta.persistentDataContainer.get(key, PersistentDataType.INTEGER) ?: 0
    }

    fun setLevel(item: ItemStack, id: String, level: Int): Boolean {
        val key = keys[id] ?: return false
        val meta = item.itemMeta ?: return false
        if (level <= 0) meta.persistentDataContainer.remove(key) else meta.persistentDataContainer.set(key, PersistentDataType.INTEGER, level)
        item.itemMeta = meta
        return true
    }

    /** Enchants on the held item, in registration order (fortune-style multipliers should register last or first as desired). */
    fun enchantsFor(player: Player): List<EnchantApplication> {
        val item = player.inventory.itemInMainHand
        val meta = item.itemMeta ?: return emptyList()
        val pdc = meta.persistentDataContainer
        var out: ArrayList<EnchantApplication>? = null
        for ((id, e) in enchants) {
            val level = pdc.get(keys.getValue(id), PersistentDataType.INTEGER) ?: continue
            if (level <= 0) continue
            (out ?: ArrayList<EnchantApplication>().also { out = it }).add(EnchantApplication(e, level))
        }
        return out ?: emptyList()
    }
}

/** Chance to blow a sphere out of the mine around the broken block. */
class ExplosiveEnchant(
    private val chancePerLevel: Double = 0.002,
    private val maxRadius: Int = 5,
    private val levelsPerRadius: Int = 250,
) : MineEnchant {
    override val id = "explosive"
    override fun apply(ctx: EnchantContext, level: Int) {
        if (ctx.random.nextDouble() >= minOf(1.0, level * chancePerLevel)) return
        ctx.addSphere(minOf(maxRadius, 1 + level / levelsPerRadius))
    }
}

/** Chance to clear the whole horizontal layer of the mine at the broken block's height. */
class LayerEnchant(private val chancePerLevel: Double = 0.0005) : MineEnchant {
    override val id = "layer"
    override fun apply(ctx: EnchantContext, level: Int) {
        if (ctx.random.nextDouble() < minOf(1.0, level * chancePerLevel)) ctx.addLayer()
    }
}

/** More rewards from the same blocks (no extra blocks removed). */
class FortuneEnchant(private val bonusPerLevel: Double = 0.02) : MineEnchant {
    override val id = "fortune"
    override fun apply(ctx: EnchantContext, level: Int) {
        ctx.rewardMultiplier *= 1.0 + level * bonusPerLevel
    }
}
