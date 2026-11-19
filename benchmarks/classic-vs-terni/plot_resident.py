"""Plot the Classic versus Terni gate-count sweep with 60 Hz preview enabled."""

import argparse
import csv
from pathlib import Path

from plot import COLORS, draw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent)
    parser.add_argument("--hardware", required=True)
    parser.add_argument("--connected", action="store_true")
    args = parser.parse_args()
    prefix = "resident-60hz-connected" if args.connected else "resident-60hz"

    rows = []
    for line in args.log.read_text().splitlines():
        if line.startswith("sweep,"):
            _, gates, activity, backend, burst, ticks, mean, tps, previews = line.split(",")
            rows.append(dict(gates=int(gates), activity=activity, backend=backend,
                             mean_ticks_per_call=float(burst), measured_ticks=int(ticks),
                             mean_tick_ms=float(mean), completed_tps=float(tps),
                             scheduled_previews=int(previews)))
    sizes = sorted({row["gates"] for row in rows})
    expected = {(size, activity, backend) for size in sizes
                for activity in ("active", "idle") for backend in COLORS}
    actual = {(row["gates"], row["activity"], row["backend"]) for row in rows}
    if not sizes or actual != expected or len(rows) != len(expected):
        raise ValueError("Expected one completed result per size, workload and backend.")

    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / f"{prefix}-measurements.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)

    for metrics in (
        ("mean_tick_ms", "Tick latency by gate count", "Mean tick latency (ms)", f"{prefix}-tick-latency.png"),
        ("completed_tps", "Simulation throughput by gate count", "Completed ticks per second", f"{prefix}-tps.png"),
    ):
        draw(rows, sizes, args.output, metrics, args.hardware, ticks=None,
             preview=True, connected=args.connected)


if __name__ == "__main__":
    main()
