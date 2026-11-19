"""Plot Classic and Terni with 60 Hz GPU-resident preview snapshots."""

import argparse
import csv
import re
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FixedLocator, ScalarFormatter

ROW = re.compile(
    r"^(\d+) gates, (Classic|Terni|Terni resident 60 Hz), batch (\d+): "
    r"(\d+\.\d+) us/tick, (\d+) completed TPS, (\d+\.\d+) us p95 batch latency, "
    r"(\d+) scheduled previews$",
    re.MULTILINE,
)
MODES = {
    ("Classic", 1): ("Classic · single tick", "#315785", "o", "-"),
    ("Terni", 256): ("Terni · readback every batch", "#008378", "s", "--"),
    ("Terni resident 60 Hz", 256): ("Terni · 60 Hz resident preview", "#7d50aa", "D", "-"),
}


def parse(log):
    rows = [
        dict(gates=int(gates), mode=mode, ticks_per_call=int(batch), mean_tick_us=float(mean),
             completed_tps=int(tps), p95_call_us=float(p95), scheduled_previews=int(previews))
        for gates, mode, batch, mean, tps, p95, previews in ROW.findall(log)
    ]
    sizes = sorted({row["gates"] for row in rows})
    expected = {(gates, mode, batch) for gates in sizes for mode, batch in MODES}
    actual = {(row["gates"], row["mode"], row["ticks_per_call"]) for row in rows}
    if not rows or actual != expected or len(actual) != len(rows):
        raise ValueError("Expected one result for every size and readback mode.")
    return rows, sizes


def plot(rows, sizes, output, scenario, hardware, samples):
    field, title, ylabel, filename, footer = scenario
    plt.rcParams.update({
        "font.family": "DejaVu Sans",
        "text.color": "#192d43",
        "axes.labelcolor": "#192d43",
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.edgecolor": "#ccd6e0",
        "xtick.color": "#41566b",
        "ytick.color": "#41566b",
    })
    fig, ax = plt.subplots(figsize=(10.4, 5.8))
    fig.patch.set_facecolor("#f7f9fc")
    fig.subplots_adjust(left=.10, right=.96, bottom=.235, top=.70)
    fig.text(.10, .95, title, fontsize=20, weight="bold", va="top")
    fig.text(.10, .865, f"{hardware}  •  {samples:,} measured calls per case", fontsize=10)
    ax.set_facecolor("white")
    ax.set_xscale("log", base=2)
    ax.set_yscale("log")
    ax.xaxis.set_major_locator(FixedLocator(sizes))
    ax.xaxis.set_major_formatter(ScalarFormatter())
    ax.set_xlabel("Combinational gates", labelpad=9)
    ax.set_ylabel(ylabel, labelpad=10)
    ax.tick_params(axis="both", which="both", length=0)
    ax.set_axisbelow(True)
    ax.grid(axis="y", which="major", color="#dbe3ec")
    for (mode, batch), (name, color, marker, style) in MODES.items():
        series = sorted((row for row in rows if (row["mode"], row["ticks_per_call"]) == (mode, batch)),
                        key=lambda row: row["gates"])
        ax.plot([row["gates"] for row in series], [row[field] for row in series], label=name,
                color=color, marker=marker, linestyle=style, linewidth=2, markersize=5)
    handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper left", bbox_to_anchor=(.095, .82),
               frameon=False, ncol=3, fontsize=9)
    fig.text(.10, .105, "Headless feedback circuits. Terni uses 256-tick bursts. Both axes are logarithmic.",
             fontsize=9, color="#41566b")
    fig.text(.10, .067, footer, fontsize=9, color="#41566b")
    fig.savefig(output / filename, dpi=180, facecolor=fig.get_facecolor())
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent)
    parser.add_argument("--hardware", required=True)
    parser.add_argument("--samples", type=int, default=5000)
    args = parser.parse_args()
    rows, sizes = parse(args.log.read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / "resident-60hz-measurements.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    charts = [
        ("mean_tick_us", "Tick latency with 60 Hz resident preview", "Mean µs per tick",
         "resident-60hz-tick-latency.png",
         "Mean tick time includes scheduled readbacks, host commits, and the final exact synchronization."),
        ("completed_tps", "Simulation throughput with 60 Hz resident preview", "Completed ticks per second",
         "resident-60hz-tps.png",
         "Throughput counts completed ticks through the final exact snapshot. Clock pacing is disabled."),
    ]
    for chart in charts:
        plot(rows, sizes, args.output, chart, args.hardware, args.samples)


if __name__ == "__main__":
    main()
