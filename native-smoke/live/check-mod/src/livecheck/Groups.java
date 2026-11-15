package livecheck;

import java.util.ArrayList;
import java.util.List;

public final class Groups {
    private Groups() {
    }

    // comma separated groups run one after the other in one game, far faster than one boot per group.
    // the runner script reads the case labels below to check group names before it starts the game
    public static List<Step> get(String group) {
        List<Step> all = new ArrayList<>();
        for (String one : group.split(",")) {
            switch (one) {
                case "example":
                    all.addAll(ExampleChecks.steps());
                    break;
                // a real battle with the CAT AI on every player ship; ends the run (name it, "all" leaves it out)
                case "aibattle":
                    all.addAll(AiBattle.steps());
                    break;
                // one held CAT ship vs reckless enemies: predicted vs real damage per window; ends the run
                case "worstcase":
                    all.addAll(WorstCase.steps());
                    break;
                // known-answer vent decisions (calm, torpedo inbound, burn-drive cruiser nearby); ends the run
                case "ventcases":
                    all.addAll(VentCases.steps());
                    break;
                // known-answer movement decisions (nothing threatening, outgunned, automated ship); ends the run
                case "movecases":
                    all.addAll(MoveCases.steps());
                    break;
                // tuning: rounds of seeded fleet battles, each with CAT and with vanilla AI; ends the run. With
                // cat.tune.slices N, "tunebattleK" plays every Nth seed from the K-th (one per --shards game)
                case "tunebattle":
                case "tunebattle2":
                case "tunebattle3":
                case "tunebattle4":
                case "tunebattle5":
                case "tunebattle6":
                    all.addAll(TuneBattle.steps());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown group " + one);
            }
        }
        return all;
    }
}
