"""Combat AI Tweaks battle trace report.

Reads a trace written by runtime/TraceRecorder.kt (saves/common/cat_trace/<battle>/part_NNN.data, JSON lines)
and compares what CAT predicted with what happened.

Usage: python trace_report.py <trace folder> [--ship <id>] [--window 2] [--worst 8]
"""
import argparse
import bisect
import glob
import json
import math
import os
import statistics
from collections import defaultdict


def load(folder):
    records = []
    for path in sorted(glob.glob(os.path.join(folder, "part_*.data"))):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        records.append(json.loads(line))
                    except json.JSONDecodeError:
                        pass  # a part cut off mid-write
    return records


def pct(values, q):
    if not values:
        return float("nan")
    v = sorted(values)
    return v[min(len(v) - 1, int(len(v) * q))]


class Trace:
    def __init__(self, records):
        self.by_kind = defaultdict(list)
        for r in records:
            self.by_kind[r["k"]].append(r)
        # ship positions by id: sorted times and records
        self.ship_times = defaultdict(list)
        self.ship_recs = defaultdict(list)
        for r in self.by_kind["ship"]:
            self.ship_times[r["id"]].append(r["t"])
            self.ship_recs[r["id"]].append(r)
        self.hulls = {r["id"]: r["hull"] for r in self.by_kind["ship"]}
        self.cat = sorted({r["id"] for r in self.by_kind["ship"] if "st" in r})
        self.hits = defaultdict(list)  # target -> hit records sorted by time
        for r in self.by_kind["hit"]:
            self.hits[r["tgt"]].append(r)
        self.hit_times = {k: [h["t"] for h in v] for k, v in self.hits.items()}

    def name(self, ship_id):
        if ship_id is None:
            return "?"
        return f"{self.hulls.get(ship_id, '?')}#{ship_id[-4:]}" if ship_id not in ("in flight",) else ship_id

    def ship_at(self, ship_id, t):
        times = self.ship_times.get(ship_id)
        if not times:
            return None
        i = bisect.bisect_left(times, t)
        best = None
        for j in (i - 1, i):
            if 0 <= j < len(times) and (best is None or abs(times[j] - t) < abs(times[best] - t)):
                best = j
        if best is None or abs(times[best] - t) > 0.3:
            return None
        return self.ship_recs[ship_id][best]

    def actual_hits(self, ship_id, t0, t1):
        times = self.hit_times.get(ship_id, [])
        a, b = bisect.bisect_left(times, t0), bisect.bisect_left(times, t1)
        return self.hits[ship_id][a:b]


def predicted_in_window(pred, t0, t1, bin_s=0.5):
    """Predicted raw damage per shooter|delivery between absolute times t0 and t1."""
    start = pred["start"]
    out = defaultdict(float)
    for key, bins in (pred.get("hits") or {}).items():
        for b, v in enumerate(bins):
            bt0 = start + b * bin_s
            bt1 = bt0 + bin_s
            overlap = max(0.0, min(t1, bt1) - max(t0, bt0))
            if overlap > 0:
                out[key] += v * overlap / bin_s
    return out


def damage_report(tr, ships, window, worst_n):
    print(f"\n== Damage: predicted (hold branch) vs actual raw damage per {window:.0f} s window ==")
    for sid in ships:
        preds = [p for p in tr.by_kind["pred"] if p["id"] == sid]
        if not preds:
            continue
        ratios, rows = [], []
        tot_pred, tot_act = defaultdict(float), defaultdict(float)
        last_window_end = -1e9
        end_time = max((r["t"] for r in tr.by_kind["ship"]), default=0)
        for p in preds:
            t0, t1 = p["t"], p["t"] + window
            if t1 > end_time:
                continue
            pw = predicted_in_window(p, t0, t1)
            pred_total = sum(pw.values())
            hits = tr.actual_hits(sid, t0, t1)
            act_total = sum(h["raw"] for h in hits)
            if pred_total > 100 or act_total > 100:
                ratios.append(act_total / pred_total if pred_total > 1 else float("inf"))
                rows.append((act_total - pred_total, p, pw, hits, pred_total, act_total))
            if t0 >= last_window_end:  # non-overlapping windows for the per-source totals
                last_window_end = t1
                for k, v in pw.items():
                    src, dl = k.rsplit("|", 1)
                    tot_pred[(src, dl)] += v
                for h in hits:
                    tot_act[(h.get("src") or "?", h["del"])] += h["raw"]
        if not ratios:
            print(f"{tr.name(sid)}: no windows with damage")
            continue
        finite = [r for r in ratios if math.isfinite(r)]
        over = sum(1 for r in ratios if r > 1.25)
        print(f"{tr.name(sid)}: {len(ratios)} windows; actual/predicted p10 {pct(finite, .1):.2f} median {pct(finite, .5):.2f} "
              f"p90 {pct(finite, .9):.2f}; actual > 1.25x predicted in {over} ({100 * over / len(ratios):.0f}%), "
              f"{len(ratios) - len(finite)} with damage but none predicted")
        keys = sorted(set(tot_pred) | set(tot_act), key=lambda k: -(tot_pred.get(k, 0) + tot_act.get(k, 0)))
        for k in keys[:12]:
            print(f"    {tr.name(k[0]):28s} {k[1]}  predicted {tot_pred.get(k, 0):8.0f}  actual {tot_act.get(k, 0):8.0f}")
        rows.sort(key=lambda r: -r[0])
        for gap, p, pw, hits, pt, at in rows[:worst_n]:
            if gap <= 0:
                break
            print(f"  under-predicted at t={p['t']:.1f} (prediction from {p['start']:.1f}): predicted {pt:.0f}, actual {at:.0f}")
            by = defaultdict(float)
            for h in hits:
                by[(h.get("src"), h["del"], h["type"])] += h["raw"]
            for (src, dl, ty), v in sorted(by.items(), key=lambda kv: -kv[1])[:5]:
                print(f"      actual    {tr.name(src):28s} {dl} {ty:15s} {v:7.0f}")
            for k, v in sorted(pw.items(), key=lambda kv: -kv[1])[:5]:
                src, dl = k.rsplit("|", 1)
                print(f"      predicted {tr.name(src):28s} {dl} {'':15s} {v:7.0f}")


def position_report(tr, ships):
    print("\n== Positions: committed enemies' predicted pursuit vs where they really were ==")
    for ahead in (1.0, 2.0, 5.0):
        errs, closer = [], 0
        reacted, reacted_closer = 0, 0
        causes, causes_closer = {}, {}
        for p in tr.by_kind["pred"]:
            if ships and p["id"] not in ships:
                continue
            idx = int(round(ahead / 0.5)) - 1
            me = tr.ship_at(p["id"], p["t"] + ahead)
            for eid, c in (p.get("committed") or {}).items():
                path = c.get("path")
                real = tr.ship_at(eid, p["start"] + ahead)
                if not path or idx >= len(path) or real is None or "ftr" in real:
                    continue
                px, py = path[idx][0], path[idx][1]
                errs.append(math.hypot(real["x"] - px, real["y"] - py))
                react = c.get("react")
                backing = react is not None and react["from"] <= ahead
                if backing:
                    reacted += 1
                    causes[react["cause"]] = causes.get(react["cause"], 0) + 1
                if me is not None:
                    pd = math.hypot(px - p["path"][idx][0], py - p["path"][idx][1]) if p.get("path") else None
                    rd = math.hypot(real["x"] - me["x"], real["y"] - me["y"])
                    if pd is not None and rd < pd - 100:
                        closer += 1
                        if backing:
                            reacted_closer += 1
                            causes_closer[react["cause"]] = causes_closer.get(react["cause"], 0) + 1
        if errs:
            print(f"  +{ahead:.0f} s: error median {statistics.median(errs):.0f}, p90 {pct(errs, .9):.0f} over {len(errs)}; "
                  f"enemy >100 closer than predicted in {closer} ({100 * closer / len(errs):.1f}%)")
            if reacted:
                print(f"         predicted backing off by then (plan 13.8): {reacted} {causes}, "
                      f">100 closer in {reacted_closer} ({100 * reacted_closer / reacted:.1f}%) {causes_closer}")


def missile_report(tr, ships):
    print("\n== Missiles: hits on CAT ships from missiles the predictor never saw ==")
    inputs = tr.by_kind["in"]
    in_times = [r["t"] for r in inputs]
    msl_by_id = defaultdict(list)
    for m in tr.by_kind["msl"]:
        msl_by_id[m["id"]].append(m)
    missed, seen = defaultdict(float), defaultdict(float)
    for sid in ships:
        for h in tr.hits.get(sid, []):
            if h["del"] != "M" or not h.get("mid"):
                continue
            track = msl_by_id.get(h["mid"], [])
            # was this missile in a predictor input batch while it flew? match by position at the batch time
            found = False
            for m in track:
                i = bisect.bisect_left(in_times, m["t"] - 0.15)
                for j in range(i, min(len(inputs), i + 3)):
                    for mm in inputs[j]["missiles"]:
                        if abs(inputs[j]["t"] - m["t"]) < 0.3 and math.hypot(mm[0] - m["x"], mm[1] - m["y"]) < 150:
                            found = True
                            break
                    if found:
                        break
                if found:
                    break
            spec = track[0]["spec"] if track else "?"
            (seen if found else missed)[spec] += h["raw"]
    for spec in sorted(set(missed) | set(seen)):
        print(f"  {spec:24s} damage from missiles seen by the predictor {seen.get(spec, 0):8.0f}, never seen {missed.get(spec, 0):8.0f}")
    if not missed and not seen:
        print("  no missile hits on CAT ships")


def vent_report(tr, ships):
    print("\n== Vents ==")
    for sid in ships:
        recs = tr.ship_recs.get(sid, [])
        venting, start, start_hp, reason = False, 0.0, 0.0, None
        for r in recs:
            if r.get("vent") and not venting:
                venting, start, start_hp, reason = True, r["t"], r.get("hp", 0), r.get("vt")
            elif venting and not r.get("vent"):
                venting = False
                hits = tr.actual_hits(sid, start, r["t"])
                raw = sum(h["raw"] for h in hits)
                print(f"  {tr.name(sid)} vented {start:.1f}-{r['t']:.1f}: hull {start_hp:.0f} -> {r.get('hp', 0):.0f}, "
                      f"raw damage taken {raw:.0f} | {reason}")


def move_report(tr, ships, changes_n):
    """Movement states (plan §8.6): time and hull lost in each, how often they change, and each change's reason."""
    print("\n== Movement states ==")
    for sid in ships:
        recs = [r for r in tr.ship_recs.get(sid, []) if r.get("st")]
        if not recs:
            continue
        time_in, hull_in = {}, {}
        changes = []
        for prev, r in zip(recs, recs[1:]):
            st = prev["st"]
            time_in[st] = time_in.get(st, 0.0) + r["t"] - prev["t"]
            hull_in[st] = hull_in.get(st, 0.0) + max(0.0, prev.get("hp", 0) - r.get("hp", 0))
            if r["st"] != st:
                changes.append(r)
        span = max(recs[-1]["t"] - recs[0]["t"], 1e-6)
        parts = ", ".join(f"{st} {t:.0f}s/-{hull_in.get(st, 0):.0f}hp" for st, t in sorted(time_in.items(), key=lambda x: -x[1]))
        print(f"  {tr.name(sid)}: {parts}; {len(changes)} changes ({len(changes) / span * 60:.0f}/min)")
        for r in changes[:changes_n]:
            print(f"    {r['t']:6.1f} -> {r['st']}: {r.get('mv') or ''}")


def shield_report(tr, ships):
    """Shield flicker (plan 13.9): time CAT held the shield down, hard flux drained, and what hit armor meanwhile."""
    print("\n== Shield drops (CAT holds the shield down) ==")
    for sid in ships:
        recs = tr.ship_recs.get(sid, [])
        periods, start = [], None
        for prev, r in zip(recs, recs[1:]):
            if r.get("shd") and start is None:
                start = prev
            elif not r.get("shd") and start is not None:
                periods.append((start, r))
                start = None
        if start is not None:
            periods.append((start, recs[-1]))
        keep = defaultdict(int)
        for r in recs:
            if r.get("shr"):
                why = r["shr"].split(":")[1].strip() if ":" in r["shr"] else r["shr"]
                why = "shield hit soon" if why.startswith("shield hit in") else why
                keep[why] += 1
        if keep:
            print(f"  {tr.name(sid)}: shield up with hard flux > 0.1, why: {dict(sorted(keep.items(), key=lambda x: -x[1]))}")
        if not periods:
            continue
        down = sum(b["t"] - a["t"] for a, b in periods)
        drained = sum(max(0.0, a.get("hard", 0) - b.get("hard", 0)) for a, b in periods)
        exposed, worst = 0.0, (0.0, None)
        for a, b in periods:
            # while held down, and the second after (the shield coming back up), until a vent or overload begins
            end = b["t"] + 1.0
            for r in recs:
                if a["t"] <= r["t"] <= end and (r.get("vent") or r.get("ovl")):
                    end = r["t"]
                    break
            raw = 0.0
            for h in tr.actual_hits(sid, a["t"], end):
                if h.get("shield"):
                    continue
                me = tr.ship_at(sid, h["t"])
                hx, hy = h.get("x"), h.get("y")
                if me is None or hx is None or hy is None or hx != hx:
                    continue
                # only the side a front shield covers (within 90 degrees of the facing)
                bearing = math.degrees(math.atan2(hy - me["y"], hx - me["x"]))
                off = abs((bearing - me["f"] + 180) % 360 - 180)
                if off <= 90:
                    raw += h["raw"]
            exposed += raw
            if raw > worst[0]:
                worst = (raw, a["t"])
        w = f", worst {worst[0]:.0f} at {worst[1]:.1f}s" if worst[1] is not None else ""
        print(f"  {tr.name(sid)}: {len(periods)} drops, {down:.0f}s down, hard flux drained {drained:.0f}, "
              f"raw damage off the shield while down (+1 s) {exposed:.0f}{w}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("--ship", action="append")
    ap.add_argument("--window", type=float, default=2.0)
    ap.add_argument("--worst", type=int, default=6)
    ap.add_argument("--changes", type=int, default=8, help="state changes listed per ship")
    a = ap.parse_args()
    tr = Trace(load(a.folder))
    ships = a.ship or tr.cat
    counts = {k: len(v) for k, v in tr.by_kind.items()}
    end = max((r["t"] for r in tr.by_kind["ship"]), default=0)
    print(f"trace {a.folder}: {end:.0f} s, CAT ships {[tr.name(s) for s in tr.cat]}, records {counts}")
    damage_report(tr, ships, a.window, a.worst)
    position_report(tr, ships)
    missile_report(tr, ships)
    vent_report(tr, ships)
    move_report(tr, ships, a.changes)
    shield_report(tr, ships)


if __name__ == "__main__":
    main()
