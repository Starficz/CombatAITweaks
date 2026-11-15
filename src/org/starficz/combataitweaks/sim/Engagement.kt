package org.starficz.combataitweaks.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class WeaponSize { SMALL, MEDIUM, LARGE }
enum class WeaponKind { BALLISTIC, ENERGY, MISSILE, OTHER }

/** One weapon as the engagement and facing rules see it. Captured from WeaponAPI on the main thread. */
class WeaponInfo(
    /** Index of its weapon group; weapons in no group use -1. */
    val group: Int,
    val size: WeaponSize,
    val kind: WeaponKind,
    val isHardpoint: Boolean,
    /** Slot centre angle relative to ship facing (ship facing right = 0), degrees. */
    val slotAngle: Float,
    val arc: Float,
    val range: Float,
    /** Mount position along the ship's forward axis (ship-local x). */
    val slotForward: Float,
    /** Has ammo left (or doesn't use ammo) and can be fired by the AI (not NO_MANUAL_FIRE). */
    val usable: Boolean,
    /** Missile with ammo for at most 5 bursts left and no DO_NOT_CONSERVE hint. */
    val lowAmmoMissile: Boolean,
    val pd: Boolean,
    val pdOnly: Boolean,
    val dps: Float,
)

/**
 * The range a ship tries to hold from its target, and which way it faces, following vanilla.
 *
 * Range: the attack module's weapon-group scan (decompiled AttackAIModule) feeding BasicShipAI's maneuver range.
 */
object Engagement {

    /** Vanilla's per-weapon weight in a group's score. */
    fun weight(w: WeaponInfo): Float {
        var weight = when (w.size) {
            WeaponSize.SMALL -> 1f
            WeaponSize.MEDIUM -> 3f
            WeaponSize.LARGE -> 7f
        }
        if (w.isHardpoint) weight *= 2f
        if (w.kind == WeaponKind.BALLISTIC) weight += 1f
        if (w.kind == WeaponKind.MISSILE) weight += 2f
        if (w.pdOnly) weight *= 0.1f
        if (w.pd) weight *= 0.25f
        if (!coversForward(w)) {
            val offForward = abs(nearestArcAngle(w.slotAngle, w.arc, 0f).let { shortestRotation(it, 0f) })
            weight *= (if (w.pd) 0.5f else 1f) * (180f - offForward) / 180f
        }
        return weight
    }

    private fun coversForward(w: WeaponInfo) = abs(shortestRotation(w.slotAngle, 0f)) <= w.arc / 2f

    /** The angle inside the arc (centre [center], width [arc]) closest to [target]. */
    private fun nearestArcAngle(center: Float, arc: Float, target: Float): Float {
        val d = shortestRotation(center, target)
        val half = arc / 2f
        return center + d.coerceIn(-half, half)
    }

    /**
     * Range a weapon contributes to its group: its range, less how far behind the ship's front it is mounted when it
     * faces forward (vanilla: 0.6 x collision radius - mount x, floored at 0), never under 100.
     */
    private fun groupRangeOf(w: WeaponInfo, collisionRadius: Float): Float {
        val forwardOffset = if (coversForward(w)) max(0f, collisionRadius * 0.6f - w.slotForward) else 0f
        return max(100f, w.range - forwardOffset)
    }

    /**
     * Base engagement range before radii: min(primary group range, best non-missile group range).
     * The primary group is the highest-scoring one (ties go to the shorter range); its range is its shortest weapon.
     * Vanilla falls back to 2000 when no group without missiles exists.
     */
    fun baseRange(weapons: List<WeaponInfo>, collisionRadius: Float): Float {
        var bestScore = 0f
        var primaryRange = Float.MAX_VALUE
        var bestGunScore = 0f
        var gunRange = Float.MAX_VALUE
        for ((_, group) in weapons.filter { it.usable && it.group >= 0 }.groupBy { it.group }) {
            var score = 0f
            var range = 10000f
            var missilesOnly = true
            for (w in group) {
                var weight = weight(w)
                if (w.kind == WeaponKind.MISSILE && w.lowAmmoMissile) weight -= weight * 2f
                score += weight
                if (w.kind != WeaponKind.MISSILE) missilesOnly = false
                range = min(range, groupRangeOf(w, collisionRadius))
            }
            if (score > bestScore || (score == bestScore && range < primaryRange)) {
                bestScore = score
                primaryRange = range
            }
            if (!missilesOnly && score > bestGunScore) {
                bestGunScore = score
                gunRange = range
            }
        }
        if (gunRange == Float.MAX_VALUE) gunRange = 2000f
        if (primaryRange == Float.MAX_VALUE) primaryRange = gunRange
        return min(primaryRange, gunRange)
    }

    /** Longest range of a non-missile weapon that can fire at ships; [weaponReach] when it has only missiles. */
    fun gunReach(weapons: List<WeaponInfo>): Float =
        weapons.filter { it.usable && !it.pdOnly && it.kind != WeaponKind.MISSILE }.maxOfOrNull { it.range }
            ?: weaponReach(weapons)

    /** Longest range of any weapon that can fire at ships. */
    fun weaponReach(weapons: List<WeaponInfo>): Float =
        weapons.filter { it.usable && !it.pdOnly }.maxOfOrNull { it.range } ?: 0f

    /**
     * Facing offset from the bearing to the target that brings the most DPS to bear, from -180 to 170 in 10 degree
     * steps. A weapon covers the target when the target's angle in ship space (-offset) is within its arc; hardpoints
     * count as at least +-5 degrees. Each covering weapon scores its DPS plus up to 10% for how far inside its arc
     * the target sits, so the middle of a broadside wins over its edge; remaining ties go to the smallest offset.
     */
    fun bestFacingOffset(weapons: List<WeaponInfo>): Float {
        var best = 0f
        var bestScore = -1f
        for (step in -18 until 18) {
            val offset = step * 10f
            val targetAngle = -offset
            var score = 0f
            for (w in weapons) {
                if (!w.usable || w.pdOnly) continue
                val half = max(w.arc / 2f, if (w.isHardpoint) 5f else 0f)
                val off = abs(shortestRotation(w.slotAngle, targetAngle))
                if (off <= half) {
                    val centred = if (half > 0f) 1f - off / half else 1f
                    score += w.dps * (if (w.pd) 0.25f else 1f) * (1f + 0.1f * centred)
                }
            }
            if (score > bestScore + 1e-3f || (abs(score - bestScore) <= 1e-3f && abs(offset) < abs(best))) {
                bestScore = score
                best = offset
            }
        }
        return best
    }
}
