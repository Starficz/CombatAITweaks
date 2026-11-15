package org.starficz.combataitweaks.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

// Everything in `sim` is plain Kotlin: no Global, no Misc (its class loading calls Global.getSettings()), no LazyLib,
// no API objects. The unit tests compile this package with only the Kotlin stdlib, which enforces that.

internal const val DEG_TO_RAD = (PI / 180.0).toFloat()

private val noFacings = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()

/**
 * An omni facing of all NaN ("keep its current offset") for [steps] steps, shared: read-only by convention (the
 * defence resolver only reads it).
 */
fun noFacing(steps: Int): FloatArray = noFacings.getOrPut(steps) { FloatArray(steps) { Float.NaN } }

/** A growable list of floats without boxing (hot paths of a batch). */
class FloatList(capacity: Int = 16) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    operator fun get(i: Int): Float = data[i]

    operator fun plusAssign(v: Float) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = v
    }

    fun toList(): List<Float> = List(size) { data[it] }
}
internal const val RAD_TO_DEG = (180.0 / PI).toFloat()

/** Angle in [0, 360). */
fun normalizeAngle(degrees: Float): Float {
    val a = degrees % 360f
    return if (a < 0f) a + 360f else a
}

/** Signed rotation from [from] to [to] in (-180, 180]; positive is counter-clockwise. */
fun shortestRotation(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

/** Direction of the vector (dx, dy) in degrees, 0 = +x, counter-clockwise. */
fun angleOf(dx: Float, dy: Float): Float = fastAtan2(dy, dx) * RAD_TO_DEG

/**
 * atan2 to within about 0.0015 rad (0.09 degrees), several times faster than Math.atan2 (which the profile showed
 * at a few percent of the game's CPU): an odd polynomial on [0, 1] plus octant folding.
 */
fun fastAtan2(y: Float, x: Float): Float {
    val ax = abs(x); val ay = abs(y)
    if (ax == 0f && ay == 0f) return 0f
    val a = if (ax >= ay) ay / ax else ax / ay
    val s = a * a
    var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a
    if (ay > ax) r = HALF_PI - r
    if (x < 0f) r = PI_F - r
    return if (y < 0f) -r else r
}

private const val PI_F = PI.toFloat()
private const val HALF_PI = (PI / 2.0).toFloat()

fun length(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)
