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

// Known-answer vent scenarios (plan §10, milestone 3). One CAT ship pinned in place; VentCasesDriver stages each
// scenario in turn inside one battle and checks the vent decision. Both sides also get an unarmed ox parked far away:
// the player's (so the CAT ship isn't the one the player flies, where CAT stands down) and the enemy's (so the
// battle starts and nothing threatens until a scenario spawns it).
public final class VentCases {
    private VentCases() {
    }

    static final String PROTECTED = System.getProperty("cat.vent.protected", "eagle_Assault");
    static final String PARKED = "ox_Standard";
    static final String PURSUED = "hound_hegemony_Standard";
    static final String CONFIG_TAG = "cat_config:{\"maxArmorDamageLevelToVent\":0.02,\"maxHullDamageLevelToVent\":0.01}";

    public static List<Step> steps() {
        return List.of(VentCases::start);
    }

    static void start(Ctx c) {
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        player.getCargo().addCrew(5000);
        player.getCargo().addSupplies(1000);
        player.getCargo().addFuel(1000);
        for (FleetMemberAPI m : player.getFleetData().getMembersListCopy()) player.getFleetData().removeFleetMember(m);

        FleetMemberAPI p = Global.getFactory().createFleetMember(FleetMemberType.SHIP, PROTECTED);
        ShipVariantAPI v = p.getVariant().clone();
        v.addTag(AiBattle.ENABLE_TAG);
        // strict vent limits (what the refit dialog saves as a tag): any predicted armor loss over 2% of the rating
        // forbids a vent, so a threat that can reach the ship during the vent is a clear "no"
        v.addTag(CONFIG_TAG);
        p.setVariant(v, false, true);
        player.getFleetData().addFleetMember(p);
        // the pursuit case's ship: CAT with the default limits, parked until its scenario
        FleetMemberAPI hound = Global.getFactory().createFleetMember(FleetMemberType.SHIP, PURSUED);
        ShipVariantAPI hv = hound.getVariant().clone();
        hv.addTag(AiBattle.ENABLE_TAG);
        hound.setVariant(hv, false, true);
        player.getFleetData().addFleetMember(hound);
        FleetMemberAPI flag = Global.getFactory().createFleetMember(FleetMemberType.SHIP, PARKED);
        player.getFleetData().addFleetMember(flag);
        player.getFleetData().setFlagship(flag);

        CampaignFleetAPI enemy = Global.getFactory().createEmptyFleet(Factions.TRITACHYON, "Vent cases enemy", true);
        enemy.getFleetData().addFleetMember(Global.getFactory().createFleetMember(FleetMemberType.SHIP, PARKED));
        for (CampaignFleetAPI f : new CampaignFleetAPI[]{player, enemy}) {
            f.getFleetData().setSyncNeeded();
            f.getFleetData().syncIfNeeded();
        }
        player.getContainingLocation().addEntity(enemy);
        enemy.setLocation(player.getLocation().x + 150f, player.getLocation().y);
        c.note("vent cases: " + PROTECTED + " (CAT, pinned)");

        ((CombatEngine) CombatEngine.getInstance()).setShowDeploymentDialog(false);
        Global.getSector().getCampaignUI().startBattle(new BattleCreationContext(player, FleetGoal.ATTACK, enemy, FleetGoal.ATTACK));
        c.note("battle started");
        c.frames(1000000);
    }
}
