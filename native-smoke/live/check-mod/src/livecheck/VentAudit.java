package livecheck;

import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.starficz.combataitweaks.decide.VentChoice;
import org.starficz.combataitweaks.decide.VentController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The vent audit (plan §10): every vent of a CAT ship, who started it (CAT or vanilla), CAT's reason line, and what
// it really cost over the vent window: hull lost and the largest regional armor drop (vanilla's 21-cell armor value,
// in armor-rating units, the same measure the prediction's limits use).
final class VentAudit {
    static final class Vent {
        final String ship, hull, reason;
        final boolean byCat, safe;
        final float start, window, armorLimit, hullLimit;
        final float hullAtStart;
        final float[][] armorAtStart;
        float hullLost = -1f, armorLost = -1f;
        boolean overloaded = false;

        Vent(ShipAPI s, float now, VentChoice c) {
            ship = s.getId();
            hull = s.getHullSpec().getHullId();
            byCat = c != null;
            safe = c != null && c.getSafe();
            reason = c != null ? c.getReason() : "vanilla";
            window = c != null ? c.getWindow() : s.getFluxTracker().getTimeToVent() + 1f;
            armorLimit = c != null ? c.getArmorLimit() : 0f;
            hullLimit = c != null ? c.getHullLimit() : 0f;
            start = now;
            hullAtStart = s.getHitpoints();
            armorAtStart = copy(s.getArmorGrid().getGrid());
        }

        boolean overLimit() {
            return safe && (armorLost > armorLimit + 1f || hullLost > hullLimit + 1f);
        }
    }

    final List<Vent> done = new ArrayList<>();
    private final Map<String, Vent> open = new HashMap<>();
    private final Set<String> wasVenting = new HashSet<>();

    /** Call every frame for the ships to audit. */
    void track(CombatEngineAPI engine, Iterable<ShipAPI> ships) {
        float now = engine.getTotalElapsedTime(false);
        for (ShipAPI s : ships) {
            boolean venting = s.isAlive() && s.getFluxTracker().isVenting();
            if (venting && !wasVenting.contains(s.getId()) && !open.containsKey(s.getId())) {
                Object c = s.getCustomData().get(VentController.ACTIVE_VENT);
                open.put(s.getId(), new Vent(s, now, c instanceof VentChoice ? (VentChoice) c : null));
            }
            if (venting) wasVenting.add(s.getId());
            else wasVenting.remove(s.getId());
            Vent v = open.get(s.getId());
            if (v == null) continue;
            if (s.getFluxTracker().isOverloaded()) v.overloaded = true;
            if (now >= v.start + v.window || !s.isAlive()) {
                v.hullLost = v.hullAtStart - s.getHitpoints();
                v.armorLost = worstRegionalDrop(v.armorAtStart, s.getArmorGrid());
                open.remove(s.getId());
                done.add(v);
            }
        }
    }

    private static float[][] copy(float[][] g) {
        float[][] c = new float[g.length][];
        for (int i = 0; i < g.length; i++) c[i] = g[i].clone();
        return c;
    }

    private static float around(float[][] g, int i, int j) {
        float sum = 0f;
        for (int di = -2; di <= 2; di++) {
            for (int dj = -2; dj <= 2; dj++) {
                if ((di == 2 || di == -2) && (dj == 2 || dj == -2)) continue;
                int ci = i + di, cj = j + dj;
                if (ci < 0 || ci >= g.length || cj < 0 || cj >= g[ci].length) continue;
                float w = (di >= -1 && di <= 1 && dj >= -1 && dj <= 1) ? 1f : 0.5f;
                sum += g[ci][cj] * w;
            }
        }
        return sum;
    }

    static float worstRegionalDrop(float[][] before, ArmorGridAPI grid) {
        float[][] now = grid.getGrid();
        float worst = 0f;
        for (int i = 0; i < now.length; i++) {
            for (int j = 0; j < now[i].length; j++) {
                float d = around(before, i, j) - around(now, i, j);
                if (d > worst) worst = d;
            }
        }
        return worst;
    }

    int count(boolean byCat) {
        int n = 0;
        for (Vent v : done) if (v.byCat == byCat) n++;
        return n;
    }

    int safeOverLimit() {
        int n = 0;
        for (Vent v : done) if (v.overLimit()) n++;
        return n;
    }

    int safeCount() {
        int n = 0;
        for (Vent v : done) if (v.safe) n++;
        return n;
    }

    /** One line per vent, for the run's results. */
    List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Vent v : done) {
            out.add(String.format("vent %-12s %s at %.1f: armor lost %.0f (limit %.0f), hull lost %.0f (limit %.0f)%s%s | %s",
                    v.hull, v.byCat ? (v.safe ? "CAT safe" : "CAT better") : "vanilla", v.start, v.armorLost, v.armorLimit,
                    v.hullLost, v.hullLimit, v.overloaded ? ", OVERLOADED" : "", v.overLimit() ? ", OVER LIMIT" : "", v.reason));
        }
        return out;
    }
}
