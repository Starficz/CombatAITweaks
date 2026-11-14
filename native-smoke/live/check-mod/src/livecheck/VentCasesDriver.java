package livecheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.combat.CombatEngine;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;
import org.starficz.combataitweaks.decide.VentChoice;
import org.starficz.combataitweaks.decide.VentController;

import java.util.Arrays;
import java.util.List;

// Runs inside the battle VentCases starts (group "ventcases"). The CAT ship is pinned at the origin; each scenario
// sets its flux, spawns the threat, and checks the vent decision:
//  calm:      high flux, nothing near             -> CAT vents
//  torpedo:   a Reaper torpedo inbound            -> no vent until it's gone
//  jump:      a Hyperion (phase teleporter, 1000 jump) at 2000 -> no vent at first: it can jump in and fire
//             during the vent (a vent may follow once dissipation shrinks the window enough)
//             (A burn-drive Dominator was the first idea, but it isn't a clean known answer: at 2000 it can't close
//             within the Eagle's ~3.7 s vent window, and at 1500 it only needs ~200 units to its hold range, so it
//             creeps in without burning, like vanilla's burn drive AI.)
//  control:   the Hyperion removed, same flux     -> CAT vents (the restraint was the threat, not a broken vent)
//  drone:     a Tempest 1300 away, guns blocked, its drone strike system free -> the prediction has the system's
//             1200-damage hit from the Tempest itself, and CAT doesn't vent
public class VentCasesDriver extends BaseEveryFrameCombatPlugin {
    private static final Logger log = Global.getLogger(VentCasesDriver.class);

    private enum Phase { WARMUP, CALM, CLEAR_1, TORPEDO_ARM, TORPEDO, CLEAR_2, BURN_ARM, BURN, CONTROL, CLEAR_3, DRONE_ARM, DRONE, DONE }

    private CombatEngineAPI engine;
    private boolean active;
    private int frame = 0, phaseStart = 0;
    private Phase phase = Phase.WARMUP;
    private ShipAPI p, flag, parkedEnemy, jumper, tempest;
    private boolean sawSystemHit = false;
    private CombatEntityAPI torpedo;
    private boolean ventedDuring = false, sawUnsafe = false;
    private String ventedTrace = null;

    static void note(String s) {
        log.info("LIVE_CHECK note: " + s);
    }

    static void expect(boolean ok, String id) {
        if (ok) Ctx.passed++;
        else Ctx.failed++;
        log.info("LIVE_CHECK " + (ok ? "ok" : "FAIL") + ": ventcases / " + id);
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        String groups = System.getProperty("live.check", "");
        active = Arrays.asList(groups.split(",")).contains("ventcases") && !engine.isSimulation() && engine.isInCampaign();
        if (!active) return;
        ((CombatEngine) engine).setShowDeploymentDialog(false);
        note("CAT vent cases driver active");
    }

    private static void pin(ShipAPI s, float x, float y) {
        if (s == null || !s.isAlive()) return;
        s.getLocation().set(x, y);
        s.getVelocity().set(0f, 0f);
    }

    private void setFlux(float level) {
        float f = level * p.getMaxFlux();
        p.getFluxTracker().setCurrFlux(f);
        p.getFluxTracker().setHardFlux(f);
    }

    private boolean catVenting() {
        return p.getFluxTracker().isVenting() && p.getCustomData().get(VentController.ACTIVE_VENT) instanceof VentChoice;
    }

    private String trace() {
        Object t = p.getCustomData().get(VentController.TRACE);
        return t == null ? "(no decision)" : t.toString();
    }

    private void enter(Phase next) {
        phase = next;
        phaseStart = frame;
        ventedDuring = false;
        sawUnsafe = false;
        ventedTrace = null;
    }

    private int age() {
        return frame - phaseStart;
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
                own.spawnFleetMember(m, parked ? new Vector2f(-9000f, -9000f) : new Vector2f(0f, 0f), 90f, 0f);
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
                else if (s.getHullSpec().getHullId().startsWith("ox")) flag = s;
                else p = s;
            }
            expect(p != null && flag != null && parkedEnemy != null, "ships deployed");
            if (p == null) { finish(); return; }
            if (flag != null) engine.setPlayerShipExternal(flag);
        }
        if (p == null) return;
        pin(p, 0f, 0f);
        // pinned anyway; its AI would otherwise keep firing maneuvering jets, and CAT doesn't vent during a system
        p.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
        pin(flag, -9000f, -9000f);
        pin(parkedEnemy, 9000f, 9000f);
        if (tempest != null && tempest.isAlive()) {
            pin(tempest, 0f, 1300f);
            tempest.blockCommandForOneFrame(ShipCommand.FIRE);
        }
        if (jumper != null && jumper.isAlive()) {
            pin(jumper, 0f, 2000f);
            jumper.blockCommandForOneFrame(ShipCommand.FIRE);
            jumper.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
        }
        boolean venting = p.getFluxTracker().isVenting();

        switch (phase) {
            case WARMUP:
                if (frame >= 240) { setFlux(0.7f); enter(Phase.CALM); }
                break;
            case CALM:
                if (catVenting()) {
                    note("calm: " + trace());
                    expect(true, "calm: CAT vents at 70% flux with nothing near");
                    enter(Phase.CLEAR_1);
                } else if (age() > 300) {
                    expect(false, "calm: CAT vents at 70% flux with nothing near (last decision: " + trace() + ")");
                    setFlux(0f);
                    enter(Phase.CLEAR_1);
                }
                break;
            case CLEAR_1:
                if (!venting && p.getFluxLevel() < 0.05f) {
                    // the torpedo first, flux later: the decision must already see it when the flux rises
                    torpedo = engine.spawnProjectile(parkedEnemy, null, "reaper", new Vector2f(0f, 1100f), 270f, null);
                    expect(torpedo != null, "torpedo spawned");
                    enter(Phase.TORPEDO_ARM);
                }
                break;
            case TORPEDO_ARM:
                if (age() >= 20) { setFlux(0.5f); enter(Phase.TORPEDO); }
                break;
            case TORPEDO: {
                if (venting && !ventedDuring) { ventedDuring = true; ventedTrace = trace(); }
                if (trace().startsWith("no vent: unsafe")) sawUnsafe = true;
                if (age() == 30) {
                    note("torpedo: decision while inbound: " + trace());
                    note("torpedo: " + diagnose());
                }
                boolean gone = torpedo == null || !engine.isEntityInPlay(torpedo);
                if (gone || age() > 420) {
                    expect(!ventedDuring, "torpedo: no vent while a Reaper torpedo is inbound"
                            + (ventedDuring ? " (vented: " + ventedTrace + ")" : ""));
                    expect(sawUnsafe, "torpedo: the reason was the predicted danger (\"no vent: unsafe\")");
                    setFlux(0f);
                    enter(Phase.CLEAR_2);
                }
                break;
            }
            case CLEAR_2:
                if (!venting && p.getFluxLevel() < 0.05f && age() > 60) {
                    jumper = engine.getFleetManager(1).spawnShipOrWing("hyperion_Attack", new Vector2f(0f, 2000f), 270f);
                    expect(jumper != null, "Hyperion (phase teleporter) spawned at 2000");
                    enter(Phase.BURN_ARM);
                }
                break;
            case BURN_ARM:
                if (age() >= 60) { setFlux(0.5f); enter(Phase.BURN); }
                break;
            case BURN:
                if (venting && !ventedDuring) { ventedDuring = true; ventedTrace = trace(); }
                if (trace().startsWith("no vent: unsafe")) sawUnsafe = true;
                if (age() == 30) note("jump: decision with the Hyperion at 2000: " + trace());
                if (age() >= 240) {
                    // the vent window shrinks as flux dissipates, so a vent may become safe while waiting: the threat
                    // must stop it at first, and any vent must come only once the predicted loss is within the limits
                    expect(sawUnsafe, "jump: the teleporter's threat stops the vent (\"no vent: unsafe\" seen)");
                    expect(!ventedDuring || (ventedTrace != null && ventedTrace.startsWith("VENT safe")),
                            "jump: any vent came only once the predicted loss was within the limits"
                            + (ventedDuring ? " (vented: " + ventedTrace + ")" : " (no vent)"));
                    if (jumper != null) engine.removeEntity(jumper);
                    jumper = null;
                    if (!venting) setFlux(0.5f);
                    enter(Phase.CONTROL);
                }
                break;
            case CONTROL:
                if (catVenting()) {
                    note("control: " + trace());
                    expect(true, "control: with the Hyperion gone CAT vents at the same flux");
                    enter(Phase.CLEAR_3);
                } else if (age() > 300) {
                    expect(false, "control: with the Hyperion gone CAT vents at the same flux (last decision: " + trace() + ")");
                    setFlux(0f);
                    enter(Phase.CLEAR_3);
                }
                break;
            case CLEAR_3:
                if (!venting && p.getFluxLevel() < 0.05f && age() > 60) {
                    tempest = engine.getFleetManager(1).spawnShipOrWing("tempest_Attack", new Vector2f(0f, 1300f), 270f);
                    expect(tempest != null, "Tempest (drone strike) spawned at 1300");
                    enter(Phase.DRONE_ARM);
                }
                break;
            case DRONE_ARM:
                if (age() >= 60) { setFlux(0.5f); sawSystemHit = false; enter(Phase.DRONE); }
                break;
            case DRONE: {
                if (venting && !ventedDuring) { ventedDuring = true; ventedTrace = trace(); }
                if (trace().startsWith("no vent: unsafe")) sawUnsafe = true;
                if (!sawSystemHit && systemHitPredicted()) sawSystemHit = true;
                if (age() == 30) note("drone: decision with the Tempest at 1300: " + trace());
                if (age() >= 180) {
                    expect(sawSystemHit, "drone: the prediction has the drone strike's 1200 hit from the Tempest");
                    expect(!ventedDuring || (ventedTrace != null && ventedTrace.startsWith("VENT safe")),
                            "drone: no vent into the drone strike" + (ventedDuring ? " (vented: " + ventedTrace + ")" : ""));
                    finish();
                }
                break;
            }
            default:
                break;
        }
    }

    // a hit of the system's threat damage (1200) from the Tempest itself (not its drones) in the hold prediction
    private boolean systemHitPredicted() {
        org.starficz.combataitweaks.predictor.RequestResult hold =
                org.starficz.combataitweaks.predictor.PredictorManager.Companion.getInstance(engine).latestHoldResult(p.getId());
        if (hold == null || hold.getOutlook().getResult() == null || tempest == null) return false;
        for (org.starficz.combataitweaks.sim.Hit h : hold.getOutlook().getResult().getHits()) {
            if (tempest.getId().equals(h.getSourceId()) && h.getDamage() >= 1100f) return true;
        }
        return false;
    }

    // what the capture sees of the torpedo, and what the latest hold prediction makes of it
    private String diagnose() {
        StringBuilder b = new StringBuilder();
        if (torpedo instanceof com.fs.starfarer.api.combat.MissileAPI) {
            com.fs.starfarer.api.combat.MissileAPI m = (com.fs.starfarer.api.combat.MissileAPI) torpedo;
            b.append(String.format("missile owner %d at (%.0f, %.0f) v (%.0f, %.0f) facing %.0f, turn %.0f, flight %.2f / %.2f, fading %b, expired %b, ai %s",
                    m.getOwner(), m.getLocation().x, m.getLocation().y, m.getVelocity().x, m.getVelocity().y, m.getFacing(),
                    m.getMaxTurnRate(), m.getFlightTime(), m.getMaxFlightTime(), m.isFading(), m.isExpired(),
                    m.getMissileAI() == null ? "null" : m.getMissileAI().getClass().getSimpleName()));
            b.append(", in engine missiles: ").append(engine.getMissiles().contains(m));
            b.append(", in engine projectiles: ").append(engine.getProjectiles().contains(m));
        } else b.append("torpedo is ").append(torpedo == null ? "null" : torpedo.getClass().getName());
        org.starficz.combataitweaks.predictor.RequestResult hold =
                org.starficz.combataitweaks.predictor.PredictorManager.Companion.getInstance(engine).latestHoldResult(p.getId());
        if (hold != null && hold.getOutlook().getResult() != null) {
            int n = 0;
            float dmg = 0f;
            for (org.starficz.combataitweaks.sim.Hit h : hold.getOutlook().getResult().getHits()) { n++; dmg += h.getDamage(); }
            b.append(String.format("; hold prediction: %d hits, %.0f damage", n, dmg));
        } else b.append("; no hold prediction");
        return b.toString();
    }

    private void finish() {
        phase = Phase.DONE;
        org.starficz.combataitweaks.runtime.TraceRecorder.Companion.flushNow(engine);
        log.info("LIVE_CHECK COMPLETE passed=" + Ctx.passed + " failed=" + Ctx.failed);
        System.exit(Ctx.failed == 0 ? 0 : 3);
    }
}
