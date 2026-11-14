package livecheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.combat.CombatEngine;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;
import org.starficz.combataitweaks.CombatAIv2;
import org.starficz.combataitweaks.predictor.DamageOutlook;
import org.starficz.combataitweaks.predictor.PredictorManager;
import org.starficz.combataitweaks.predictor.RequestResult;
import org.starficz.combataitweaks.sim.Timeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// Runs inside the battle AiBattle starts (registered in data/config/settings.json, active only for group "aibattle").
// Deploys everything, lets the battle run with CAT on the player side and vanilla AI on the enemy, and checks that
// the AI is attached, the predictor keeps producing fresh results, and the behaviour states actually change.
public class AiBattleDriver extends BaseEveryFrameCombatPlugin {
    private static final Logger log = Global.getLogger(AiBattleDriver.class);
    static int taggedMembers = 0;

    static final int WARMUP = 300;      // frames before sampling: deployment, listener attach, first predictions
    static final int DURATION = 5400;   // frames sampled (90 s of game time at 60 fps)
    static final int SAMPLE_EVERY = 15;
    static final int[] SHOTS = {1800, 4200};

    private CombatEngineAPI engine;
    private boolean active;
    private int frame = 0;

    private int samples = 0, withPrediction = 0, missingListener = 0, enemyWithListener = 0;
    private final List<Float> staleness = new ArrayList<>();
    private final Map<String, Integer> states = new TreeMap<>();
    private final Set<Float> predictionBatches = new HashSet<>();
    private final Set<String> catShipsSeen = new HashSet<>(), catShipsVented = new HashSet<>();
    private final Set<String> overloaded0 = new HashSet<>(), overloaded1 = new HashSet<>();
    private float firstSampleTime = -1f, lastSampleTime = 0f;

    // milestone 1: the enemy plan. How many enemies commit, and how far a committed enemy's real position 2 s later
    // is from its predicted pursuit position (worst-case pursuit vs what vanilla actually did).
    static final float CHECK_AHEAD = 2f;
    private int holdSamples = 0, samplesWithCommitted = 0, committedTotal = 0;
    // {dueTime, pursuitX, pursuitY, coastX, coastY, targetsThisShip (1/0), predicted P x, predicted P y}
    private final List<float[]> pendingChecks = new ArrayList<>();
    private final List<String> pendingIds = new ArrayList<>();
    private final List<String> pendingProtected = new ArrayList<>();
    // the worst-case bound: did the enemy really get closer to the CAT ship than predicted? (distance, not position)
    private final List<Float> pursuitSlack = new ArrayList<>(), coastSlack = new ArrayList<>();
    private int pursuitViolations = 0, coastViolations = 0;
    // same, only where the CAT ship really stayed within 150 of its predicted hold position (the branch being checked)
    private int heldChecks = 0, heldPursuitViolations = 0, heldCoastViolations = 0;
    private final List<Float> pursuitError = new ArrayList<>();
    // split: enemies whose vanilla maneuver target IS the CAT ship (the worst case is really happening) vs the rest,
    // each compared with v1's coast prediction for the same enemy at the same moment
    private final List<Float> pursuitErrTargeting = new ArrayList<>(), coastErrTargeting = new ArrayList<>();
    private final List<Float> pursuitErrOther = new ArrayList<>(), coastErrOther = new ArrayList<>();
    // milestone 3: every vent of a CAT ship, who decided it and what it really cost
    private final VentAudit ventAudit = new VentAudit();
    private final List<ShipAPI> catShips = new ArrayList<>();

    static void note(String s) {
        log.info("LIVE_CHECK note: " + s);
    }

    static void expect(boolean ok, String id) {
        if (ok) Ctx.passed++;
        else Ctx.failed++;
        log.info("LIVE_CHECK " + (ok ? "ok" : "FAIL") + ": aibattle / " + id);
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        String groups = System.getProperty("live.check", "");
        active = Arrays.asList(groups.split(",")).contains("aibattle") && !engine.isSimulation() && engine.isInCampaign();
        if (!active) return;
        ((CombatEngine) engine).setShowDeploymentDialog(false);
        note("CAT battle driver active");
    }

    private int deploy(int owner, float y, float facing) {
        CombatFleetManagerAPI fm = engine.getFleetManager(owner);
        int i = 0;
        for (FleetMemberAPI m : fm.getReservesCopy()) {
            float row = (i / 20) * 600f;
            fm.spawnFleetMember(m, new Vector2f(((i % 20) - 10f) * 450f, y + (owner == 0 ? -row : row)), facing, 0f);
            i++;
        }
        return i;
    }

    private static boolean isCountedShip(ShipAPI s) {
        return s.isAlive() && !s.isHulk() && !s.isFighter() && !s.isStationModule() && !s.isShuttlePod();
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!active) return;
        frame++;

        if (frame == 2) {
            engine.setDoNotEndCombat(true);
            int a = deploy(0, -3000f, 90f), b = deploy(1, 3000f, 270f);
            expect(a > 0 && b > 0, "deployed " + a + " and " + b + " ships");
        }
        if (frame < 3) return;
        if (engine.isPaused()) engine.setPaused(false);
        if (frame == 3) for (ShipAPI s : engine.getShips()) s.setCurrentCR(Math.max(s.getCurrentCR(), 0.7f));
        ShipAPI player = engine.getPlayerShip();
        if (player != null && player.getShipAI() == null) player.resetDefaultAI();

        for (int shot : SHOTS) {
            if (frame == shot - 90) Global.getSettings().setDevMode(true); // CAT's debug overlay draws in dev mode
            if (frame >= shot - 90 && frame <= shot + 30) aimCamera();
            if (frame == shot) log.info("LIVE_CHECK shot cat-overlay-" + shot);
            if (frame == shot + 30) {
                engine.getViewport().setExternalControl(false);
                Global.getSettings().setDevMode(false);
            }
        }

        if (frame < WARMUP) return;
        if (frame >= WARMUP + DURATION) {
            finish();
            return;
        }
        catShips.clear();
        for (ShipAPI s : engine.getShips()) {
            if (isCountedShip(s) && s.getOwner() == 0 && s != engine.getPlayerShip() && s.hasListenerOfClass(CombatAIv2.class)) catShips.add(s);
        }
        ventAudit.track(engine, catShips);
        if (frame % SAMPLE_EVERY == 0) sample();
        resolvePursuitChecks(engine.getTotalElapsedTime(false));
    }

    // centre on the player ship closest to an enemy, where the overlay has the most to show
    private void aimCamera() {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI s : engine.getShips()) {
            if (!isCountedShip(s) || s.getOwner() != 0) continue;
            for (ShipAPI e : engine.getShips()) {
                if (!isCountedShip(e) || e.getOwner() != 1) continue;
                float d = Vector2f.sub(s.getLocation(), e.getLocation(), null).lengthSquared();
                if (d < bestDist) {
                    bestDist = d;
                    best = s;
                }
            }
        }
        if (best == null) return;
        ViewportAPI vp = engine.getViewport();
        float w = 5000f, h = w * Global.getSettings().getScreenHeight() / Global.getSettings().getScreenWidth();
        vp.setExternalControl(true);
        vp.set(best.getLocation().x - w / 2f, best.getLocation().y - h / 2f, w, h);
    }

    private void sample() {
        float now = engine.getTotalElapsedTime(false);
        if (firstSampleTime < 0f) firstSampleTime = now;
        lastSampleTime = now;
        for (ShipAPI s : engine.getShips()) {
            if (!isCountedShip(s)) continue;
            if (s.getFluxTracker().isOverloaded()) (s.getOwner() == 0 ? overloaded0 : overloaded1).add(s.getId());
            boolean hasCat = s.hasListenerOfClass(CombatAIv2.class);
            if (s.getOwner() == 1) {
                if (hasCat) enemyWithListener++;
                continue;
            }
            if (s.getOwner() != 0) continue;
            if (s.getHullSpec().isPhase()) continue; // phase hulls are excluded by design
            // CAT skips the player's ship while it counts as manually flown, so its last data just goes stale
            if (s == engine.getPlayerShip()) continue;
            if (!hasCat) {
                missingListener++;
                continue;
            }
            samples++;
            catShipsSeen.add(s.getId());
            Object state = s.getCustomData().get(CombatAIv2.BEHAVIOR_STATE);
            states.merge(String.valueOf(state), 1, Integer::sum);
            if (s.getFluxTracker().isVenting()) catShipsVented.add(s.getId());
            samplePlan(s, now);
            RequestResult holdResult = PredictorManager.Companion.getInstance(engine).latestHoldResult(s.getId());
            if (holdResult != null) {
                withPrediction++;
                DamageOutlook t = holdResult.getOutlook();
                staleness.add(now - t.getStartTime());
                predictionBatches.add(t.getStartTime());
            }
        }
    }

    private void samplePlan(ShipAPI s, float now) {
        RequestResult hold = PredictorManager.Companion.getInstance(engine).latestHoldResult(s.getId());
        if (hold == null) return;
        holdSamples++;
        int n = hold.getCommitted().size();
        committedTotal += n;
        if (n > 0) samplesWithCommitted++;
        if (frame % 60 != 0) return;
        float start = hold.getOutlook().getStartTime();
        int frameIdx = Math.round(CHECK_AHEAD / org.starficz.combataitweaks.sim.ModelKt.DT) - 1;
        PredictorManager manager = PredictorManager.Companion.getInstance(engine);
        for (Map.Entry<String, Timeline> e : hold.getPursuitTimelines().entrySet()) {
            Timeline t = e.getValue();
            Timeline coast = manager.latestBaseTimeline(e.getKey());
            if (frameIdx >= t.getSteps() || coast == null) continue;
            ShipAPI enemy = null;
            for (ShipAPI o : engine.getShips()) if (o.getId().equals(e.getKey())) { enemy = o; break; }
            if (enemy == null || enemy.isFighter()) continue;
            boolean targetsUs = false;
            if (enemy.getShipAI() != null && enemy.getShipAI().getAIFlags() != null) {
                Object mt = enemy.getShipAI().getAIFlags().getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
                targetsUs = mt == s;
            }
            Timeline own = hold.getShipTimeline();
            pendingChecks.add(new float[]{start + CHECK_AHEAD, t.getX()[frameIdx], t.getY()[frameIdx],
                    coast.getX()[frameIdx], coast.getY()[frameIdx], targetsUs ? 1f : 0f,
                    own.getX()[frameIdx], own.getY()[frameIdx]});
            pendingIds.add(e.getKey());
            pendingProtected.add(s.getId());
        }
    }

    private void resolvePursuitChecks(float now) {
        for (int i = pendingChecks.size() - 1; i >= 0; i--) {
            float[] c = pendingChecks.get(i);
            if (now < c[0]) continue;
            ShipAPI enemy = null;
            for (ShipAPI s : engine.getShips()) if (s.getId().equals(pendingIds.get(i))) { enemy = s; break; }
            ShipAPI cat = null;
            for (ShipAPI s : engine.getShips()) if (s.getId().equals(pendingProtected.get(i))) { cat = s; break; }
            if (enemy != null && enemy.isAlive() && !enemy.isFighter() && cat != null && cat.isAlive()) {
                // slack = real distance - predicted distance; negative beyond -100 = closer than the worst case allowed
                float real = Vector2f.sub(enemy.getLocation(), cat.getLocation(), null).length();
                float predPursuit = (float) Math.hypot(c[1] - c[6], c[2] - c[7]);
                float predCoast = (float) Math.hypot(c[3] - c[6], c[4] - c[7]);
                pursuitSlack.add(real - predPursuit);
                coastSlack.add(real - predCoast);
                if (real < predPursuit - 100f) pursuitViolations++;
                if (real < predCoast - 100f) coastViolations++;
                boolean held = Math.hypot(cat.getLocation().x - c[6], cat.getLocation().y - c[7]) < 150f;
                if (held) {
                    heldChecks++;
                    if (real < predPursuit - 100f) heldPursuitViolations++;
                    if (real < predCoast - 100f) heldCoastViolations++;
                }
            }
            if (enemy != null && enemy.isAlive() && !enemy.isFighter()) {
                float pe = Vector2f.sub(enemy.getLocation(), new Vector2f(c[1], c[2]), null).length();
                float ce = Vector2f.sub(enemy.getLocation(), new Vector2f(c[3], c[4]), null).length();
                pursuitError.add(pe);
                (c[5] > 0f ? pursuitErrTargeting : pursuitErrOther).add(pe);
                (c[5] > 0f ? coastErrTargeting : coastErrOther).add(ce);
            }
            pendingChecks.remove(i);
            pendingIds.remove(i);
            pendingProtected.remove(i);
        }
    }

    private static float pct(List<Float> v, double p) {
        if (v.isEmpty()) return Float.NaN;
        List<Float> c = new ArrayList<>(v);
        Collections.sort(c);
        return c.get(Math.min(c.size() - 1, (int) (c.size() * p)));
    }

    private void finish() {
        float span = Math.max(0.001f, lastSampleTime - firstSampleTime);
        note("tagged player members: " + taggedMembers + ", CAT ships seen: " + catShipsSeen.size());
        note("behaviour state samples: " + states);
        note(String.format("prediction age: median %.2f s, p95 %.2f s, max %.2f s over %d samples",
                pct(staleness, 0.5), pct(staleness, 0.95), pct(staleness, 1.0), staleness.size()));
        note(String.format("predictor batches: %d in %.1f s of game time (%.1f per second)",
                predictionBatches.size(), span, predictionBatches.size() / span));
        PredictorManager pm = PredictorManager.Companion.getInstance(engine);
        int done = pm.getBatchesCompleted();
        note(String.format("predictor throughput: %d batches done in %.1f s (%.1f per second), %.0f ms and %.1f branches per batch",
                done, span, done / span, done == 0 ? 0f : pm.getBatchMillisTotal() / (float) done,
                done == 0 ? 0f : pm.getBranchesTotal() / (float) done));
        note("CAT ships that vented: " + catShipsVented.size() + "; ships overloaded at a sample: CAT side "
                + overloaded0.size() + ", vanilla side " + overloaded1.size());

        note(String.format("enemy plan: %d of %d hold samples had committed enemies, %.2f committed per sample",
                samplesWithCommitted, holdSamples, holdSamples == 0 ? 0f : committedTotal / (float) holdSamples));
        note(String.format("pursuit error after %.0f s (committed non-fighters, real vs predicted): median %.0f, p90 %.0f over %d checks",
                CHECK_AHEAD, pct(pursuitError, 0.5), pct(pursuitError, 0.9), pursuitError.size()));
        note(String.format("  enemies actually targeting the CAT ship: pursuit median %.0f / p90 %.0f vs v1 coast median %.0f / p90 %.0f (%d checks)",
                pct(pursuitErrTargeting, 0.5), pct(pursuitErrTargeting, 0.9), pct(coastErrTargeting, 0.5), pct(coastErrTargeting, 0.9), pursuitErrTargeting.size()));
        note(String.format("  enemies fighting someone else:           pursuit median %.0f / p90 %.0f vs v1 coast median %.0f / p90 %.0f (%d checks)",
                pct(pursuitErrOther, 0.5), pct(pursuitErrOther, 0.9), pct(coastErrOther, 0.5), pct(coastErrOther, 0.9), pursuitErrOther.size()));

        int n = pursuitSlack.size();
        note(String.format("worst-case bound after %.0f s: enemy got >100 closer than predicted in %d of %d checks (%.1f%%) for pursuit, %d (%.1f%%) for v1 coast; median slack pursuit %.0f, coast %.0f",
                CHECK_AHEAD, pursuitViolations, n, n == 0 ? 0f : 100f * pursuitViolations / n, coastViolations, n == 0 ? 0f : 100f * coastViolations / n,
                pct(pursuitSlack, 0.5), pct(coastSlack, 0.5)));

        note(String.format("  where the CAT ship really held its predicted position: pursuit violated %d of %d (%.1f%%), v1 coast %d (%.1f%%)",
                heldPursuitViolations, heldChecks, heldChecks == 0 ? 0f : 100f * heldPursuitViolations / heldChecks,
                heldCoastViolations, heldChecks == 0 ? 0f : 100f * heldCoastViolations / heldChecks));

        int catVents = ventAudit.count(true), vanillaVents = ventAudit.count(false);
        note(String.format("vent audit: %d vents decided by CAT (%d safe), %d started by vanilla; safe vents over their limit: %d",
                catVents, ventAudit.safeCount(), vanillaVents, ventAudit.safeOverLimit()));
        for (String line : ventAudit.lines()) note("  " + line);
        expect(ventAudit.safeOverLimit() == 0, "no safe vent took more than its limits (" + ventAudit.safeOverLimit()
                + " of " + ventAudit.safeCount() + ")");
        expect(vanillaVents <= Math.max(1, catVents / 10), "CAT owns the vent decision: " + vanillaVents
                + " vents started by vanilla vs " + catVents + " by CAT");

        expect(!catShipsSeen.isEmpty(), "CAT AI attached to player ships (" + catShipsSeen.size() + " ships)");
        expect(samplesWithCommitted > 0, "the enemy plan commits enemies to CAT ships (" + samplesWithCommitted + " samples)");
        expect(!pursuitError.isEmpty(), "committed enemies' pursuit predictions were checked against reality ("
                + pursuitError.size() + " checks)");
        expect(missingListener == 0, "every alive non-phase player ship had the CAT listener at every sample (missing "
                + missingListener + " times)");
        expect(enemyWithListener == 0, "no untagged (enemy) ship got the CAT listener (" + enemyWithListener + " times)");
        expect(samples > 0 && withPrediction > samples * 0.8,
                "predictions exist for most CAT ship samples: " + withPrediction + " of " + samples);
        expect(!staleness.isEmpty() && pct(staleness, 0.5) < 1.0f,
                "predictions stay fresh: median age " + pct(staleness, 0.5) + " s");
        expect(predictionBatches.size() > span * 0.5f,
                "the predictor keeps running: " + predictionBatches.size() + " batches in " + span + " s");
        int nonFallbackStates = 0;
        for (Map.Entry<String, Integer> e : states.entrySet()) if (!e.getKey().equals("VANILLA_FALLBACK") && !e.getKey().equals("null")) nonFallbackStates++;
        expect(nonFallbackStates >= 2, "the AI uses more than one behaviour state: " + states.keySet());

        org.starficz.combataitweaks.runtime.TraceRecorder.Companion.flushNow(engine);
        log.info("LIVE_CHECK COMPLETE passed=" + Ctx.passed + " failed=" + Ctx.failed);
        System.exit(Ctx.failed == 0 ? 0 : 3);
    }
}
