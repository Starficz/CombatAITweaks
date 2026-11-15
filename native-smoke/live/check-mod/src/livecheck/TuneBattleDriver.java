package livecheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.combat.CombatEngine;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;
import org.starficz.combataitweaks.CombatAIv2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

// Runs inside the battle TuneBattle starts (group "tunebattle"). Each round spawns two seeded fleets, the player's
// with CAT ("cat") or without it ("vanilla"), lets them fight until one side has no ships left or the time cap, and
// writes one "TUNE" line with what each side lost; then clears the field for the next round. tools/tune_report.py
// pairs the rounds of each seed and sums CAT's effect.
//
// Settings (java_args in project.sh): cat.tune.seeds (comma separated, default 1,2,3), cat.tune.modes (default
// cat,vanilla), cat.tune.fp (fleet points per side, default 60), cat.tune.seconds (time cap per round, default 240).
public class TuneBattleDriver extends BaseEveryFrameCombatPlugin {
    private static final Logger log = Global.getLogger(TuneBattleDriver.class);

    static final String[] PLAYER_FACTIONS = {Factions.HEGEMONY, Factions.PERSEAN, Factions.INDEPENDENT, Factions.DIKTAT,
            Factions.LUDDIC_CHURCH};
    static final String[] ENEMY_FACTIONS = {Factions.TRITACHYON, Factions.PIRATES, Factions.HEGEMONY, Factions.PERSEAN,
            Factions.LUDDIC_CHURCH};
    static final int CLEAR_FRAMES = 120;
    static final float PARK = 9000f;

    private static final class Round {
        final int seed;
        final boolean cat;

        Round(int seed, boolean cat) {
            this.seed = seed;
            this.cat = cat;
        }
    }

    /** One spawned ship's starting numbers. */
    private static final class Entry {
        final ShipAPI ship;
        final int owner;
        final float fp, maxHull, armor;

        Entry(ShipAPI ship, int owner, float fp, float maxHull, float armor) {
            this.ship = ship;
            this.owner = owner;
            this.fp = fp;
            this.maxHull = maxHull;
            this.armor = armor;
        }
    }

    private CombatEngineAPI engine;
    private boolean active;
    private int frame = 0, roundStart = 0, clearUntil = -1, roundIndex = -1;
    private final List<Round> rounds = rounds();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> catAttached = new HashMap<>();
    private ShipAPI parked0, parked1;
    private String enemyFaction, playerFaction, composition;

    static void note(String s) {
        log.info("LIVE_CHECK note: " + s);
    }

    static void expect(boolean ok, String id) {
        if (ok) Ctx.passed++;
        else Ctx.failed++;
        log.info("LIVE_CHECK " + (ok ? "ok" : "FAIL") + ": tunebattle / " + id);
    }

    /** This game's tunebattle group: "tunebattle" is slice 1, "tunebattleK" slice K; 0 when none runs. */
    static int slice() {
        for (String g : System.getProperty("live.check", "").split(",")) {
            g = g.trim();
            if (g.equals("tunebattle")) return 1;
            if (g.startsWith("tunebattle")) {
                try { return Integer.parseInt(g.substring("tunebattle".length())); } catch (NumberFormatException ignored) { }
            }
        }
        return 0;
    }

    /** The rounds of this game: its slice of the seeds (cat.tune.slices games share them), each in every mode. */
    static List<Round> rounds() {
        List<Round> list = new ArrayList<>();
        String[] modes = System.getProperty("cat.tune.modes", "cat,vanilla").split(",");
        int slices = Math.max(1, Integer.getInteger("cat.tune.slices", 1));
        int slice = Math.max(1, slice());
        int k = 0;
        for (String s : System.getProperty("cat.tune.seeds", "1,2,3").split(",")) {
            if (s.trim().isEmpty()) continue;
            int seed = Integer.parseInt(s.trim());
            if (k++ % slices != slice - 1) continue;
            for (String m : modes) list.add(new Round(seed, m.trim().equals("cat")));
        }
        return list;
    }

    static String describeRounds() {
        StringBuilder b = new StringBuilder();
        for (Round r : rounds()) b.append(r.seed).append(r.cat ? "/cat " : "/vanilla ");
        return b.toString().trim();
    }

    private static float fpPerSide() {
        return Float.parseFloat(System.getProperty("cat.tune.fp", "60"));
    }

    private static int capFrames() {
        return Math.round(Float.parseFloat(System.getProperty("cat.tune.seconds", "240")) * 60f);
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        String groups = System.getProperty("live.check", "");
        active = slice() > 0 && !engine.isSimulation() && engine.isInCampaign();
        if (!active) return;
        ((CombatEngine) engine).setShowDeploymentDialog(false);
        note("CAT tuning battle driver active: " + rounds.size() + " rounds");
        note("TUNE settings " + org.starficz.combataitweaks.sim.Tuning.INSTANCE.describe());
    }

    private static boolean isCombatShip(ShipAPI s) {
        return s.isAlive() && !s.isHulk() && !s.isFighter() && !s.isStationModule() && !s.isShuttlePod();
    }

    private static void pin(ShipAPI s, float x, float y) {
        if (s == null || !s.isAlive()) return;
        s.getLocation().set(x, y);
        s.getVelocity().set(0f, 0f);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!active) return;
        frame++;
        if (frame == 2) {
            engine.setDoNotEndCombat(true);
            for (int owner = 0; owner < 2; owner++) {
                for (FleetMemberAPI m : engine.getFleetManager(owner).getReservesCopy()) {
                    ShipAPI s = engine.getFleetManager(owner).spawnFleetMember(m,
                            new Vector2f(owner == 0 ? -PARK : PARK, owner == 0 ? -PARK : PARK), 90f, 0f);
                    if (owner == 0) parked0 = s; else parked1 = s;
                }
            }
        }
        if (frame < 3) return;
        if (engine.isPaused()) engine.setPaused(false);
        pin(parked0, -PARK, -PARK);
        pin(parked1, PARK, PARK);

        if (roundIndex < 0 || (clearUntil >= 0 && frame >= clearUntil)) {
            clearUntil = -1;
            roundIndex++;
            if (roundIndex >= rounds.size()) { finish(); return; }
            startRound(rounds.get(roundIndex));
            return;
        }
        if (clearUntil >= 0) { clearField(); return; }

        Round r = rounds.get(roundIndex);
        if (r.cat && frame % 30 == 0) {
            for (Entry e : entries) {
                if (e.owner == 0 && isCombatShip(e.ship) && e.ship.hasListenerOfClass(CombatAIv2.class)) {
                    catAttached.merge(r.seed + "/cat", 1, Integer::sum);
                    break;
                }
            }
        }
        if (frame % 30 != 0) return;
        int alive0 = 0, alive1 = 0;
        for (Entry e : entries) {
            if (!isCombatShip(e.ship) || e.ship.isRetreating()) continue;
            if (e.owner == 0) alive0++; else alive1++;
        }
        if (alive0 == 0 || alive1 == 0 || frame - roundStart >= capFrames()) {
            report(r, alive0, alive1);
            clearUntil = frame + CLEAR_FRAMES;
            clearField();
        }
    }

    private void startRound(Round r) {
        Random pick = new Random(r.seed * 7919L);
        playerFaction = PLAYER_FACTIONS[pick.nextInt(PLAYER_FACTIONS.length)];
        enemyFaction = ENEMY_FACTIONS[pick.nextInt(ENEMY_FACTIONS.length)];
        entries.clear();
        List<FleetMemberAPI> own = fleet(playerFaction, new Random(r.seed * 2L + 1L));
        List<FleetMemberAPI> foe = fleet(enemyFaction, new Random(r.seed * 2L + 2L));
        StringBuilder comp = new StringBuilder();
        for (FleetMemberAPI m : own) comp.append(m.getVariant().getHullVariantId()).append(' ');
        comp.append("vs ");
        for (FleetMemberAPI m : foe) comp.append(m.getVariant().getHullVariantId()).append(' ');
        composition = comp.toString().trim();
        spawn(own, 0, -3000f, 90f, r.cat);
        spawn(foe, 1, 3000f, 270f, false);
        roundStart = frame;
        note(String.format("round %d: seed %d %s, %s vs %s: %s", roundIndex + 1, r.seed, r.cat ? "cat" : "vanilla",
                playerFaction, enemyFaction, composition));
    }

    private List<FleetMemberAPI> fleet(String faction, Random random) {
        FleetParamsV3 p = new FleetParamsV3(null, null, faction, 1f, FleetTypes.PATROL_LARGE, fpPerSide(), 0f, 0f, 0f, 0f, 0f, 0f);
        p.ignoreMarketFleetSizeMult = true;
        p.random = random;
        CampaignFleetAPI made = FleetFactoryV3.createFleet(p);
        List<FleetMemberAPI> members = new ArrayList<>();
        for (FleetMemberAPI m : made.getFleetData().getMembersListCopy()) if (!m.isFighterWing()) members.add(m);
        return members;
    }

    private void spawn(List<FleetMemberAPI> members, int owner, float y, float facing, boolean cat) {
        int i = 0;
        for (FleetMemberAPI m : members) {
            if (cat) {
                ShipVariantAPI v = m.getVariant().clone();
                v.addTag(AiBattle.ENABLE_TAG);
                m.setVariant(v, false, true);
            }
            m.setOwner(owner);
            float row = (i / 20) * 600f;
            Vector2f at = new Vector2f(((i % 20) - members.size() / 2f) * 450f, y + (owner == 0 ? -row : row));
            ShipAPI s = engine.getFleetManager(owner).spawnFleetMember(m, at, facing, 0f);
            i++;
            if (s == null) continue;
            s.setCurrentCR(Math.max(s.getCurrentCR(), 0.7f));
            entries.add(new Entry(s, owner, m.getFleetPointCost(), s.getMaxHitpoints(), armor(s)));
        }
    }

    private static float armor(ShipAPI s) {
        if (s.getArmorGrid() == null) return 0f;
        float sum = 0f;
        for (float[] col : s.getArmorGrid().getGrid()) for (float c : col) sum += c;
        return sum;
    }

    /**
     * One line per round: per side, FP and ships fielded and lost, and the FP-weighted share of hull and armor lost
     * (a destroyed ship counts as all of it).
     */
    private void report(Round r, int alive0, int alive1) {
        float[] fp = new float[2], fpLost = new float[2], hullLost = new float[2], armorLost = new float[2];
        int[] ships = new int[2], lost = new int[2], retreated = new int[2];
        for (Entry e : entries) {
            int o = e.owner;
            fp[o] += e.fp;
            ships[o]++;
            boolean dead = !e.ship.isAlive() || e.ship.isHulk() || !engine.isEntityInPlay(e.ship);
            if (e.ship.isRetreating() && !dead) retreated[o]++;
            if (dead) {
                lost[o]++;
                fpLost[o] += e.fp;
                hullLost[o] += e.fp;
                armorLost[o] += e.fp;
            } else {
                hullLost[o] += e.fp * (1f - e.ship.getHitpoints() / Math.max(1f, e.maxHull));
                if (e.armor > 0f) armorLost[o] += e.fp * (1f - armor(e.ship) / e.armor);
            }
        }
        float seconds = (frame - roundStart) / 60f;
        String winner = alive1 == 0 && alive0 > 0 ? "player" : alive0 == 0 && alive1 > 0 ? "enemy" : "none";
        note(String.format("TUNE seed=%d mode=%s time=%.0f winner=%s own=%s/%d ships %.0f fp lost=%d ships %.0f fp hull=%.3f armor=%.3f retreated=%d"
                        + " enemy=%s/%d ships %.0f fp lost=%d ships %.0f fp hull=%.3f armor=%.3f retreated=%d fleets=%s",
                r.seed, r.cat ? "cat" : "vanilla", seconds, winner,
                playerFaction, ships[0], fp[0], lost[0], fpLost[0], hullLost[0] / Math.max(1f, fp[0]), armorLost[0] / Math.max(1f, fp[0]), retreated[0],
                enemyFaction, ships[1], fp[1], lost[1], fpLost[1], hullLost[1] / Math.max(1f, fp[1]), armorLost[1] / Math.max(1f, fp[1]), retreated[1],
                composition.replace(' ', '+')));
        expect(ships[0] > 0 && ships[1] > 0, String.format("round %d (seed %d %s): both fleets fielded", roundIndex + 1, r.seed,
                r.cat ? "cat" : "vanilla"));
        if (r.cat) {
            expect(catAttached.getOrDefault(r.seed + "/cat", 0) > 0,
                    String.format("round %d (seed %d cat): CAT ran on the player ships", roundIndex + 1, r.seed));
        }
    }

    /** Removes every ship but the two parked ones, wrecks and fighters included, and everything in flight. */
    private void clearField() {
        for (ShipAPI s : new ArrayList<>(engine.getShips())) {
            if (s == parked0 || s == parked1) continue;
            engine.removeEntity(s);
        }
        for (CombatEntityAPI m : new ArrayList<>(engine.getMissiles())) engine.removeEntity(m);
        for (CombatEntityAPI p : new ArrayList<>(engine.getProjectiles())) engine.removeEntity(p);
    }

    private void finish() {
        active = false;
        org.starficz.combataitweaks.runtime.TraceRecorder.Companion.flushNow(engine);
        log.info("LIVE_CHECK COMPLETE passed=" + Ctx.passed + " failed=" + Ctx.failed);
        System.exit(Ctx.failed == 0 ? 0 : 3);
    }
}
