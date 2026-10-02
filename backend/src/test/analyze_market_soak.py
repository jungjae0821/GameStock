"""Summarize MarketSoakTest JSON without third-party packages. Usage: python3 ... <report-directory>."""
import json
import statistics
import sys
from pathlib import Path


def summarize(run):
    samples = run["samples"]
    participants = [p for p in run["participants"] if p["username"] != "liquidity_provider"]
    lp = next(p for p in run["participants"] if p["username"] == "liquidity_provider")
    active_books = [(s, b) for s in samples if s["second"] >= 300 and s["marketOpen"]
                    for b in s["books"] if b["vi"] is None]
    two_sided = [(s, b) for s, b in active_books if b["bids"] > 0 and b["asks"] > 0]
    spreads = [(b["best_ask"] - b["best_bid"]) / ((b["best_ask"] + b["best_bid"]) / 2) * 100
               for _, b in two_sided]
    total_participant_volume = sum(p["buys"] + p["sells"] for p in participants)
    participant_rows = [dict(name=p["nickname"], volume=p["buys"] + p["sells"],
                             volumeSharePercent=100 * (p["buys"] + p["sells"]) / max(1, total_participant_volume),
                             pnl=p["cash"] + p["reserved_cash"] + p["assets"] - 1_000_000)
                        for p in participants]
    stagnant_minutes = sum(a["trades"] == b["trades"] for a, b in zip(samples, samples[1:]))
    all_books = [b for s in samples if s["second"] >= 300 for b in s["books"]]
    return dict(seed=run["seed"], simulatedHours=run["simulatedSeconds"] / 3600,
                actions=run["actions"], trades=samples[-1]["trades"], volume=samples[-1]["volume"],
                wallSeconds=round(run["wallSeconds"], 1), invariantFailures=run["invariantFailures"],
                feeTotal=samples[-1]["fees"], cashConservationDelta=samples[-1]["cashConservationDelta"],
                globalMinutesWithoutTrades=stagnant_minutes,
                viBookPercent=round(100 * sum(b["vi"] is not None for b in all_books) / max(1, len(all_books)), 2),
                oneSidedBookPercent=round(100 * (len(active_books) - len(two_sided)) / max(1, len(active_books)), 2),
                activeBookSamples=len(active_books),
                staleOver5MinutesPercent=round(100 * sum(b["stale_seconds"] is None or b["stale_seconds"] >= 300
                                                        for _, b in active_books) / max(1, len(active_books)), 2),
                longestObservedTradeGapSeconds=max((s["second"] if b["stale_seconds"] is None else b["stale_seconds"]
                                                    for s, b in active_books), default=0),
                longestExactTradeGapSeconds=max((g["longestSeconds"] for g in run.get("tradeGaps", [])), default=None),
                exactGapsOver5Minutes=sum(g["overFiveMinutes"] for g in run.get("tradeGaps", [])) if "tradeGaps" in run else None,
                medianSpreadPercent=round(statistics.median(spreads), 3) if spreads else None,
                maxSpreadPercent=round(max(spreads), 3) if spreads else None,
                lpVolumeSharePercent=round(100 * (lp["buys"] + lp["sells"]) / max(1, samples[-1]["volume"]), 2),
                lpCashIncludingReservations=lp["cash"] + lp["reserved_cash"],
                lpInventoryMin=min(b["lp_inventory"] for s in samples for b in s["books"]),
                lpInventoryMax=max(b["lp_inventory"] for s in samples for b in s["books"]),
                minTradePrice=min(s["low"] for s in run["symbols"]),
                maxTradePrice=max(s["high"] for s in run["symbols"]),
                regimes=sorted({s["regime"] for s in samples}),
                patternsObserved=len({p for s in samples for p in s.get("patterns", {}).values()}),
                participants=sorted(participant_rows, key=lambda p: p["volume"], reverse=True),
                symbols=run["symbols"])


def main():
    directory = Path(sys.argv[1])
    runs = [json.loads(p.read_text()) for p in sorted(directory.glob("seed-*.json")) if "samples" not in p.name]
    summaries = [summarize(run) for run in runs]
    (directory / "summary.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2) + "\n")
    for row in summaries:
        print(json.dumps({k: v for k, v in row.items() if k not in ("participants", "symbols", "regimes")}, ensure_ascii=False))
        print("Top participants:", json.dumps(row["participants"][:4], ensure_ascii=False))


if __name__ == "__main__":
    main()
