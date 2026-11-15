package livecheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.combat.CombatEngine;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;
import org.starficz.combataitweaks.CombatAIv2;
import org.starficz.combataitweaks.decide.MoveController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Runs inside the battle MoveCases starts (group "movecases"). Each scenario places one CAT ship at the origin (the
// other is parked far away) and checks the movement decision (plan §10):
//  advance:   an unarmed Lasher 2200 away                     -> ADVANCE, and the ship closes in
//  threat:    a Dominator 1300 away, the Brawler alone     -> BACK_OFF / DISENGAGE, and the reason is the danger
//  automated: the same Dominator against the Warden        -> it backs off too when caught out, by direct thrust
//             (vanilla won't), and isn't caught
//  standoff:  a slow Enforcer with only chainguns (450) 1300 away, against the Brawler's Arbalests (700)
//             -> HOLD at the standoff (plan §13.4), or ADVANCE where that is out of reach too: it deals damage
//             and takes none
public class MoveCasesDriver extends BaseEveryFrameCombatPlugin {
    private static final Logger log = Global.getLogger(MoveCasesDriver.class);

    private enum Phase { WARMUP, ADVANCE, THREAT, AUTOMATED, STANDOFF, DONE }

    private static final int PHASE_FRAMES = 600;
    private static final Vector2f AWAY = new Vector2f(-6000f, 6000f);

    private CombatEngineAPI engine;
    private boolean active;
    private int frame = 0, phaseStart = 0;
    private Phase phase = Phase.WARMUP;
    private ShipAPI frigate, automated, flag, parkedEnemy, target, threat;
    private ShipAPI p;
    private final Map<String, Integer> states = new LinkedHashMap<>();
    private int samples = 0, firstBackOff = -1;
    private float startDist = 0f, minDist = Float.MAX_VALUE, maxDist = 0f, radialSum = 0f;
    private int radialSamples = 0;
    private String firstBackOffReason = null, lastReason = null;
    private float startHull = 0f;
    private String lastState = null;
    private float startArmor = 0f, maxHardFlux = 0f, threatStartHull = 0f, threatStartArmor = 0f, threatMaxFlux = 0f;

    static void note(String s) {
        log.info("LIVE_CHECK note: " + s);
    }

    static void expect(boolean ok, String id) {
        if (ok) Ctx.passed++;
        else Ctx.failed++;
        log.info("LIVE_CHECK " + (ok ? "ok" : "FAIL") + ": movecases / " + id);
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        String groups = System.getProperty("live.check", "");
        active = Arrays.asList(groups.split(",")).contains("movecases") && !engine.isSimulation() && engine.isInCampaign();
        if (!active) return;
        ((CombatEngine) engine).setShowDeploymentDialog(false);
        note("CAT move cases driver active");
    }

    private static void pin(ShipAPI s, float x, float y) {
        if (s == null || !s.isAlive()) return;
        s.getLocation().set(x, y);
        s.getVelocity().set(0f, 0f);
    }

    private static boolean isHull(ShipAPI s, String variant) {
        return s.getHullSpec().getHullId().equals(variant.substring(0, variant.indexOf('_')));
    }

    private int age() {
        return frame - phaseStart;
    }

    private void enter(Phase next, ShipAPI ship) {
        phase = next;
        phaseStart = frame;
        p = ship;
        states.clear();
        samples = 0;
        firstBackOff = -1;
        firstBackOffReason = null;
        lastReason = null;
        lastState = null;
        minDist = Float.MAX_VALUE;
        maxDist = 0f;
        radialSum = 0f;
        radialSamples = 0;
        if (p != null) {
            startHull = p.getHitpoints();
            pin(p, 0f, 0f);
            p.setCurrentCR(Math.max(p.getCurrentCR(), 0.7f));
            p.getFluxTracker().setCurrFlux(0f);
            p.getFluxTracker().setHardFlux(0f);
        }
    }

    private String state(ShipAPI s) {
        Object o = s.getCustomData().get(CombatAIv2.BEHAVIOR_STATE);
        return o == null ? "none" : o.toString();
    }

    private String reason(ShipAPI s) {
        Object o = s.getCustomData().get(MoveController.TRACE);
        return o == null ? "(no decision)" : o.toString();
    }

    private float share(String... names) {
        int n = 0;
        for (String name : names) n += states.getOrDefault(name, 0);
        return samples == 0 ? 0f : n / (float) samples;
    }

    // per-frame bookkeeping against the ship the scenario measures from
    private void sample(ShipAPI from) {
        if (p == null || !p.isAlive() || from == null || !from.isAlive()) return;
        String st = state(p);
        if (!st.equals(lastState)) {
            note(phase.name().toLowerCase() + " t=" + String.format("%.1f", age() / 60f) + "s: " + st + " - " + reason(p));
            lastState = st;
        }
        if (age() < 60) return;   // let the first predictions arrive
        samples++;
        states.merge(st, 1, Integer::sum);
        lastReason = reason(p);
        float dx = p.getLocation().x - from.getLocation().x, dy = p.getLocation().y - from.getLocation().y;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        minDist = Math.min(minDist, d);
        maxDist = Math.max(maxDist, d);
        if (d > 1f) {
            radialSum += (p.getVelocity().x * dx + p.getVelocity().y * dy) / d;
            radialSamples++;
        }
        boolean backing = st.equals("BACK_OFF") || st.equals("DISENGAGE");
        if (backing && firstBackOff < 0) {
            firstBackOff = age();
            firstBackOffReason = reason(p);
        }
    }

    // a combat hull with its weapons taken out: vanilla engages it (it ignored an unarmed civilian ox), and it can't
    // threaten anything, so no enemy commits
    private ShipAPI spawnUnarmed(String variantId, Vector2f at) {
        FleetMemberAPI m = Global.getFactory().createFleetMember(com.fs.starfarer.api.fleet.FleetMemberType.SHIP, variantId);
        com.fs.starfarer.api.combat.ShipVariantAPI v = m.getVariant().clone();
        v.clearWeapons();
        v.getWeaponGroups().clear();
        m.setVariant(v, false, true);
        m.getRepairTracker().setCR(0.7f);
        m.setOwner(1);   // a new member is owner 0 (the player's side) until told otherwise
        return engine.getFleetManager(1).spawnFleetMember(m, at, 270f, 0f);
    }

    // a slow ship whose medium ballistic slots all carry chainguns (450) and nothing else: the Brawler's Arbalests
    // (700) outrange it
    private ShipAPI spawnShortGuns(String variantId, Vector2f at) {
        FleetMemberAPI m = Global.getFactory().createFleetMember(com.fs.starfarer.api.fleet.FleetMemberType.SHIP, variantId);
        com.fs.starfarer.api.combat.ShipVariantAPI v = m.getVariant().clone();
        v.clearWeapons();
        v.getWeaponGroups().clear();
        for (com.fs.starfarer.api.loading.WeaponSlotAPI slot : v.getHullSpec().getAllWeaponSlotsCopy()) {
            if (slot.getWeaponType() == com.fs.starfarer.api.combat.WeaponAPI.WeaponType.BALLISTIC
                    && slot.getSlotSize() == com.fs.starfarer.api.combat.WeaponAPI.WeaponSize.MEDIUM) {
                v.addWeapon(slot.getId(), "chaingun");
            }
        }
        v.autoGenerateWeaponGroups();
        m.setVariant(v, false, true);
        m.getRepairTracker().setCR(0.7f);
        m.setOwner(1);
        return engine.getFleetManager(1).spawnFleetMember(m, at, 270f, 0f);
    }

    private static float armor(ShipAPI s) {
        float sum = 0f;
        for (float[] col : s.getArmorGrid().getGrid()) for (float c : col) sum += c;
        return sum;
    }

    private float distance(ShipAPI a, ShipAPI b) {
        return Vector2f.sub(a.getLocation(), b.getLocation(), null).length();
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!active || phase == Phase.DONE) return;
        frame++;
        if (frame == 2) {
            engine.setDoNotEndCombat(true);
            CombatFleetManagerAPI own = engine.getFleetManager(0), foe = engine.getFleetManager(1);
            for (FleetMemberAPI m : own.getReservesCopy()) {
                boolean parked = m.getHullId().startsWith("ox");
                own.spawnFleetMember(m, parked ? new Vector2f(-9000f, -9000f) : new Vector2f(AWAY), 90f, 0f);
            }
            for (FleetMemberAPI m : foe.getReservesCopy()) foe.spawnFleetMember(m, new Vector2f(9000f, 9000f), 270f, 0f);
        }
        if (frame < 3) return;
        if (engine.isPaused()) engine.setPaused(false);
        if (frame == 3) {
            for (ShipAPI s : engine.getShips()) {
                if (s.isFighter() || s.isStationModule()) continue;
                s.setCurrentCR(Math.max(s.getCurrentCR(), 0.7f));
                if (s.getOwner() == 1) parkedEnemy = s;
                else if (isHull(s, MoveCases.FRIGATE)) frigate = s;
                else if (isHull(s, MoveCases.AUTOMATED)) automated = s;
                else if (s.getHullSpec().getHullId().startsWith("ox")) flag = s;
            }
            expect(frigate != null && automated != null && flag != null && parkedEnemy != null, "ships deployed");
            if (frigate == null || automated == null) { finish(); return; }
            if (flag != null) engine.setPlayerShipExternal(flag);
        }
        if (frigate == null) return;
        pin(flag, -9000f, -9000f);
        pin(parkedEnemy, 9000f, 9000f);
        if (p != frigate) pin(frigate, AWAY.x, AWAY.y);
        if (p != automated) pin(automated, AWAY.x + 400f, AWAY.y);

        switch (phase) {
            case WARMUP:
                if (frame >= 240) {
                    target = spawnUnarmed("lasher_Standard", new Vector2f(0f, 2200f));
                    expect(target != null, "advance: unarmed Lasher spawned 2200 away");
                    enter(Phase.ADVANCE, frigate);
                    startDist = 2200f;
                }
                break;
            case ADVANCE:
                pin(target, 0f, 2200f);
                sample(target);
                if (age() >= PHASE_FRAMES) {
                    float end = target == null ? 0f : distance(p, target);
                    note(String.format("advance: states %s, distance 2200 -> %.0f (min %.0f); last: %s", states, end, minDist, lastReason));
                    expect(share("ADVANCE") >= 0.7f, String.format("advance: nothing threatening -> ADVANCE (%.0f%% of samples)", 100f * share("ADVANCE")));
                    expect(minDist <= startDist - 500f, String.format("advance: the ship closed in (closest %.0f of 2200)", minDist));
                    if (target != null) engine.removeEntity(target);
                    target = null;
                    threat = engine.getFleetManager(1).spawnShipOrWing("dominator_Assault", new Vector2f(0f, 1300f), 270f);
                    expect(threat != null, "threat: Dominator spawned 1300 away");
                    enter(Phase.THREAT, frigate);
                    startDist = 1300f;
                }
                break;
            case THREAT:
                sample(threat);
                if (age() >= PHASE_FRAMES || !frigate.isAlive()) {
                    reportBackOff("threat", "the Brawler");
                    if (threat != null) engine.removeEntity(threat);
                    threat = engine.getFleetManager(1).spawnShipOrWing("dominator_Assault", new Vector2f(0f, 1300f), 270f);
                    enter(Phase.AUTOMATED, automated);
                    startDist = 1300f;
                }
                break;
            case AUTOMATED:
                sample(threat);
                if (age() >= PHASE_FRAMES || !automated.isAlive()) {
                    reportBackOff("automated", "the Warden");
                    float radial = radialSamples == 0 ? 0f : radialSum / radialSamples;
                    // not a check: under the escape rule the Warden may close in while it can still get out; what
                    // matters is that it isn't caught (reportBackOff's hull check)
                    note(String.format("automated: the Warden's mean speed away from the threat %.0f", radial));
                    if (threat != null) engine.removeEntity(threat);
                    threat = null;
                    if (!frigate.isAlive()) { finish(); break; }
                    frigate.setHitpoints(frigate.getMaxHitpoints());
                    threat = spawnShortGuns("enforcer_Assault", new Vector2f(0f, 1300f));
                    expect(threat != null, "standoff: chaingun Enforcer spawned 1300 away");
                    enter(Phase.STANDOFF, frigate);
                    startDist = 1300f;
                    startArmor = armor(frigate);
                    maxHardFlux = 0f;
                    if (threat != null) {
                        threatStartHull = threat.getHitpoints();
                        threatStartArmor = armor(threat);
                        threatMaxFlux = 0f;
                    }
                }
                break;
            case STANDOFF:
                sample(threat);
                if (age() >= 60) maxHardFlux = Math.max(maxHardFlux, frigate.getFluxTracker().getHardFlux());
                if (threat != null && threat.isAlive()) threatMaxFlux = Math.max(threatMaxFlux, threat.getFluxTracker().getCurrFlux());
                if (age() >= PHASE_FRAMES * 3 / 2 || !frigate.isAlive() || threat == null || !threat.isAlive()) {
                    float hullLost = startHull - (frigate.isAlive() ? frigate.getHitpoints() : 0f);
                    float armorLost = startArmor - (frigate.isAlive() ? armor(frigate) : 0f);
                    float dealtHull = threat == null ? 0f : threatStartHull - (threat.isAlive() ? threat.getHitpoints() : 0f);
                    float dealtArmor = threat == null ? 0f : threatStartArmor - (threat.isAlive() ? armor(threat) : 0f);
                    note(String.format("standoff: states %s, distance %.0f -> min %.0f max %.0f; Brawler lost hull %.0f armor %.0f, max hard flux %.0f; "
                                    + "Enforcer lost hull %.0f armor %.0f, max flux %.0f; last: %s",
                            states, startDist, minDist, maxDist, hullLost, armorLost, maxHardFlux, dealtHull, dealtArmor, threatMaxFlux, lastReason));
                    // HOLD is the standoff; ADVANCE is fine too when its range is also out of the chainguns' reach
                    // (the Brawler's ~600 against 555): the checks below are what matters
                    expect(share("HOLD", "ADVANCE") >= 0.9f, String.format("standoff: outranging a slow enemy -> engages, no back-off (HOLD %.0f%%, ADVANCE %.0f%% of samples)",
                            100f * share("HOLD"), 100f * share("ADVANCE")));
                    expect(share("HOLD") >= 0.3f, String.format("standoff: holds at the standoff part of the time (HOLD %.0f%%)", 100f * share("HOLD")));
                    // chainguns reach 450 + the Enforcer's radius: the Brawler never sat inside that
                    float reach = 450f + (threat == null ? 0f : threat.getCollisionRadius());
                    expect(minDist > reach, String.format("standoff: kept out of the chainguns' reach (closest %.0f, reach %.0f)", minDist, reach));
                    expect(hullLost <= 1f && armorLost <= 1f && maxHardFlux <= 1f,
                            String.format("standoff: takes no damage (hull %.0f, armor %.0f, shield hard flux %.0f)", hullLost, armorLost, maxHardFlux));
                    expect(dealtHull + dealtArmor > 0f || threatMaxFlux > 0f,
                            String.format("standoff: deals damage (hull %.0f, armor %.0f, flux %.0f)", dealtHull, dealtArmor, threatMaxFlux));
                    finish();
                }
                break;
            default:
                break;
        }
    }

    private void reportBackOff(String name, String who) {
        note(String.format("%s: states %s, distance %.0f -> min %.0f max %.0f, %s alive %b; first back-off at %.1fs: %s",
                name, states, startDist, minDist, maxDist, who, p.isAlive(), firstBackOff / 60f, firstBackOffReason));
        // the goal is never being caught out: it may edge in while it can still escape, but must keep its hull.
        // The limit is the default config's maxHullDamageLevelToVent (5% of max hull), the hull limit the rule uses
        float lost = startHull - (p.isAlive() ? p.getHitpoints() : 0f);
        float limit = 0.05f * p.getMaxHitpoints();
        note(String.format("%s: back-off share %.0f%%, hull lost %.0f (limit %.0f)", name,
                100f * share("BACK_OFF", "DISENGAGE", "VENTING"), lost, limit));
        expect(p.isAlive() && lost <= limit, String.format("%s: outgunned and not caught out: hull lost %.0f of limit %.0f", name, lost, limit));
        expect(firstBackOff >= 0 && firstBackOff <= 240, String.format("%s: backs off within 4 s (at %.1fs)", name, firstBackOff / 60f));
        expect(firstBackOffReason != null && (firstBackOffReason.contains("HOLD ovl") || firstBackOffReason.contains("HOLD hull")),
                name + ": the reason is the predicted danger of holding (" + firstBackOffReason + ")");
    }

    private void finish() {
        phase = Phase.DONE;
        org.starficz.combataitweaks.runtime.TraceRecorder.Companion.flushNow(engine);
        log.info("LIVE_CHECK COMPLETE passed=" + Ctx.passed + " failed=" + Ctx.failed);
        System.exit(Ctx.failed == 0 ? 0 : 3);
    }
}
