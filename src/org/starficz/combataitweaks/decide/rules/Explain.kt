package org.starficz.combataitweaks.decide

// Pure: no game API, compiled into the unit tests with `sim` (test/run-unit-tests.ps1).

/**
 * Whether the decision rules spell out their reasons with numbers (the decision trace, plan §8.6). Only the trace
 * recorder and the smoke checks read them, and String.format every frame for every ship is real main-thread time and
 * garbage, so in normal play the rules give short fixed reasons instead. Set once per frame from
 * TraceRecorder.enabled(); on by default (tests).
 */
object Explain {
    @Volatile
    @JvmField
    var enabled = true
}
