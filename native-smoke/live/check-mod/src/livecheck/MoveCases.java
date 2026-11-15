package livecheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.combat.CombatEngine;

import java.util.List;

// Known-answer movement scenarios (plan §10, milestone 4). Two CAT ships: a Brawler (maneuvering jets) and a Warden
// (automated: vanilla never backs it off outside a vent, so CAT must move it directly). MoveCasesDriver stages each
// scenario in turn inside one battle. Both sides also get an unarmed ox parked far away, as in VentCases.
public final class MoveCases {
    private MoveCases() {
    }

    static final String FRIGATE = System.getProperty("cat.move.frigate", "brawler_Assault");
    static final String AUTOMATED = System.getProperty("cat.move.automated", "warden_Defense");
    /** A frigate with the damper as its ship system (plan §11b: CAT decides it). */
    static final String DAMPER = System.getProperty("cat.move.damper", "centurion_Assault");
    static final String PARKED = "ox_Standard";

    public static List<Step> steps() {
        return List.of(MoveCases::start);
    }

    static void start(Ctx c) {
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        player.getCargo().addCrew(5000);
        player.getCargo().addSupplies(1000);
        player.getCargo().addFuel(1000);
        for (FleetMemberAPI m : player.getFleetData().getMembersListCopy()) player.getFleetData().removeFleetMember(m);

        for (String variant : new String[]{FRIGATE, AUTOMATED, DAMPER}) {
            FleetMemberAPI p = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variant);
            ShipVariantAPI v = p.getVariant().clone();
            v.addTag(AiBattle.ENABLE_TAG);
            p.setVariant(v, false, true);
            player.getFleetData().addFleetMember(p);
        }
        FleetMemberAPI flag = Global.getFactory().createFleetMember(FleetMemberType.SHIP, PARKED);
        player.getFleetData().addFleetMember(flag);
        player.getFleetData().setFlagship(flag);

        CampaignFleetAPI enemy = Global.getFactory().createEmptyFleet(Factions.HEGEMONY, "Move cases enemy", true);
        enemy.getFleetData().addFleetMember(Global.getFactory().createFleetMember(FleetMemberType.SHIP, PARKED));
        for (CampaignFleetAPI f : new CampaignFleetAPI[]{player, enemy}) {
            f.getFleetData().setSyncNeeded();
            f.getFleetData().syncIfNeeded();
        }
        player.getContainingLocation().addEntity(enemy);
        enemy.setLocation(player.getLocation().x + 150f, player.getLocation().y);
        c.note("move cases: " + FRIGATE + " and " + AUTOMATED + " (CAT)");

        ((CombatEngine) CombatEngine.getInstance()).setShowDeploymentDialog(false);
        Global.getSector().getCampaignUI().startBattle(new BattleCreationContext(player, FleetGoal.ATTACK, enemy, FleetGoal.ATTACK));
        c.note("battle started");
        c.frames(1000000);
    }
}
