"""Combat AI Tweaks tuning report.

Reads the "TUNE" lines the tunebattle group writes (results.txt of one or more smoke runs) and compares CAT's rounds
with vanilla's on the same seeds: CAT's effect on the same fights.

Usage: python tune_report.py <run folder or results.txt> [...]

Vanilla rounds don't depend on CAT's settings, so every vanilla round of a seed is the baseline for that seed. CAT
rounds are grouped by the settings line of their run ("TUNE settings ..."). Per round, each side's FP-weighted damage
share: hull share lost (a destroyed ship counts fully) and the FP share of ships lost. Per seed, CAT minus vanilla
(means over the rounds of each): own loss (lower is better: survival first), enemy loss (higher is better), and the
exchange (enemy loss - own loss).
"""
import os
import re
import statistics
import sys
from collections import defaultdict

LINE = re.compile(r"TUNE seed=(\d+) mode=(\w+) time=(\d+) winner=(\w+) own=(\S+)/(\d+) ships (\d+) fp lost=(\d+) ships (\d+) fp "
                  r"hull=([\d.]+) armor=([\d.]+) retreated=(\d+) enemy=(\S+)/(\d+) ships (\d+) fp lost=(\d+) ships (\d+) fp "
                  r"hull=([\d.]+) armor=([\d.]+) retreated=(\d+)(?: fleets=(\S+))?")
SETTINGS = re.compile(r"TUNE settings (.*)")
DEFAULT = "(defaults, not logged)"


def rounds(paths):
    out = []
    for p in paths:
        f = os.path.join(p, "results.txt") if os.path.isdir(p) else p
        settings = DEFAULT
        with open(f, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                s = SETTINGS.search(line)
                if s:
                    settings = s.group(1).strip()
                m = LINE.search(line)
                if not m:
                    continue
                g = m.groups()
                out.append(dict(seed=int(g[0]), mode=g[1], time=int(g[2]), winner=g[3],
                                own_faction=g[4], own_fp=int(g[6]), own_lost_fp=int(g[8]), own_hull=float(g[9]),
                                enemy_faction=g[12], enemy_fp=int(g[14]), enemy_lost_fp=int(g[16]), enemy_hull=float(g[17]),
                                fleets=g[20] or "", settings=settings, source=f))
    return out


def mean(values):
    return statistics.mean(values) if values else float("nan")


def sd(values):
    return statistics.stdev(values) if len(values) > 1 else float("nan")


def show(r, label):
    print(f"{r['seed']:>5} {r['own_faction'] + ' v ' + r['enemy_faction']:<26} {label:<8} {r['winner']:<7} {r['time']:>4} "
          f"{r['own_hull']:>8.2f} {r['own_lost_fp'] / max(1, r['own_fp']):>7.2f} "
          f"{r['enemy_hull']:>10.2f} {r['enemy_lost_fp'] / max(1, r['enemy_fp']):>8.2f}")


def short(settings, base):
    """The settings that differ from [base] (both 'k=v k=v ...')."""
    if settings == DEFAULT or base is None:
        return settings
    b = dict(kv.split("=", 1) for kv in base.split())
    diff = [kv for kv in settings.split() if b.get(kv.split("=", 1)[0]) != kv.split("=", 1)[1]]
    return " ".join(diff) if diff else "defaults"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    rs = rounds(sys.argv[1:])
    vanilla = defaultdict(list)
    cat = defaultdict(lambda: defaultdict(list))
    for r in rs:
        if r["mode"] == "vanilla":
            vanilla[r["seed"]].append(r)
        else:
            cat[r["settings"]][r["seed"]].append(r)
    # the first settings seen with defaults is the reference for naming the others
    base = next((s for s in cat if s != DEFAULT), None)

    header = (f"{'seed':>5} {'factions':<26} {'mode':<8} {'win':<7} {'time':>4} {'own hull':>8} {'own fp':>7} "
              f"{'enemy hull':>10} {'enemy fp':>8}")
    print("vanilla baseline")
    print(header)
    for seed in sorted(vanilla):
        for r in vanilla[seed]:
            show(r, "vanilla")
        if len(vanilla[seed]) > 1:
            own = [r["own_hull"] for r in vanilla[seed]]
            print(f"      vanilla repeats: own loss sd {sd(own):.3f}")

    for settings, seeds in cat.items():
        print()
        print("CAT:", short(settings, base))
        print(header)
        d_own, d_enemy, d_exch = [], [], []
        wins = defaultdict(int)
        for seed in sorted(seeds):
            for r in seeds[seed]:
                show(r, "cat")
                wins[r["winner"]] += 1
            if len(seeds[seed]) > 1:
                print(f"      CAT repeats: own loss sd {sd([r['own_hull'] for r in seeds[seed]]):.3f}")
            v = vanilla.get(seed)
            if not v:
                continue
            c_own = mean([r["own_hull"] for r in seeds[seed]]); v_own = mean([r["own_hull"] for r in v])
            c_en = mean([r["enemy_hull"] for r in seeds[seed]]); v_en = mean([r["enemy_hull"] for r in v])
            d_own.append(c_own - v_own)
            d_enemy.append(c_en - v_en)
            d_exch.append((c_en - c_own) - (v_en - v_own))
            if len({r["fleets"] for r in seeds[seed] + v}) > 1:
                print(f"      note: seed {seed}'s fleets differ between rounds")
        n = sum(wins.values())
        print(f"rounds {n}: player won {wins['player']}, enemy won {wins['enemy']}, undecided {wins['none']}")
        for name, values, better in (("own loss", d_own, "lower is better"), ("enemy loss", d_enemy, "higher is better"),
                                     ("exchange", d_exch, "higher is better")):
            if values:
                print(f"CAT - vanilla {name:<10} mean {mean(values):+.3f} (sd {sd(values):.3f}, n {len(values)}; {better})")


if __name__ == "__main__":
    main()
