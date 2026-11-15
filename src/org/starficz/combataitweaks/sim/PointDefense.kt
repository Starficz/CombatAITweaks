package org.starficz.combataitweaks.sim

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * One point-defense weapon as the missile sim sees it (plan §14, point defense): where it sits, what it covers, how
 * it aims and what it does to a missile.
 */
class PdWeapon(
    /** Mount in ship-local coordinates (ship facing +x). */
    val mountX: Float, val mountY: Float,
    /** Slot centre angle relative to the ship's facing, and the arc it covers, degrees. */
    val slotAngle: Float, val arc: Float,
    /** Turret turn rate, degrees per second. */
    val turnRate: Float,
    val range: Float,
    /** Projectile speed; [BEAM_SPEED] for beams. */
    val speed: Float,
    /** Full spread cone in degrees (max spread with recoil; hardpoints halved). */
    val spread: Float,
    /** Sustained damage per second against missiles (the ship's damage bonuses and damage-to-missiles applied). */
    val dps: Float,
    /**
     * Vanilla's lead inaccuracy k (1 = perfect lead): 2 - the ship's autofire aim accuracy, - the weapon's autofire
     * accuracy bonus, at least 1; 1 with "best target leading" (Integrated Point Defense AI).
     */
    val inaccuracy: Float,
) {
    companion object {
        const val BEAM_SPEED = 1e6f
    }
}

/** A ship's point defense, and its collision radius (vanilla's lead measures from the ship, not the mount). */
class PointDefense(val weapons: List<PdWeapon>, val shipRadius: Float)

/**
 * Point defense against missiles for one missile run (plan §14): every PD weapon of the target engages one missile at
 * a time, aimed the way vanilla's autofire aims, and the expected damage of its hits (after their flight time) is
 * taken off the missile's hitpoints. A missile whose hitpoints run out never arrives.
 *
 * How vanilla aims (translation of obfuscated code: `combat.ai.N` lead point, used by the autofire AI
 * `combat.ai.P`): an intercept on the missile's current velocity relative to the ship, from the ship's centre, with
 * the distance less 0.75 x both radii, but every speed multiplied by k ([PdWeapon.inaccuracy]): k = 2 (no skills)
 * leads by about half the true flight time. k drops by 1/15 per second the weapon has been firing at the same target
 * (at least 1). Acceleration and turning aren't led at all.
 *
 * What the sim adds (heuristic, deliberately conservative):
 * - the shot leaves the mount with the ship's velocity plus its speed toward vanilla's aim point; its miss distance is
 *   the closest approach to the missile's path with the missile's acceleration this step carried on;
 * - the hit chance is that miss against the spread at that distance (a uniform cone across the missile's width, its
 *   radius + [SHOT_RADIUS]), times how well the turret tracks the missile's angular rate (beams: tracking only);
 * - a weapon needs the missile in range and arc for [ACQUIRE] s before it fires (re-targeting every 0.25-0.5 s and
 *   turning onto it), and stays on it while it lives; PD_ALSO weapons and flares are left out.
 */
class PdFire(
    private val pd: PointDefense,
    private val timeline: Timeline,
    private val scratch: Scratch?,
) {
    private val steps = timeline.steps
    private val n = pd.weapons.size
    /**
     * Weapon k is busy at step i (with an earlier missile of this run): taken[k * steps + i] != 0. Made when the first
     * missile comes within reach.
     */
    private var taken = EMPTY

    /** A tracker for one missile with [hitpoints] and [radius]; null when its hitpoints aren't known. */
    fun track(hitpoints: Float, radius: Float): Track? = if (hitpoints <= 0f || n == 0) null else Track(hitpoints, radius)

    /** Farthest any PD weapon reaches from the ship's centre (range + mount offset): missiles beyond it are skipped. */
    private val reach = pd.weapons.maxOfOrNull { it.range + sqrt(it.mountX * it.mountX + it.mountY * it.mountY) } ?: 0f

    inner class Track(private var hp: Float, private val radius: Float) {
        // made when the missile first comes within reach: most missiles of most branches never do
        private var inRange = EMPTY
        private var firing = EMPTY
        private var pending = EMPTY
        private var near = false
        private val out = FloatArray(1)

        /** The hits landing in step [i]; true when the missile is destroyed by them. */
        fun destroyedAt(i: Int): Boolean {
            if (pending.isEmpty()) return false
            val d = pending[i]
            if (d <= 0f) return false
            hp -= d
            return hp <= 0f
        }

        /** Step [i]: the missile at (x, y) with velocity (vx, vy) and acceleration (ax, ay); free PD weapons shoot. */
        fun engage(i: Int, x: Float, y: Float, vx: Float, vy: Float, ax: Float, ay: Float) {
            val sx = timeline.x[i]; val sy = timeline.y[i]
            val r = reach + radius
            val cdx = x - sx; val cdy = y - sy
            if (cdx * cdx + cdy * cdy > r * r) {
                // out of every weapon's reach: whatever was acquiring loses it
                if (near) { java.util.Arrays.fill(inRange, 0f); java.util.Arrays.fill(firing, 0f); near = false }
                return
            }
            if (taken.isEmpty()) taken = scratch?.floats(n * steps) ?: FloatArray(n * steps)
            if (pending.isEmpty()) {
                inRange = scratch?.floats(n) ?: FloatArray(n)
                firing = scratch?.floats(n) ?: FloatArray(n)
                pending = scratch?.floats(steps) ?: FloatArray(steps)
            }
            near = true
            val ux = FireControl.velocityX(timeline, i); val uy = FireControl.velocityY(timeline, i)
            val facing = timeline.facing[i]
            val c = kotlin.math.cos(facing * DEG_TO_RAD); val s = kotlin.math.sin(facing * DEG_TO_RAD)
            for (k in 0 until n) {
                val w = pd.weapons[k]
                val slot = k * steps + i
                if (taken[slot] != 0f) { inRange[k] = 0f; firing[k] = 0f; continue }
                val mx = sx + w.mountX * c - w.mountY * s
                val my = sy + w.mountX * s + w.mountY * c
                val dx = x - mx; val dy = y - my
                val wr = w.range + radius
                // range first (cheap), the arc only in range
                if (dx * dx + dy * dy > wr * wr ||
                    (w.arc < 360f && abs(shortestRotation(facing + w.slotAngle, angleOf(dx, dy))) > w.arc / 2f)) {
                    inRange[k] = 0f; firing[k] = 0f; continue
                }
                inRange[k] += DT
                if (inRange[k] < ACQUIRE) continue
                taken[slot] = 1f
                firing[k] += DT
                val kNow = max(1f, w.inaccuracy - firing[k] / 15f)
                val p = hitChance(w, kNow, mx, my, sx, sy, ux, uy, pd.shipRadius, x, y, vx, vy, ax, ay, radius, out)
                if (p <= 0f) continue
                // lands no earlier than the next step (this step's hits are already counted)
                val land = i + max(1, ceil(out[0] / DT).toInt())
                if (land < steps) pending[land] += w.dps * DT * p
            }
        }
    }

    companion object {
        /** Seconds a missile must be in range and arc before a PD weapon fires at it. */
        const val ACQUIRE = 0.3f
        /** A PD projectile's own radius, added to the missile's. */
        const val SHOT_RADIUS = 5f
        private val EMPTY = FloatArray(0)

        /**
         * The chance one shot from [w] (fired from its mount (mx, my) on a ship at (sx, sy) moving at (ux, uy)) hits a
         * missile at (x, y) with velocity (vx, vy), acceleration (ax, ay) and [radius], aimed with lead inaccuracy
         * [k]. out[0] = seconds until it gets there.
         */
        fun hitChance(
            w: PdWeapon, k: Float, mx: Float, my: Float, sx: Float, sy: Float, ux: Float, uy: Float, shipRadius: Float,
            x: Float, y: Float, vx: Float, vy: Float, ax: Float, ay: Float, radius: Float, out: FloatArray,
        ): Float {
            val rvx = vx - ux; val rvy = vy - uy
            // turret tracking: the missile's angular rate seen from the mount
            val px = x - mx; val py = y - my
            val d2 = max(1f, px * px + py * py)
            val omega = abs(px * rvy - py * rvx) / d2 * RAD_TO_DEG
            val track = if (omega <= w.turnRate) 1f else w.turnRate / omega
            if (w.speed >= PdWeapon.BEAM_SPEED) { out[0] = 0f; return track }

            // vanilla's lead point, from the ship's centre (translation of obfuscated code, see the class comment)
            val cx = x - sx; val cy = y - sy
            val cd = max(1e-3f, sqrt(cx * cx + cy * cy))
            val d = max(0f, cd - (shipRadius + radius) * 0.75f)
            val closing = -(cx * rvx + cy * rvy) / cd
            var denom = k * (closing + w.speed)
            if (denom <= 10f) denom = 20f
            var t = d / denom
            t = ((d - k * closing * t) / denom).coerceIn(0f, 10f)
            val aimX = x + rvx * t; val aimY = y + rvy * t

            // the shot: the ship's velocity plus its speed toward the aim point, from the mount
            val ddx = aimX - mx; val ddy = aimY - my
            val dl = max(1e-3f, sqrt(ddx * ddx + ddy * ddy))
            val wx = ux + ddx / dl * w.speed; val wy = uy + ddy / dl * w.speed
            // closest approach to the missile, its acceleration carried on
            val qx = vx - wx; val qy = vy - wy
            val q2 = max(1e-3f, qx * qx + qy * qy)
            val tMax = w.range / w.speed * 1.2f
            val tc = (-(px * qx + py * qy) / q2).coerceIn(0f, tMax)
            val missX = px + qx * tc + 0.5f * ax * tc * tc
            val missY = py + qy * tc + 0.5f * ay * tc * tc
            val miss = sqrt(missX * missX + missY * missY)
            out[0] = tc

            // the spread across the missile's width at that distance
            val r = radius + SHOT_RADIUS
            val spreadR = w.speed * tc * tan(w.spread / 2f * DEG_TO_RAD)
            val p = if (spreadR < 1f) { if (miss <= r) 1f else 0f }
                    else max(0f, min(miss + r, spreadR) - max(miss - r, -spreadR)) / (2f * spreadR)
            return p * track
        }
    }
}
