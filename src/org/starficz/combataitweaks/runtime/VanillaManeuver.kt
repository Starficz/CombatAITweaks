package org.starficz.combataitweaks.runtime

import com.fs.starfarer.api.combat.ShipAPI
import org.starficz.combataitweaks.ReflectionUtils
import org.starficz.combataitweaks.ReflectionUtils.getConstructorsMatching
import org.starficz.combataitweaks.ReflectionUtils.getFieldsMatching
import org.starficz.combataitweaks.ReflectionUtils.invoke
import org.starficz.combataitweaks.ReflectionUtils.set

/**
 * Hands vanilla's ship AI the maneuver CAT wants: strafing a target at a given range. Main thread only.
 *
 * Why: the target override (ShipAIPlugin.setTargetOverride) only counts when vanilla picks its own maneuver. With
 * an order whose response comes with a maneuver (escorting the flagship, a waypoint) BasicShipAI.pickManeuver runs
 * that maneuver and the override never applies. Setting the maneuver itself works whatever the order says.
 *
 * Translation of obfuscated code:
 * - pickManeuver builds `StrafeTargetManeuverV2(ship, target, badTarget, 0, range, flockingAI, ai, true)` and calls
 *   `setManeuver(maneuver, seconds)`. The range is surface to surface (the maneuver adds both radii), and
 *   BACK_OFF_MIN_RANGE / BACK_OFF_MAX_RANGE still clamp it.
 * - The AI's `sequence` runs one maneuver at a time; when it runs out (or vanilla sets another, e.g. a threat
 *   response) pickManeuver picks again, so [isCurrent] tells the caller when to hand it over again.
 *
 * Neither class name nor `sequence` is obfuscated; the constructor is found by shape (8 parameters), its flocking
 * and owner arguments come from the AI itself, and the sequence's current maneuver is found by type. Anything
 * missing (another mod's ship AI, a game update) and [strafe] returns null, so callers keep their fallback.
 */
object VanillaManeuver {
    private const val STRAFE = "com.fs.starfarer.combat.ai.movement.maneuvers.StrafeTargetManeuverV2"
    private const val BASIC_AI = "com.fs.starfarer.combat.ai.BasicShipAI"

    private var broken = false
    private var constructor: ReflectionUtils.ReflectedConstructor? = null
    private var strafeClass: Class<*>? = null

    // the sequence's current entry, and the maneuver inside it (found by type once)
    private var sequenceField: ReflectionUtils.ReflectedField? = null
    private var entryField: ReflectionUtils.ReflectedField? = null
    private var maneuverField: ReflectionUtils.ReflectedField? = null
    private var lookupFailed = false

    /**
     * Gives [ship]'s AI a strafe maneuver on [target] at [range] (surface to surface) for about [seconds]. Returns
     * the maneuver (for [isCurrent]), or null when vanilla's AI can't take one.
     */
    fun strafe(ship: ShipAPI, target: ShipAPI, range: Float, seconds: Float): Any? {
        if (broken) return null
        val ai = ship.shipAI ?: return null
        if (ai.javaClass.name != BASIC_AI) return null
        return runCatching {
            val flocking = ai.invoke("getFlockingAI") ?: return null
            val ctor = constructor ?: Class.forName(STRAFE, false, ai.javaClass.classLoader)
                .also { strafeClass = it }
                .getConstructorsMatching(numOfParams = 8, parameterTypes = arrayOf(
                    ship.javaClass, target.javaClass, java.lang.Boolean.TYPE, java.lang.Float.TYPE,
                    java.lang.Float.TYPE, flocking.javaClass, ai.javaClass, java.lang.Boolean.TYPE,
                )).single().also { constructor = it }
            val maneuver = ctor.newInstance(ship, target, false, 0f, range.coerceAtLeast(0f), flocking, ai, true)
            ai.invoke("setManeuver", maneuver, seconds)
            // the rest of vanilla's AI (attack mode, threat checks) reads its target field: point it at the same ship
            runCatching { ai.set("target", target, searchSuperclass = true) }
            maneuver
        }.getOrElse {
            broken = true
            com.fs.starfarer.api.Global.getLogger(VanillaManeuver::class.java)
                .warn("CAT: can't hand vanilla a strafe maneuver, falling back to the target override", it)
            null
        }
    }

    /** Whether [maneuver] is the one [ship]'s AI is running now; null when that can't be read. */
    fun isCurrent(ship: ShipAPI, maneuver: Any): Boolean? {
        if (lookupFailed) return null
        val ai = ship.shipAI ?: return null
        return runCatching {
            val seqField = sequenceField ?: ai.javaClass.getFieldsMatching(name = "sequence").single()
                .also { sequenceField = it }
            val sequence = seqField.get(ai) ?: return false
            // the current entry: the sequence's field typed with its own nested class (the queue is a List)
            val eField = entryField ?: sequence.javaClass.getFieldsMatching()
                .single { it.type.enclosingClass == sequence.javaClass }.also { entryField = it }
            val entry = eField.get(sequence) ?: return false
            val strafe = strafeClass ?: return null
            val mField = maneuverField ?: entry.javaClass.getFieldsMatching()
                .single { !it.type.isPrimitive && it.type.isAssignableFrom(strafe) }.also { maneuverField = it }
            mField.get(entry) === maneuver
        }.getOrElse {
            lookupFailed = true
            com.fs.starfarer.api.Global.getLogger(VanillaManeuver::class.java)
                .warn("CAT: can't read vanilla's current maneuver, re-issuing on a timer", it)
            null
        }
    }
}
