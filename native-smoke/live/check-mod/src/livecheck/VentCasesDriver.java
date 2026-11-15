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
//  feeder:    a Hammerhead (Accelerated Ammo Feeder) parked 1500 away, guns and system blocked -> its captured
//             ballistic weapons carry the system's boost (fire rate x2, flux x0.5) and its system is ready now; fired
//             from 600 at the CAT ship for 7 s its ballistics deal clearly more with the system than without; then
//             with the system on, the captured weapons (system-off values) match the idle capture
//  damper:    an own-side Centurion (damper as its ship system), then a Vanguard (damper in the right-click slot),
//             parked 1500 below: idle the defence capture has no system; on, the system carries the live
//             damage-taken mults, the ship's own stay the system-off values, with no shield, no vent and its on-time
//  pursuit:   the aibattle Hound case (plan §14): a CAT Hound (no shield, default limits) at 90% hard flux, a Shrike
//             (Plasma Burn on 1 of 2 charges, Heavy Blaster) 640 away, both free -> any vent CAT calls safe stays
//             within its limits, and a burn on the last charge is captured running (not "out of ammo, never")
public class VentCasesDriver extends BaseEveryFrameCombatPlugin {
    private static final Logger log = Global.getLogger(VentCasesDriver.class);
    private static final float HOUND_PARK_X = -9000f, HOUND_PARK_Y = 9000f, HOUND_X = -6000f, HOUND_Y = 3000f;

    private enum Phase { WARMUP, CALM, CLEAR_1, TORPEDO_ARM, TORPEDO, CLEAR_2, BURN_ARM, BURN, CONTROL, CLEAR_3, DRONE_ARM, DRONE, FEEDER_ARM, FEEDER_ON, DAMPER_ARM, DAMPER_ON, PURSUIT_ARM, PURSUIT, DONE }

    // damper: own-side ships parked 1500 below, the damper as the ship system (Centurion) and in the right-click slot
    // (Vanguard): idle, the capture has no system on; on, its effect goes to the system (the live values) while the
    // ship's own values stay the system-off ones, with the damper's restrictions and its on-time
    private static final String[] DAMPER_VARIANTS = { "centurion_Assault", "vanguard_Attack" };
    private int damperIndex = 0;
    private ShipAPI damperShip;
    private org.starficz.combataitweaks.sim.SimDefense damperIdle;

    private CombatEngineAPI engine;
    private boolean active;
    private int frame = 0, phaseStart = 0;
    private Phase phase = Phase.WARMUP;
    private ShipAPI p, flag, parkedEnemy, jumper, tempest, hound, shrike, feeder;
    private org.starficz.combataitweaks.sim.SimShooter feederIdle;
    private int controlArmedAt = -1;
    private final VentAudit audit = new VentAudit();
    private boolean shrikeBurnedDuringVent = false;
    private float closestDuringVent = Float.MAX_VALUE;
    private String lastChargeBurn = null;
    private boolean lastChargeBurnSeen = false;
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
        return trace(p);
    }

    private static String trace(ShipAPI s) {
        Object t = s.getCustomData().get(VentController.TRACE);
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
                boolean pursued = m.getHullId().startsWith("hound");
                Vector2f at = parked ? new Vector2f(-9000f, -9000f) : pursued ? new Vector2f(HOUND_PARK_X, HOUND_PARK_Y) : new Vector2f(0f, 0f);
                own.spawnFleetMember(m, at, 90f, 0f);
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
                else if (s.getHullSpec().getHullId().startsWith("hound")) hound = s;
                else p = s;
            }
            expect(p != null && flag != null && parkedEnemy != null && hound != null, "ships deployed");
            if (p == null) { finish(); return; }
            if (flag != null) engine.setPlayerShipExternal(flag);
        }
        if (p == null) return;
        pin(p, 0f, 0f);
        // pinned anyway; its AI would otherwise keep firing maneuvering jets, and CAT doesn't vent during a system
        p.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
        pin(flag, -9000f, -9000f);
        pin(parkedEnemy, 9000f, 9000f);
        if (phase.ordinal() < Phase.PURSUIT.ordinal()) {
            if (phase == Phase.PURSUIT_ARM) {
                // the predictor must see the Shrike before the flux rises (as in the other cases): both held a second
                pin(hound, HOUND_X, HOUND_Y);
                if (shrike != null && shrike.isAlive()) {
                    pin(shrike, HOUND_X + 640f, HOUND_Y);
                    shrike.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                    shrike.blockCommandForOneFrame(ShipCommand.FIRE);
                }
            } else pin(hound, HOUND_PARK_X, HOUND_PARK_Y);
            if (hound != null) hound.blockCommandForOneFrame(ShipCommand.VENT_FLUX);
        }
        if (feeder != null && feeder.isAlive()) {
            pin(feeder, 0f, 1500f);
            feeder.blockCommandForOneFrame(ShipCommand.FIRE);
            if (phase == Phase.FEEDER_ARM) feeder.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
        }
        if (damperShip != null && damperShip.isAlive()) {
            pin(damperShip, 0f, -1500f);
            damperShip.blockCommandForOneFrame(ShipCommand.FIRE);
            if (phase == Phase.DAMPER_ARM) {
                damperShip.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                damperShip.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);
            }
        }
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
                    controlArmedAt = -1;
                    enter(Phase.CONTROL);
                }
                break;
            case CONTROL:
                // a vent the jump case ended on finishes first; then the same flux as there
                if (controlArmedAt < 0) {
                    if (!venting) { setFlux(0.5f); controlArmedAt = age(); }
                    break;
                }
                if (catVenting()) {
                    note("control: " + trace());
                    expect(true, "control: with the Hyperion gone CAT vents at the same flux");
                    enter(Phase.CLEAR_3);
                } else if (age() - controlArmedAt > 300) {
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
                    if (tempest != null) engine.removeEntity(tempest);
                    tempest = null;
                    setFlux(0f);
                    feeder = engine.getFleetManager(1).spawnShipOrWing("hammerhead_Elite", new Vector2f(0f, 1500f), 270f);
                    expect(feeder != null && feeder.getSystem() != null
                            && "ammofeed".equals(feeder.getSystem().getSpecAPI().getId()), "feeder: Hammerhead (ammofeed) spawned");
                    enter(Phase.FEEDER_ARM);
                }
                break;
            }
            case FEEDER_ARM:
                if (age() >= 60 && feeder != null) {
                    feederIdle = feederShooter();
                    checkFeederIdle(feederIdle);
                    enter(Phase.FEEDER_ON);
                }
                break;
            case FEEDER_ON: {
                com.fs.starfarer.api.combat.ShipSystemAPI sys = feeder == null ? null : feeder.getSystem();
                // the system was blocked through the arm phase (this frame's block is lifted)
                if (age() == 1 && feeder != null) feeder.useSystem();
                if (sys != null && sys.getState() == com.fs.starfarer.api.combat.ShipSystemAPI.SystemState.ACTIVE) {
                    checkFeederOn(feederIdle, feederShooter());
                    engine.removeEntity(feeder);
                    feeder = null;
                    startDamper(0);
                } else if (age() > 300) {
                    expect(false, "feeder: the system came on when used (state " + (sys == null ? "-" : sys.getState()) + ")");
                    if (feeder != null) engine.removeEntity(feeder);
                    feeder = null;
                    startDamper(0);
                }
                break;
            }
            case DAMPER_ARM:
                if (age() >= 60 && damperShip != null) {
                    damperIdle = damperDefense();
                    String name = DAMPER_VARIANTS[damperIndex];
                    expect(damperIdle != null && damperIdle.getSystem() == null,
                            "damper " + name + ": idle, the capture has no system on");
                    enter(Phase.DAMPER_ON);
                }
                break;
            case DAMPER_ON: {
                com.fs.starfarer.api.combat.ShipSystemAPI sys = damper();
                boolean on = sys != null && sys.getState() == com.fs.starfarer.api.combat.ShipSystemAPI.SystemState.ACTIVE;
                if (!on && sys != null && !sys.isOn() && age() % 10 == 1) {
                    if (damperShip.getShield() != null && damperShip.getShield().isOn()) damperShip.getShield().toggleOff();
                    if (sys == damperShip.getSystem()) damperShip.useSystem();
                    else damperShip.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
                }
                if (on) {
                    checkDamperOn(damperIdle, damperDefense());
                    nextDamper();
                } else if (age() > 300) {
                    expect(false, "damper " + DAMPER_VARIANTS[damperIndex] + ": the damper came on when used (state "
                            + (sys == null ? "no damper" : sys.getState()) + ")");
                    nextDamper();
                }
                break;
            }
            case PURSUIT_ARM:
                // place the Hound and the Shrike for the open: the Hound still parked (pinned) this frame
                if (age() == 1) {
                    hound.getLocation().set(HOUND_X, HOUND_Y);
                    hound.getVelocity().set(0f, 0f);
                    hound.setFacing(0f);
                    shrike = engine.getFleetManager(1).spawnShipOrWing("shrike_Attack", new Vector2f(HOUND_X + 640f, HOUND_Y), 180f);
                    expect(shrike != null, "pursuit: Shrike spawned 640 from the Hound");
                    if (shrike != null) {
                        shrike.setCurrentCR(Math.max(shrike.getCurrentCR(), 0.7f));
                        // as in the aibattle case: Plasma Burn used before, one of its two charges left and the
                        // other regenerating
                        if (shrike.getSystem() != null) {
                            shrike.getSystem().setAmmo(1);
                            shrike.getSystem().setAmmoReloadProgress(0.3f);
                        }
                    }
                }
                if (age() >= 60) {
                    float f = 0.9f * hound.getMaxFlux();
                    hound.getFluxTracker().setCurrFlux(f);
                    hound.getFluxTracker().setHardFlux(f);
                    enter(Phase.PURSUIT);
                }
                break;
            case PURSUIT: {
                audit.track(engine, java.util.Collections.singletonList(hound));
                boolean houndVenting = hound.isAlive() && hound.getFluxTracker().isVenting();
                if (houndVenting && shrike != null && shrike.isAlive()) {
                    if (shrike.getSystem() != null && shrike.getSystem().isOn()) shrikeBurnedDuringVent = true;
                    closestDuringVent = Math.min(closestDuringVent,
                            com.fs.starfarer.api.util.Misc.getDistance(hound.getLocation(), shrike.getLocation()));
                }
                if (age() % 30 == 0 && shrike != null) note("pursuit " + systemState());
                // a burn on the Shrike's last charge: the capture must see it running and the charge coming back
                if (lastChargeBurn == null && shrike != null && shrike.getSystem() != null && shrike.getSystem().isOutOfAmmo()
                        && (shrike.getSystem().getState() == com.fs.starfarer.api.combat.ShipSystemAPI.SystemState.IN
                        || shrike.getSystem().getState() == com.fs.starfarer.api.combat.ShipSystemAPI.SystemState.ACTIVE)) {
                    org.starficz.combataitweaks.sim.SystemInfo c = capturedSystem();
                    lastChargeBurn = systemState();
                    lastChargeBurnSeen = c != null && c.getActiveLeft() > 0f && c.getReadyIn() < 60f;
                }
                if (age() % 60 == 0 && shrike != null) {
                    Object t = hound.getCustomData().get(VentController.TRACE);
                    note(String.format("pursuit %.1fs: gap %.0f, Hound flux %.2f venting %b, Shrike system %s; %s",
                            age() / 60f, com.fs.starfarer.api.util.Misc.getDistance(hound.getLocation(), shrike.getLocation()),
                            hound.getFluxLevel(), houndVenting, shrike.getSystem() == null ? "-" : shrike.getSystem().getState(),
                            t == null ? "(no decision)" : t));
                }
                boolean ventDone = !audit.done.isEmpty();
                if (ventDone || !hound.isAlive() || age() > 60 * 30) {
                    if (audit.done.isEmpty()) {
                        note("pursuit: the Hound didn't vent within 30 s (last decision: " + trace(hound) + ")");
                        expect(true, "pursuit: no vent, nothing to judge");
                    } else {
                        VentAudit.Vent v = audit.done.get(0);
                        note(String.format("pursuit: vent by %s, %s; armor lost %.0f (limit %.0f), hull lost %.0f (limit %.0f); "
                                        + "closest Shrike %.0f, Shrike burned during it %b",
                                v.byCat ? "CAT" : "vanilla", v.reason, v.armorLost, v.armorLimit, v.hullLost, v.hullLimit,
                                closestDuringVent, shrikeBurnedDuringVent));
                        expect(!v.overLimit(), "pursuit: a vent CAT calls safe stays within its limits ("
                                + (v.safe ? "safe" : "not safe") + ", armor " + Math.round(v.armorLost) + "/" + Math.round(v.armorLimit)
                                + ", hull " + Math.round(v.hullLost) + "/" + Math.round(v.hullLimit) + ")");
                    }
                    expect(lastChargeBurn != null && lastChargeBurnSeen,
                            "pursuit: a burn on the last charge is captured running, ready when the charge regenerates ("
                                    + (lastChargeBurn == null ? "no such burn" : lastChargeBurn) + ")");
                    finish();
                }
                break;
            }
            default:
                break;
        }
    }

    private void startDamper(int index) {
        damperIndex = index;
        damperIdle = null;
        String variant = DAMPER_VARIANTS[index];
        damperShip = engine.getFleetManager(0).spawnShipOrWing(variant, new Vector2f(0f, -1500f), 90f);
        if (damperShip != null) damperShip.setCurrentCR(Math.max(damperShip.getCurrentCR(), 0.7f));
        com.fs.starfarer.api.combat.ShipSystemAPI sys = damper();
        expect(sys != null, "damper " + variant + ": spawned with a damper (" + (sys == null ? "none" : sys.getId()) + ")");
        if (sys == null) { nextDamper(); return; }
        enter(Phase.DAMPER_ARM);
    }

    private void nextDamper() {
        if (damperShip != null) engine.removeEntity(damperShip);
        damperShip = null;
        if (damperIndex + 1 < DAMPER_VARIANTS.length) startDamper(damperIndex + 1);
        else enter(Phase.PURSUIT_ARM);
    }

    /** The damper ship's damper: the right-click system or the ship system, as vanilla's DamperFieldStats.getDamper. */
    private com.fs.starfarer.api.combat.ShipSystemAPI damper() {
        if (damperShip == null) return null;
        for (com.fs.starfarer.api.combat.ShipSystemAPI s : new com.fs.starfarer.api.combat.ShipSystemAPI[]{
                damperShip.getPhaseCloak(), damperShip.getSystem()}) {
            if (s != null && s.getId().startsWith("damper")) return s;
        }
        return null;
    }

    private org.starficz.combataitweaks.sim.SimDefense damperDefense() {
        org.starficz.combataitweaks.sim.DamageInputs in = org.starficz.combataitweaks.capture.DamageCaptureKt
                .captureDamage(engine, java.util.Collections.singleton(damperShip.getId()));
        return in.getDefenses().get(damperShip.getId());
    }

    private void checkDamperOn(org.starficz.combataitweaks.sim.SimDefense idle, org.starficz.combataitweaks.sim.SimDefense on) {
        String name = "damper " + DAMPER_VARIANTS[damperIndex];
        expect(idle != null && on != null && idle.getArmor() != null && on.getArmor() != null, name + ": captured off and on");
        if (idle == null || on == null || idle.getArmor() == null || on.getArmor() == null) return;
        org.starficz.combataitweaks.sim.DefenseSystem sys = on.getSystem();
        com.fs.starfarer.api.combat.MutableShipStatsAPI stats = damperShip.getMutableStats();
        float liveArmor = stats.getArmorDamageTakenMult().getModifiedValue(), liveHull = stats.getHullDamageTakenMult().getModifiedValue();
        note(String.format("%s on: live armor x%.2f hull x%.2f; captured off armor x%.2f hull x%.2f (idle x%.2f, x%.2f); system %s",
                name, liveArmor, liveHull, on.getArmor().getArmorDamageTakenMult(), on.getArmor().getHullDamageTakenMult(),
                idle.getArmor().getArmorDamageTakenMult(), idle.getArmor().getHullDamageTakenMult(),
                sys == null ? "none" : String.format("armor x%.2f hull x%.2f onLeft %.2f chargeDown %.2f noShield %b noVent %b noFiring %b",
                        sys.getArmorDamageTakenMult(), sys.getHullDamageTakenMult(), sys.getOnLeft(), sys.getChargeDown(),
                        sys.getNoShield(), sys.getNoVent(), sys.getNoFiring())));
        expect(sys != null && close(sys.getArmorDamageTakenMult(), liveArmor) && close(sys.getHullDamageTakenMult(), liveHull)
                        && liveArmor < 0.5f * idle.getArmor().getArmorDamageTakenMult(),
                name + ": on, the system carries the live damage-taken mults (the damper's cut)");
        expect(close(on.getArmor().getArmorDamageTakenMult(), idle.getArmor().getArmorDamageTakenMult())
                        && close(on.getArmor().getHullDamageTakenMult(), idle.getArmor().getHullDamageTakenMult()),
                name + ": on, the ship's own mults stay the system-off values");
        expect(sys != null && sys.getNoShield() && sys.getNoVent() && sys.getOnLeft() > 0f && sys.getOnLeft() < 3.6f,
                name + ": the damper's restrictions (no shield, no vent) and its on-time");
    }

    private org.starficz.combataitweaks.sim.SimShooter feederShooter() {
        org.starficz.combataitweaks.sim.DamageInputs in = org.starficz.combataitweaks.capture.DamageCaptureKt
                .captureDamage(engine, java.util.Collections.singleton(p.getId()));
        return in.getShooterById().get(feeder.getId());
    }

    private static String weapons(org.starficz.combataitweaks.sim.SimShooter s) {
        StringBuilder b = new StringBuilder();
        for (org.starficz.combataitweaks.sim.SimWeapon w : s.getWeapons()) {
            org.starficz.combataitweaks.sim.WeaponBoost bo = w.getBoost();
            b.append(String.format("[%s dmg %.0f cd %.3f burst %d/%.3f flux %.1f range %.0f boost %s] ", w.getWeaponClass(),
                    w.getDamage(), w.getCooldown(), w.getBurstSize(), w.getBurstDelay(), w.getFluxCost(), w.getRange(),
                    bo == null ? "-" : String.format("rof %.2f dmg %.2f flux %.2f range %.2f", bo.getRof(), bo.getDamage(),
                            bo.getFlux(), bo.getRange())));
        }
        return b.toString();
    }

    // the idle Hammerhead as captured: boosted ballistics, the system ready, and the sim's fire with it stronger
    private void checkFeederIdle(org.starficz.combataitweaks.sim.SimShooter s) {
        expect(s != null, "feeder: the Hammerhead is captured as a shooter");
        if (s == null) return;
        note("feeder idle: " + weapons(s));
        boolean ballisticsBoosted = true, othersPlain = true;
        java.util.List<org.starficz.combataitweaks.sim.SimWeapon> ballistic = new java.util.ArrayList<>();
        for (org.starficz.combataitweaks.sim.SimWeapon w : s.getWeapons()) {
            org.starficz.combataitweaks.sim.WeaponBoost b = w.getBoost();
            if (w.getWeaponClass() == org.starficz.combataitweaks.sim.WeaponClass.BALLISTIC) {
                ballistic.add(w);
                ballisticsBoosted &= b != null && Math.abs(b.getRof() - 2f) < 0.01f && Math.abs(b.getFlux() - 0.5f) < 0.01f;
            } else if (b != null) othersPlain = false;
        }
        expect(!ballistic.isEmpty() && ballisticsBoosted, "feeder: its ballistic weapons carry fire rate x2, flux x0.5");
        expect(othersPlain, "feeder: its other weapons carry no boost");
        org.starficz.combataitweaks.sim.ShooterSystem sys = s.getSystem();
        expect(sys != null && sys.getReadyIn() < 0.01f && sys.getOnLeft() == 0f,
                "feeder: its system is captured ready now and off"
                        + (sys == null ? " (none)" : String.format(" (readyIn %.2f, onLeft %.2f, onTime %.2f, cooldown %.2f)",
                        sys.getReadyIn(), sys.getOnLeft(), sys.getOnTime(), sys.getCooldown())));
        // the sim's fire of its ballistics from 600 at the captured CAT ship over 7 s, with and without the system
        org.starficz.combataitweaks.sim.DamageInputs in = org.starficz.combataitweaks.capture.DamageCaptureKt
                .captureDamage(engine, java.util.Collections.singleton(p.getId()));
        org.starficz.combataitweaks.sim.SimDefense def = in.getDefenses().get(p.getId());
        float with = fired(s, ballistic, sys, def), without = fired(s, ballistic, null, def);
        // the known answer from the captured weapons: bursts back to back, the cooldown at the fire rate, the delay
        // between burst shots no shorter than vanilla's minRefireDelay
        float expectWith = 0f, expectWithout = 0f;
        for (org.starficz.combataitweaks.sim.SimWeapon w : ballistic) {
            expectWith += w.getDamage() * shotsIn(w, w.getBoost().getRof(), 7f);
            expectWithout += w.getDamage() * shotsIn(w, 1f, 7f);
        }
        note(String.format("feeder: ballistic damage over 7 s from 600: %.0f with the system (expected %.0f), %.0f without (%.0f)",
                with, expectWith, without, expectWithout));
        expect(without > 0f && Math.abs(with - expectWith) <= 0.05f * expectWith && Math.abs(without - expectWithout) <= 0.05f * expectWithout
                        && with > without,
                "feeder: the sim's ballistic fire matches the fire cycle at the system's fire rate, and without it");
    }

    // shots a weapon fires in [horizon] s from ready: bursts back to back, each burst's shots refireDelay apart
    private static int shotsIn(org.starficz.combataitweaks.sim.SimWeapon w, float rof, float horizon) {
        float delay = org.starficz.combataitweaks.sim.SystemsKt.refireDelay(w.getBurstDelay(), rof);
        float cooldown = org.starficz.combataitweaks.sim.SystemsKt.refireDelay(w.getCooldown(), rof);
        int shots = 0;
        float t = 0f;
        while (t < horizon) {
            for (int k = 0; k < w.getBurstSize() && t < horizon; k++) {
                shots++;
                if (k < w.getBurstSize() - 1) t += delay;
            }
            t += cooldown;
        }
        return shots;
    }

    private float fired(org.starficz.combataitweaks.sim.SimShooter s, java.util.List<org.starficz.combataitweaks.sim.SimWeapon> weapons,
                        org.starficz.combataitweaks.sim.ShooterSystem sys, org.starficz.combataitweaks.sim.SimDefense def) {
        int steps = 140;
        org.starficz.combataitweaks.sim.Timeline target = new org.starficz.combataitweaks.sim.Timeline(steps);
        org.starficz.combataitweaks.sim.Timeline shooter = new org.starficz.combataitweaks.sim.Timeline(steps);
        for (int i = 0; i < steps; i++) { shooter.getY()[i] = 600f; shooter.getFacing()[i] = 270f; target.getFacing()[i] = 90f; }
        org.starficz.combataitweaks.sim.SimShooter copy = new org.starficz.combataitweaks.sim.SimShooter(s.getShipId(), s.getOwner(),
                0f, 0f, s.getMaxFlux(), s.getDissipation(), s.getTimeMult(), 0f, s.getDamageVsSize(), weapons, null, sys);
        org.starficz.combataitweaks.sim.FireControl fc = new org.starficz.combataitweaks.sim.FireControl(def, p.getOwner(), target, null);
        fc.fire(copy, shooter, null, null, null, false);
        float sum = 0f;
        for (org.starficz.combataitweaks.sim.Hit h : fc.getHits()) sum += h.getDamage();
        return sum;
    }

    // with the system on, the captured weapons are still the system-off values (its part taken out of the live ones)
    private void checkFeederOn(org.starficz.combataitweaks.sim.SimShooter idle, org.starficz.combataitweaks.sim.SimShooter on) {
        expect(idle != null && on != null && idle.getWeapons().size() == on.getWeapons().size(), "feeder: captured on and off");
        if (idle == null || on == null || idle.getWeapons().size() != on.getWeapons().size()) return;
        note("feeder on: " + weapons(on));
        boolean same = true;
        for (int k = 0; k < idle.getWeapons().size(); k++) {
            org.starficz.combataitweaks.sim.SimWeapon a = idle.getWeapons().get(k), b = on.getWeapons().get(k);
            same &= close(a.getCooldown(), b.getCooldown()) && close(a.getBurstDelay(), b.getBurstDelay())
                    && close(a.getFluxCost(), b.getFluxCost()) && close(a.getDamage(), b.getDamage()) && close(a.getRange(), b.getRange());
        }
        expect(same, "feeder: with the system on, the captured weapons match the system-off capture");
        org.starficz.combataitweaks.sim.ShooterSystem sys = on.getSystem();
        expect(sys != null && sys.getOnLeft() > 0.5f && sys.getReadyIn() > sys.getOnLeft(),
                "feeder: the system is captured on, ready again only after its cooldown"
                        + (sys == null ? " (none)" : String.format(" (onLeft %.2f, readyIn %.2f)", sys.getOnLeft(), sys.getReadyIn())));
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= 0.01f * Math.max(1f, Math.abs(a));
    }

    // the Shrike's system live, and as the motion capture gives it to the sim
    private String systemState() {
        com.fs.starfarer.api.combat.ShipSystemAPI sys = shrike.getSystem();
        if (sys == null) return "Shrike has no system";
        org.starficz.combataitweaks.sim.SystemInfo captured = capturedSystem();
        return String.format("%.2fs Shrike system %s ammo %d/%d reload %.2f cooldown %.2f outOfAmmo %b -> captured %s",
                age() / 60f, sys.getState(), sys.getAmmo(), sys.getMaxAmmo(), sys.getAmmoReloadProgress(),
                sys.getCooldownRemaining(), sys.isOutOfAmmo(),
                captured == null ? "none" : String.format("readyIn %.2f burst %.0f active %.2f cooldown %.2f activeLeft %.2f",
                        captured.getReadyIn(), captured.getBurstPotential(), captured.getActiveTime(), captured.getCooldown(),
                        captured.getActiveLeft()));
    }

    private org.starficz.combataitweaks.sim.SystemInfo capturedSystem() {
        for (org.starficz.combataitweaks.sim.SimShip s : org.starficz.combataitweaks.capture.MotionCaptureKt
                .captureMotion(engine, java.util.Collections.singleton(hound.getId())).getShips()) {
            if (s.getId().equals(shrike.getId())) return s.getSystem();
        }
        return null;
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
