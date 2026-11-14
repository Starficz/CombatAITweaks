package livecheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.combat.CombatEngine;

import java.util.List;

// Starts a real battle from the campaign (real CombatState, settings.json combat plugins, so CAT's own
// CATCombatPlugin attaches the AI exactly as in a player's game). Every player ship gets the CAT AI tag; the enemy
// keeps vanilla AI. AiBattleDriver samples the AI inside combat and ends the run.
public final class AiBattle {
    private AiBattle() {
    }

    static final String PLAYER_FACTION = Factions.HEGEMONY;
    static final String ENEMY_FACTION = Factions.TRITACHYON;
    // Battle size per side, from java_args in project.sh (CAT_BATTLE_FLEETS / CAT_BATTLE_FP). The default is a small
    // battle for a laptop; for a stress run use e.g. CAT_BATTLE_FLEETS=2 CAT_BATTLE_FP=250.
    static final int FLEETS_PER_SIDE = Integer.getInteger("cat.battle.fleets", 1);
    static final float FLEET_POINTS = Float.parseFloat(System.getProperty("cat.battle.fp", "60"));
    // ConfigDialog.ENABLE_CAT_AI_TAG
    static final String ENABLE_TAG = "cat_custom_ai_enabled";

    public static List<Step> steps() {
        return List.of(AiBattle::start);
    }

    static void fill(CampaignFleetAPI into, String faction) {
        for (int i = 0; i < FLEETS_PER_SIDE; i++) {
            FleetParamsV3 p = new FleetParamsV3(null, null, faction, 1f, FleetTypes.PATROL_LARGE, FLEET_POINTS, 0f, 0f, 0f, 0f, 0f, 0f);
            p.ignoreMarketFleetSizeMult = true;
            CampaignFleetAPI made = FleetFactoryV3.createFleet(p);
            for (FleetMemberAPI m : made.getFleetData().getMembersListCopy()) {
                made.getFleetData().removeFleetMember(m);
                into.getFleetData().addFleetMember(m);
            }
        }
    }

    static void start(Ctx c) {
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        player.getCargo().addCrew(20000);
        player.getCargo().addSupplies(5000);
        player.getCargo().addFuel(2000);
        CampaignFleetAPI enemy = Global.getFactory().createEmptyFleet(ENEMY_FACTION, "Live check enemy", true);
        fill(player, PLAYER_FACTION);
        fill(enemy, ENEMY_FACTION);

        // what the refit dialog's Save does, on a per-member copy of the variant
        int tagged = 0;
        for (FleetMemberAPI m : player.getFleetData().getMembersListCopy()) {
            if (m.isFighterWing()) continue;
            ShipVariantAPI v = m.getVariant().clone();
            v.addTag(ENABLE_TAG);
            m.setVariant(v, false, true);
            tagged++;
        }
        AiBattleDriver.taggedMembers = tagged;

        for (CampaignFleetAPI f : new CampaignFleetAPI[]{player, enemy}) {
            f.getFleetData().setSyncNeeded();
            f.getFleetData().syncIfNeeded();
        }
        player.getContainingLocation().addEntity(enemy);
        enemy.setLocation(player.getLocation().x + 150f, player.getLocation().y);
        c.note("battle size: " + FLEETS_PER_SIDE + " fleet(s) of " + FLEET_POINTS + " FP per side");
        c.expect(player.getFleetData().getNumMembers() >= 3 && enemy.getFleetData().getNumMembers() >= 3,
                "both fleets have ships: " + player.getFleetData().getNumMembers() + " and " + enemy.getFleetData().getNumMembers());
        c.expect(tagged >= 3, "player ships tagged for the CAT AI: " + tagged);

        ((CombatEngine) CombatEngine.getInstance()).setShowDeploymentDialog(false);
        Global.getSector().getCampaignUI().startBattle(new BattleCreationContext(player, FleetGoal.ATTACK, enemy, FleetGoal.ATTACK));
        c.note("battle started");
        // the campaign Runner does not tick in combat; AiBattleDriver ends the run
        c.frames(1000000);
    }
}
