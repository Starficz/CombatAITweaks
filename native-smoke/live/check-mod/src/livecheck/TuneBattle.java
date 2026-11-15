package livecheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.combat.CombatEngine;

import java.util.List;

// Tuning battles (plan milestone 6): one battle holding rounds of seeded fleet fights, each seed fought once with CAT
// on the player side and once with vanilla AI, so a setting is judged by CAT's effect on the same fights.
// TuneBattleDriver stages the rounds. Both sides only bring an unarmed ox, parked far away, so the battle never ends
// between rounds; the round fleets are spawned by the driver.
public final class TuneBattle {
    private TuneBattle() {
    }

    static final String PARKED = "ox_Standard";

    public static List<Step> steps() {
        return List.of(TuneBattle::start);
    }

    static void start(Ctx c) {
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        player.getCargo().addCrew(20000);
        player.getCargo().addSupplies(5000);
        player.getCargo().addFuel(2000);
        for (FleetMemberAPI m : player.getFleetData().getMembersListCopy()) player.getFleetData().removeFleetMember(m);
        FleetMemberAPI flag = Global.getFactory().createFleetMember(FleetMemberType.SHIP, PARKED);
        player.getFleetData().addFleetMember(flag);
        player.getFleetData().setFlagship(flag);

        CampaignFleetAPI enemy = Global.getFactory().createEmptyFleet(Factions.TRITACHYON, "Tuning enemy", true);
        enemy.getFleetData().addFleetMember(Global.getFactory().createFleetMember(FleetMemberType.SHIP, PARKED));
        for (CampaignFleetAPI f : new CampaignFleetAPI[]{player, enemy}) {
            f.getFleetData().setSyncNeeded();
            f.getFleetData().syncIfNeeded();
        }
        player.getContainingLocation().addEntity(enemy);
        enemy.setLocation(player.getLocation().x + 150f, player.getLocation().y);
        c.note("tuning battle: rounds " + TuneBattleDriver.describeRounds());

        ((CombatEngine) CombatEngine.getInstance()).setShowDeploymentDialog(false);
        Global.getSector().getCampaignUI().startBattle(new BattleCreationContext(player, FleetGoal.ATTACK, enemy, FleetGoal.ATTACK));
        c.note("battle started");
        c.frames(10000000);
    }
}
