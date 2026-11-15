package org.starficz.combataitweaks.runtime

import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import java.util.IdentityHashMap
import kotlin.math.max

/**
 * One pass over the battle per frame, shared by every CAT ship (main thread only). Each ship used to walk the
 * engine's projectile, missile, beam and ship lists itself (and sum every ship's weapon DPS) several times a frame;
 * now the first CAT ship to ask in a frame fills flat arrays and the rest read them.
 *
 * - shots: enemy-able projectiles that can still hit (not missiles, not fading, not spent);
 * - missiles: the same for missiles (not fizzling), with the speed they can close at;
 * - beams: every beam with a source;
 * - ships: alive, not hulks, not fighters; their DPS ([dps]) is cached for [DPS_REFRESH] s.
 */
object CombatScan {
    private const val DPS_REFRESH = 1f

    private var engine: CombatEngineAPI? = null
    private var stamp = Float.NaN

    var shotCount = 0
        private set
    var shotX = FloatArray(256); private set
    var shotY = FloatArray(256); private set
    var shotVx = FloatArray(256); private set
    var shotVy = FloatArray(256); private set
    var shotOwner = IntArray(256); private set

    var missileCount = 0
        private set
    var missileX = FloatArray(64); private set
    var missileY = FloatArray(64); private set
    /** max(current speed, max speed): how fast it can close. */
    var missileSpeed = FloatArray(64); private set
    var missileOwner = IntArray(64); private set

    val beams = ArrayList<BeamAPI>()
    val ships = ArrayList<ShipAPI>()

    private class Dps(var value: Float, var at: Float)
    private val dpsCache = IdentityHashMap<ShipAPI, Dps>()
    private var dpsSweptAt = 0f

    /** The scan for this frame of [engine], filled on the first call in the frame. */
    fun of(engine: CombatEngineAPI): CombatScan {
        val now = engine.getTotalElapsedTime(false)
        if (engine === this.engine && now == stamp) return this
        if (engine !== this.engine) { dpsCache.clear(); dpsSweptAt = 0f }
        this.engine = engine
        stamp = now
        fill(engine, now)
        return this
    }

    private fun fill(engine: CombatEngineAPI, now: Float) {
        shotCount = 0
        for (p in engine.projectiles) {
            if (p is MissileAPI || p.didDamage() || p.isFading) continue
            if (shotCount == shotX.size) growShots()
            val k = shotCount++
            shotX[k] = p.location.x; shotY[k] = p.location.y
            shotVx[k] = p.velocity.x; shotVy[k] = p.velocity.y
            shotOwner[k] = p.owner
        }
        missileCount = 0
        for (m in engine.missiles) {
            if (m.didDamage() || m.isFading || m.isFizzling) continue
            if (missileCount == missileX.size) growMissiles()
            val k = missileCount++
            missileX[k] = m.location.x; missileY[k] = m.location.y
            missileSpeed[k] = max(m.velocity.length(), m.maxSpeed)
            missileOwner[k] = m.owner
        }
        beams.clear()
        for (b in engine.beams) if (b.source != null) beams += b
        ships.clear()
        for (s in engine.ships) if (s.isAlive && !s.isHulk && !s.isFighter) ships += s
        // forget dead ships now and then
        if (now - dpsSweptAt > 10f) {
            dpsSweptAt = now
            dpsCache.keys.retainAll(ships.toSet())
        }
    }

    /** Sum of the DPS of [ship]'s weapons that can fire at ships (PD-only left out), cached for a second. */
    fun dps(ship: ShipAPI): Float {
        val now = stamp
        val cached = dpsCache[ship]
        if (cached != null && now - cached.at < DPS_REFRESH) return cached.value
        var sum = 0f
        for (w in ship.allWeapons) if (!w.hasAIHint(WeaponAPI.AIHints.PD_ONLY)) sum += w.derivedStats.dps
        if (cached != null) { cached.value = sum; cached.at = now } else dpsCache[ship] = Dps(sum, now)
        return sum
    }

    /**
     * Whether any non-fighter ship hostile to [owner] is within [range] of (x, y) (its collision radius counted,
     * as LazyLib's AIUtils.getNearbyEnemies does).
     */
    fun anyEnemyNear(owner: Int, x: Float, y: Float, range: Float): Boolean {
        for (k in ships.indices) {
            val s = ships[k]
            if (!isEnemy(s, owner)) continue
            if (within(s, x, y, range)) return true
        }
        return false
    }

    /** The nearest non-fighter ship hostile to [owner] within [range] of (x, y), or null. */
    fun nearestEnemy(owner: Int, x: Float, y: Float, range: Float): ShipAPI? {
        var best: ShipAPI? = null
        var bestD = Float.MAX_VALUE
        for (k in ships.indices) {
            val s = ships[k]
            if (!isEnemy(s, owner) || !within(s, x, y, range)) continue
            val dx = s.location.x - x; val dy = s.location.y - y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = s }
        }
        return best
    }

    private fun isEnemy(s: ShipAPI, owner: Int) = s.owner != owner && s.owner != 100

    private fun within(s: ShipAPI, x: Float, y: Float, range: Float): Boolean {
        val r = range + s.collisionRadius
        val dx = s.location.x - x; val dy = s.location.y - y
        return dx * dx + dy * dy <= r * r
    }

    private fun growShots() {
        val n = shotX.size * 2
        shotX = shotX.copyOf(n); shotY = shotY.copyOf(n); shotVx = shotVx.copyOf(n); shotVy = shotVy.copyOf(n)
        shotOwner = shotOwner.copyOf(n)
    }

    private fun growMissiles() {
        val n = missileX.size * 2
        missileX = missileX.copyOf(n); missileY = missileY.copyOf(n); missileSpeed = missileSpeed.copyOf(n)
        missileOwner = missileOwner.copyOf(n)
    }
}
