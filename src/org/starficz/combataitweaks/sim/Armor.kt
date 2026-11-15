package org.starficz.combataitweaks.sim

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A mutable copy of one ship's armor grid, damaged with vanilla's rule (plan §6.5; translation of the obfuscated
 * class implementing ArmorGridAPI):
 *
 * 1. Damage and hit strength are multiplied by m = type armor mult x armorDamageTakenMult (x the kinetic armor mult
 *    for kinetic). Beams use DPS x dpsToHitStrengthMult as hit strength.
 * 2. Armor at the hit = the 21 cells around it (5x5 without corners), the centre and inner ring at weight 1 and the
 *    outer 12 at 0.5; then the effective armor bonus; floored at rating x minArmorFraction.
 * 3. Damage x (1 - min(armor / (armor + hit strength), max reduction)) is spread 1/15 to each of the inner 9 cells
 *    and 1/30 to each of the outer 12. What a cell can't absorb (and anything landing off the grid) goes to hull
 *    x type hull mult / m x hullDamageTakenMult.
 */
class ArmorGrid(val info: ArmorInfo, private val rules: DamageRules, scratch: Scratch? = null) {
    val width = info.width
    val height = info.height
    /** A copy of the captured cells (from [scratch] when given: valid for that run only). */
    val cells: FloatArray = scratch?.floats(info.cells.size)?.also { info.cells.copyInto(it) } ?: info.cells.copyOf()

    /** Damage taken by the last [hit], in armor-grid points (before the hull overflow). */
    var lastArmorDamage = 0f
        private set

    fun index(i: Int, j: Int) = i * height + j

    /** Weighted armor around (i, j) as vanilla sums it: about the full armor rating for an undamaged ship. */
    fun armorAround(i: Int, j: Int, grid: FloatArray = cells): Float {
        var sum = 0f
        for (di in -2..2) for (dj in -2..2) {
            val corner = (di == 2 || di == -2) && (dj == 2 || dj == -2)
            if (corner) continue
            val ci = i + di; val cj = j + dj
            if (ci < 0 || ci >= width || cj < 0 || cj >= height) continue
            val weight = if (di in -1..1 && dj in -1..1) 1f else 0.5f
            sum += grid[ci * height + cj] * weight
        }
        return sum
    }

    /**
     * Applies one hit centred on cell (i, j). [damage] and [hitStrength] already carry the target's damage-taken
     * mults. Returns the hull damage.
     */
    fun hit(i: Int, j: Int, damage: Float, hitStrength: Float, kind: DamageKind, beam: Boolean): Float {
        lastArmorDamage = 0f
        if (damage <= 0f) return 0f
        var m = rules.armorMult[kind.ordinal] * info.armorDamageTakenMult
        if (kind == DamageKind.KINETIC) m *= info.kineticArmorDamageTakenMult
        val dmg = damage * m
        var strength = hitStrength * m
        if (beam) strength *= rules.dpsToHitStrengthMult
        if (strength <= 0f) return 0f
        strength = max(strength, 1f)

        var armor = info.effectiveArmorBonus.apply(armorAround(i, j))
        armor = max(armor, info.rating * info.minArmorFraction)
        armor = max(armor, 0f)
        val reduction = min(armor / (armor + strength), info.maxArmorDamageReduction)
        val dealt = dmg * (1f - reduction)

        val hullMult = if (m > 0f) rules.hullMult[kind.ordinal] / m * info.hullDamageTakenMult else 0f
        var hull = 0f
        var absorbed = 0f
        for (di in -2..2) for (dj in -2..2) {
            val corner = (di == 2 || di == -2) && (dj == 2 || dj == -2)
            if (corner) continue
            val share = if (di in -1..1 && dj in -1..1) dealt / 15f else dealt / 30f
            val ci = i + di; val cj = j + dj
            if (ci < 0 || ci >= width || cj < 0 || cj >= height) {
                hull += share * hullMult
                continue
            }
            val k = ci * height + cj
            val cell = cells[k]
            val overflow = share - cell
            cells[k] = max(cell - share, 0f)
            absorbed += min(share, cell)
            if (overflow > 0f) hull += overflow * hullMult
        }
        lastArmorDamage = absorbed
        return hull
    }

    /** The cell containing a ship-local point, or null when it's off the grid. */
    fun cellAt(localX: Float, localY: Float): Int? = cellAt(info, localX, localY)

    companion object {
        /** The cell (as i * height + j) containing ship-local ([localX], [localY]) on [info]'s grid, or null. */
        fun cellAt(info: ArmorInfo, localX: Float, localY: Float): Int? {
            // solve p = origin + a * axisI + b * axisJ for (a, b)
            val px = localX - info.originX
            val py = localY - info.originY
            val det = info.axisIX * info.axisJY - info.axisIY * info.axisJX
            if (det == 0f) return null
            val a = (px * info.axisJY - py * info.axisJX) / det
            val b = (info.axisIX * py - info.axisIY * px) / det
            val i = floor(a).toInt()
            val j = floor(b).toInt()
            if (i < 0 || i >= info.width || j < 0 || j >= info.height) return null
            return i * info.height + j
        }
    }
}
