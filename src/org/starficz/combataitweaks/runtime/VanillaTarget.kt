package org.starficz.combataitweaks.runtime

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags
import org.starficz.combataitweaks.ReflectionUtils
import org.starficz.combataitweaks.ReflectionUtils.get
import org.starficz.combataitweaks.ReflectionUtils.getFieldsMatching

/**
 * The ship vanilla's ship AI attacks and maneuvers on. Main thread only.
 *
 * Vanilla keeps it in BasicShipAI's private field `target` (decompiled; the name isn't obfuscated). The API's
 * signals are weaker:
 * - the MANEUVER_TARGET flag is only set for 1 s as a target maneuver changes (by the maneuver sequence);
 * - the ship target follows the AI's manually aimed weapon group.
 *
 * Falls back to MANEUVER_TARGET for AIs without the field (other mods' ship AIs).
 */
object VanillaTarget {
    private val fields = HashMap<Class<*>, ReflectionUtils.ReflectedField?>()

    fun of(ship: ShipAPI): ShipAPI? {
        val ai = ship.shipAI ?: return null
        val field = fields.getOrPut(ai.javaClass) {
            runCatching {
                ai.getFieldsMatching("target", fieldAssignableTo = ShipAPI::class.java, searchSuperclass = true).singleOrNull()
            }.getOrNull()
        }
        if (field != null) {
            (runCatching { ai.get(field) }.getOrNull() as? ShipAPI)?.let { return it }
        }
        return ai.aiFlags?.getCustom(AIFlags.MANEUVER_TARGET) as? ShipAPI
    }
}
