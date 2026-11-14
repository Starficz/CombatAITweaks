package livecheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.combat.CombatEngine;

import java.util.List;

// "Worst case made real" (plan §10): one CAT ship held in place against reckless enemies that want to kill it, so the
// prediction's assumption (every enemy commits) is what really happens. WorstCaseDriver compares the predicted damage
// of the CAT ship's hold branch with the damage it really takes, window by window.
public final class WorstCase {
    private WorstCase() {
    }

    // the protected ship: a sturdy cruiser that lasts long enough to collect many windows
    static final String PROTECTED = System.getProperty("cat.worst.protected", "dominator_Assault");
    static final String FLAGSHIP = "ox_Standard";
    // a mix of guns, beams, DEM and guided missiles, all with reckless captains
    static final String[] ENEMIES = System.getProperty("cat.worst.enemies",
            "hammerhead_DEM,enforcer_Assault,medusa_Attack").split(",");

    public static List<Step> steps() {
        return List.of(WorstCase::start);
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
        p.setVariant(v, false, true);
        player.getFleetData().addFleetMember(p);
        // the player's own ship, parked far away: CAT stands down on the ship the player flies, so P must not be it
        FleetMemberAPI flag = Global.getFactory().createFleetMember(FleetMemberType.SHIP, FLAGSHIP);
        player.getFleetData().addFleetMember(flag);
        player.getFleetData().setFlagship(flag);

        CampaignFleetAPI enemy = Global.getFactory().createEmptyFleet(Factions.TRITACHYON, "Worst case enemy", true);
        for (String id : ENEMIES) {
            FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, id.trim());
            PersonAPI captain = Global.getFactory().createPerson();
            captain.setPersonality(Personalities.RECKLESS);
            m.setCaptain(captain);
            enemy.getFleetData().addFleetMember(m);
        }
        for (CampaignFleetAPI f : new CampaignFleetAPI[]{player, enemy}) {
            f.getFleetData().setSyncNeeded();
            f.getFleetData().syncIfNeeded();
        }
        player.getContainingLocation().addEntity(enemy);
        enemy.setLocation(player.getLocation().x + 150f, player.getLocation().y);
        c.note("worst case: " + PROTECTED + " (CAT, held in place) vs reckless " + String.join(", ", ENEMIES));
        c.expect(player.getFleetData().getNumMembers() == 2 && enemy.getFleetData().getNumMembers() == ENEMIES.length,
                "fleets built: " + player.getFleetData().getNumMembers() + " vs " + enemy.getFleetData().getNumMembers());

        ((CombatEngine) CombatEngine.getInstance()).setShowDeploymentDialog(false);
        Global.getSector().getCampaignUI().startBattle(new BattleCreationContext(player, FleetGoal.ATTACK, enemy, FleetGoal.ATTACK));
        c.note("battle started");
        c.frames(1000000);
    }
}
