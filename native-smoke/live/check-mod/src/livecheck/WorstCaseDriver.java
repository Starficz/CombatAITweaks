package livecheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.combat.CombatEngine;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;
import org.starficz.combataitweaks.predictor.PredictorManager;
import org.starficz.combataitweaks.predictor.RequestResult;
import org.starficz.combataitweaks.sim.DamageResult;
import org.starficz.combataitweaks.sim.DefenseOutcome;
import org.starficz.combataitweaks.sim.Hit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Runs inside the battle WorstCase starts (group "worstcase"). Holds the CAT ship in place (movement and system
// commands blocked, so it matches the predictor's HOLD branch) while reckless enemies attack it, and compares, for
// overlapping 2 s windows:
//  - raw incoming damage (before shields, armor and the ship's damage-taken stats) predicted by the hold branch vs
//    what DamageTakenModifier sees hitting the ship, per shooter too;
//  - after defences: predicted shield flux and hull lost vs DamageListener's results.
public class WorstCaseDriver extends BaseEveryFrameCombatPlugin {
    private static final Logger log = Global.getLogger(WorstCaseDriver.class);

    static final int WARMUP = 240;
    static final int DURATION = Integer.getInteger("cat.worst.frames", 4800); // 80 s at 60 fps
    static final int SAMPLE_EVERY = 30;
    static final float WINDOW = 2f;
    /** Windows where both predicted and actual raw damage are under this are skipped as noise. */
    static final float FLOOR = 150f;
    static final int SHOT = 1500;

    private CombatEngineAPI engine;
    private boolean active;
    private int frame = 0;
    private ShipAPI p, parked;
    private boolean done = false;

    // what really hit the protected ship: {time, raw damage}, source id per event
    private final List<float[]> rawEvents = new ArrayList<>();
    private final List<String> rawSources = new ArrayList<>(), rawDelivery = new ArrayList<>();
    private final Map<String, Float> predictedByDelivery = new TreeMap<>(), actualByDelivery = new TreeMap<>();
    // per enemy: samples, venting or overloaded, beyond 1500 of the protected ship
    private final Map<String, int[]> enemyStates = new TreeMap<>();
    private float actualArmorTotal = 0f;
    // {time, shield damage, armor damage, hull damage}
    private final List<float[]> appliedEvents = new ArrayList<>();

    // open windows: {start, predicted raw, predicted shield flux, predicted hull}
    private final List<float[]> windows = new ArrayList<>();
    private final Map<String, Float> predictedBySource = new TreeMap<>(), actualBySource = new TreeMap<>();
    private final List<Float> ratios = new ArrayList<>(), shieldRatios = new ArrayList<>();
    private int samples = 0, withPrediction = 0, evaluated = 0, violations = 0, within25 = 0, quiet = 0;
    private int missedDamage = 0, protectedWasPlayerShip = 0;
    private float predictedHullTotal = 0f, actualHullTotal = 0f, predictedRawTotal = 0f, actualRawTotal = 0f;

    static void note(String s) {
        log.info("LIVE_CHECK note: " + s);
    }

    static void expect(boolean ok, String id) {
        if (ok) Ctx.passed++;
        else Ctx.failed++;
        log.info("LIVE_CHECK " + (ok ? "ok" : "FAIL") + ": worstcase / " + id);
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        String groups = System.getProperty("live.check", "");
        active = Arrays.asList(groups.split(",")).contains("worstcase") && !engine.isSimulation() && engine.isInCampaign();
        if (!active) return;
        ((CombatEngine) engine).setShowDeploymentDialog(false);
        note("CAT worst-case driver active");
    }

    private static String sourceOf(Object param) {
        ShipAPI s = null;
        if (param instanceof DamagingProjectileAPI) s = ((DamagingProjectileAPI) param).getSource();
        else if (param instanceof BeamAPI) s = ((BeamAPI) param).getSource();
        return s == null ? "other" : s.getId();
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!active || done) return;
        frame++;
        if (frame == 2) {
            engine.setDoNotEndCombat(true);
            CombatFleetManagerAPI own = engine.getFleetManager(0), foe = engine.getFleetManager(1);
            for (FleetMemberAPI m : own.getReservesCopy()) {
                boolean parked = m.getHullId().startsWith("ox");
                own.spawnFleetMember(m, parked ? new Vector2f(-9000f, -9000f) : new Vector2f(0f, -1200f), 90f, 0f);
            }
            int i = 0;
            for (FleetMemberAPI m : foe.getReservesCopy()) {
                foe.spawnFleetMember(m, new Vector2f((i - 1) * 700f, 1800f), 270f, 0f);
                i++;
            }
        }
        if (frame < 3) return;
        if (engine.isPaused()) engine.setPaused(false);
        if (frame == 3) {
            for (ShipAPI s : engine.getShips()) {
                s.setCurrentCR(Math.max(s.getCurrentCR(), 0.7f));
                if (s.getOwner() != 0 || s.isFighter() || s.isStationModule()) continue;
                if (s.getHullSpec().getHullId().startsWith("ox")) parked = s;
                else p = s;
            }
            if (parked != null) engine.setPlayerShipExternal(parked);
            expect(p != null, "protected ship deployed");
            if (p == null) { finish(); return; }
            if (p.getShipAI() == null) p.resetDefaultAI();
            p.addListener(new DamageTakenModifier() {
                @Override
                public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
                    if (target != p) return null;
                    // beams apply DPS damage in intervals of getDpsDuration() seconds (BeamWeaponRay)
                    float dpsTime = damage.getDpsDuration() > 0f ? damage.getDpsDuration() : engine.getElapsedInLastFrame();
                    float raw = damage.isDps() ? damage.getDamage() * dpsTime : damage.getDamage();
                    if (raw <= 0f) return null;
                    rawEvents.add(new float[]{engine.getTotalElapsedTime(false), raw});
                    rawSources.add(sourceOf(param));
                    rawDelivery.add(param instanceof com.fs.starfarer.api.combat.MissileAPI ? "MISSILE"
                            : param instanceof BeamAPI || damage.isDps() ? "BEAM" : "PROJECTILE");
                    return null;
                }
            });
            p.addListener(new DamageListener() {
                @Override
                public void reportDamageApplied(Object source, CombatEntityAPI target, ApplyDamageResultAPI r) {
                    if (target != p) return;
                    appliedEvents.add(new float[]{engine.getTotalElapsedTime(false), r.getDamageToShields(),
                            r.getTotalDamageToArmor(), r.getDamageToHull()});
                }
            });
        }
        if (p == null) return;
        if (engine.getPlayerShip() == p) protectedWasPlayerShip++;
        if (parked != null && parked.isAlive()) {
            parked.getLocation().set(-9000f, -9000f);
            parked.getVelocity().set(0f, 0f);
        }

        // hold: no thrust in any direction, no mobility system; the AI still turns, shields and fires
        for (ShipCommand cmd : new ShipCommand[]{ShipCommand.ACCELERATE, ShipCommand.ACCELERATE_BACKWARDS,
                ShipCommand.STRAFE_LEFT, ShipCommand.STRAFE_RIGHT, ShipCommand.USE_SYSTEM}) {
            p.blockCommandForOneFrame(cmd);
        }
        p.giveCommand(ShipCommand.DECELERATE, null, 0);

        if (frame >= SHOT - 90 && frame <= SHOT + 30) aimCamera();
        if (frame == SHOT - 90) Global.getSettings().setDevMode(true);
        if (frame == SHOT) log.info("LIVE_CHECK shot cat-worstcase-" + SHOT);
        if (frame == SHOT + 30) {
            engine.getViewport().setExternalControl(false);
            Global.getSettings().setDevMode(false);
        }

        float now = engine.getTotalElapsedTime(false);
        boolean dying = !p.isAlive() || p.isHulk() || p.getHullLevel() < 0.2f;
        if (frame >= WARMUP && frame % SAMPLE_EVERY == 0 && !dying) openWindow(now);
        resolveWindows(now, false);
        if (frame >= WARMUP + DURATION || dying) {
            if (dying) note("protected ship down to " + Math.round(p.getHullLevel() * 100f) + "% hull at " + now);
            resolveWindows(Float.MAX_VALUE, true);
            finish();
        }
    }

    private void openWindow(float now) {
        samples++;
        for (ShipAPI e : engine.getShips()) {
            if (e.getOwner() != 1 || e.isFighter() || !e.isAlive() || e.isHulk()) continue;
            int[] st = enemyStates.computeIfAbsent(e.getHullSpec().getHullId(), k -> new int[3]);
            st[0]++;
            if (e.getFluxTracker().isOverloadedOrVenting()) st[1]++;
            if (Vector2f.sub(e.getLocation(), p.getLocation(), null).length() > 1500f) st[2]++;
        }
        RequestResult hold = PredictorManager.Companion.getInstance(engine).latestHoldResult(p.getId());
        if (hold == null) return;
        DamageResult result = hold.getOutlook().getResult();
        if (result == null) return;
        withPrediction++;
        float rel = now - hold.getOutlook().getStartTime();
        float raw = 0f;
        for (Hit h : result.getHits()) {
            if (h.getTime() < rel || h.getTime() >= rel + WINDOW) continue;
            raw += h.getDamage();
        }
        DefenseOutcome o = result.getNormal();
        float shield = between(o.getShieldFlux(), o, rel, rel + WINDOW);
        float hull = between(o.getHullLost(), o, rel, rel + WINDOW);
        windows.add(new float[]{now, raw, shield, hull, rel});
        // per-source totals over the same windows (one window every 2 s, so each moment is counted once)
        if ((samples % Math.round(WINDOW * 60f / SAMPLE_EVERY)) == 0) {
            for (Hit h : result.getHits()) {
                if (h.getTime() < rel || h.getTime() >= rel + WINDOW) continue;
                predictedBySource.merge(h.getSourceId(), h.getDamage(), Float::sum);
                predictedByDelivery.merge(h.getDelivery().name(), h.getDamage(), Float::sum);
            }
            windows.get(windows.size() - 1)[4] = -1f; // mark: count actual per source for this window
        }
    }

    private static float between(float[] cumulative, DefenseOutcome o, float from, float to) {
        float a = from <= 0f ? 0f : cumulative[o.at(from)];
        float b = to <= 0f ? 0f : cumulative[o.at(to)];
        return b - a;
    }

    private void resolveWindows(float now, boolean all) {
        for (int k = windows.size() - 1; k >= 0; k--) {
            float[] w = windows.get(k);
            float start = w[0], end = start + WINDOW;
            if (!all && end > now) continue;
            windows.remove(k);
            if (end > engine.getTotalElapsedTime(false)) continue; // cut short by the end of the run
            float raw = 0f;
            for (int i = 0; i < rawEvents.size(); i++) {
                float[] e = rawEvents.get(i);
                if (e[0] < start || e[0] >= end) continue;
                raw += e[1];
                if (w[4] < 0f) {
                    actualBySource.merge(rawSources.get(i), e[1], Float::sum);
                    actualByDelivery.merge(rawDelivery.get(i), e[1], Float::sum);
                }
            }
            float shield = 0f, hull = 0f;
            for (float[] e : appliedEvents) {
                if (e[0] < start || e[0] >= end) continue;
                shield += e[1];
                hull += e[3];
                actualArmorTotal += e[2];
            }
            float predicted = w[1];
            predictedRawTotal += predicted;
            actualRawTotal += raw;
            predictedHullTotal += w[3];
            actualHullTotal += hull;
            if (predicted < FLOOR && raw < FLOOR) { quiet++; continue; }
            evaluated++;
            if (predicted < FLOOR) { missedDamage++; continue; }
            float ratio = raw / predicted;
            ratios.add(ratio);
            if (ratio > 1.25f) violations++;
            if (ratio >= 0.75f && ratio <= 1.25f) within25++;
            if (w[2] > FLOOR) shieldRatios.add(shield / w[2]);
        }
    }

    private void aimCamera() {
        ViewportAPI vp = engine.getViewport();
        float w = 4000f, h = w * Global.getSettings().getScreenHeight() / Global.getSettings().getScreenWidth();
        vp.setExternalControl(true);
        vp.set(p.getLocation().x - w / 2f, p.getLocation().y - h / 2f + 800f, w, h);
    }

    private static float pct(List<Float> v, double q) {
        if (v.isEmpty()) return Float.NaN;
        List<Float> c = new ArrayList<>(v);
        Collections.sort(c);
        return c.get(Math.min(c.size() - 1, (int) (c.size() * q)));
    }

    private void finish() {
        done = true;
        int n = ratios.size();
        note(String.format("windows: %d evaluated (%d quiet under %.0f both), %d with prediction, of %d samples",
                evaluated, quiet, FLOOR, withPrediction, samples));
        note(String.format("raw damage actual / predicted per %.0f s window: p10 %.2f, median %.2f, p90 %.2f (%d windows)",
                WINDOW, pct(ratios, 0.1), pct(ratios, 0.5), pct(ratios, 0.9), n));
        note(String.format("  within +-25%%: %d (%.0f%%); actual over 1.25x predicted: %d (%.0f%%); damage where none was predicted: %d",
                within25, n == 0 ? 0f : 100f * within25 / n, violations, n == 0 ? 0f : 100f * violations / n, missedDamage));
        note(String.format("totals over evaluated windows: raw predicted %.0f, actual %.0f; hull predicted %.0f, actual %.0f",
                predictedRawTotal, actualRawTotal, predictedHullTotal, actualHullTotal));
        note(String.format("shield damage actual / predicted shield flux: median %.2f, p10 %.2f, p90 %.2f (%d windows)",
                pct(shieldRatios, 0.5), pct(shieldRatios, 0.1), pct(shieldRatios, 0.9), shieldRatios.size()));
        List<String> sources = new ArrayList<>(predictedBySource.keySet());
        for (String s : actualBySource.keySet()) if (!sources.contains(s)) sources.add(s);
        for (String s : sources) {
            String name = s;
            for (ShipAPI ship : engine.getShips()) if (ship.getId().equals(s)) name = ship.getHullSpec().getHullId() + (ship.isFighter() ? " (fighter)" : "");
            note(String.format("  source %-28s predicted %8.0f  actual %8.0f", name,
                    predictedBySource.getOrDefault(s, 0f), actualBySource.getOrDefault(s, 0f)));
        }
        for (String d : predictedByDelivery.keySet()) note(String.format("  delivery %-10s predicted %8.0f  actual %8.0f", d,
                predictedByDelivery.get(d), actualByDelivery.getOrDefault(d, 0f)));
        note(String.format("armor damage actual (all windows, overlapping): %.0f", actualArmorTotal));
        for (Map.Entry<String, int[]> e : enemyStates.entrySet()) {
            int[] st = e.getValue();
            note(String.format("  enemy %-14s venting/overloaded %3.0f%% of samples, beyond 1500 %3.0f%%", e.getKey(),
                    100f * st[1] / Math.max(1, st[0]), 100f * st[2] / Math.max(1, st[0])));
        }
        expect(protectedWasPlayerShip == 0, "the protected ship was never the player's ship (CAT stands down there): "
                + protectedWasPlayerShip + " frames");
        expect(!rawEvents.isEmpty(), "the protected ship was attacked (" + rawEvents.size() + " hits)");
        expect(samples > 0 && withPrediction > samples * 0.8f, "hold predictions exist: " + withPrediction + " of " + samples);
        expect(evaluated >= 10, "enough windows to compare: " + evaluated);
        org.starficz.combataitweaks.runtime.TraceRecorder.Companion.flushNow(engine);
        log.info("LIVE_CHECK COMPLETE passed=" + Ctx.passed + " failed=" + Ctx.failed);
        System.exit(Ctx.failed == 0 ? 0 : 3);
    }
}
