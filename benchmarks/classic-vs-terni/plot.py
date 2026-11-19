"""Render the Classic/Terni desktop benchmark into two PNGs and a CSV."""

import argparse
import csv
import re
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FixedLocator, FuncFormatter

BENCHMARK_ROW = re.compile(
    r"^(\d+) gates (idle|active) (Classic|Terni): first tick (\d+\.\d+) ms, "
    r"mean (\d+\.\d+) ms, p95 (\d+\.\d+) ms"
)
NATIVE_ROW = re.compile(
    r"(\d+) bytes uploaded, (\d+) bytes read back per tick, means: "
    r"([\d.eE+-]+) ms native call, ([\d.eE+-]+) ms submission and wait, "
    r"([\d.eE+-]+) ms device elapsed, ([\d.eE+-]+) ms compute"
)
BATCH_ROW = re.compile(
    r"^200 gates, (.+), batch (\d+): (\d+\.\d+) us/tick, (\d+) completed TPS, "
    r"(\d+\.\d+) us p95 batch latency, (\d+) scheduled previews",
    re.MULTILINE,
)

COLORS = {"Classic": "#315785", "Terni": "#008378"}


def parse(log):
    rows = []
    for line in log.splitlines():
        match = BENCHMARK_ROW.match(line)
        if match:
            gates, activity, backend, first, mean, p95 = match.groups()
            rows.append(
                dict(
                    gates=int(gates),
                    activity=activity,
                    backend=backend,
                    first_tick_ms=float(first),
                    mean_tick_ms=float(mean),
                    p95_tick_ms=float(p95),
                    calculated_tps=round(1000 / float(mean), 2),
                    upload_bytes="",
                    readback_bytes="",
                    native_call_ms="",
                    submit_and_wait_ms="",
                    device_elapsed_ms="",
                    shader_compute_ms="",
                )
            )
        native = NATIVE_ROW.search(line)
        if native and rows and rows[-1]["backend"] == "Terni":
            keys = ("upload_bytes", "readback_bytes", "native_call_ms",
                    "submit_and_wait_ms", "device_elapsed_ms", "shader_compute_ms")
            rows[-1].update(zip(keys, native.groups()))
    sizes = sorted(set(row["gates"] for row in rows))
    expected = {(size, activity, backend) for size in sizes
                for activity in ("active", "idle") for backend in COLORS}
    actual = {(row["gates"], row["activity"], row["backend"]) for row in rows}
    if not sizes or actual != expected or len(rows) != len(expected):
        raise ValueError("The log must contain one completed result per size, workload and backend.")
    return rows, sizes


def gate_label(size, _=None):
    if size < 1000:
        return str(size)
    return f"{size / 1_000_000:g}M" if size >= 1_000_000 else f"{size / 1000:g}k"


def draw(rows, sizes, directory, metrics, hardware, ticks, preview=False, connected=False):
    field, title, ylabel, filename = metrics
    plt.rcParams.update({
        "font.family": "DejaVu Sans",
        "axes.labelsize": 10,
        "axes.titleweight": "bold",
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.edgecolor": "#ccd6e0",
        "text.color": "#192d43",
        "axes.labelcolor": "#192d43",
        "xtick.color": "#41566b",
        "ytick.color": "#41566b",
    })
    fig, axes = plt.subplots(1, 2, figsize=(11.6, 5.6), sharey=True)
    fig.patch.set_facecolor("#f7f9fc")
    fig.subplots_adjust(left=.09, right=.965, bottom=.25 if preview else .22, top=.735, wspace=.15)
    fig.text(.09, .94, title, fontsize=20, weight="bold", va="top")
    run_label = ("Terni preview set to 60 Hz" if preview
                 else f"{ticks} measured ticks per case")
    fig.text(.09, .86, f"{hardware}  •  {run_label}", fontsize=10)
    for axis, activity in zip(axes, ("active", "idle")):
        axis.set_facecolor("white")
        axis.set_title("Active inputs" if activity == "active" else "Stable inputs", loc="left", pad=11)
        axis.set_xscale("log")
        axis.set_yscale("log")
        if preview:
            axis.set_title("Toggling feedback" if activity == "active" else "Stable feedback", loc="left", pad=11)
        # Retain all data points and skip adjacent labels at the crossover.
        labels = [size for size in sizes if size not in (200, 258)] if preview and 256 in sizes else sizes
        axis.xaxis.set_major_locator(FixedLocator(labels))
        axis.xaxis.set_major_formatter(FuncFormatter(gate_label))
        axis.tick_params(axis="both", which="both", length=0)
        if preview:
            axis.tick_params(axis="x", labelrotation=50, labelsize=9)
        axis.grid(axis="y", which="major", color="#dbe3ec")
        axis.set_axisbelow(True)
        axis.set_xlabel("Gate count", labelpad=9)
        for backend, color in COLORS.items():
            series = sorted(
                (row for row in rows if row["activity"] == activity and row["backend"] == backend),
                key=lambda row: row["gates"],
            )
            axis.plot([row["gates"] for row in series], [row[field] for row in series],
                      label=backend, color=color, marker="o" if backend == "Classic" else "s",
                      markersize=5, linewidth=2.3)
    axes[0].set_ylabel(ylabel)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper right", bbox_to_anchor=(.966, .874),
               frameon=False, ncol=2, fontsize=10)
    if preview:
        note = ("A shared net links the feedback pairs. Above 256 gates, Terni uses GPU-wide net commit."
                if connected else "Feedback pairs are independent. Terni batches them in parallel workgroups.")
        footer = "Mean tick time includes compute, readbacks, host overhead and the final synchronization."
    else:
        note = "Log scales. Classic and Terni ran the same headless circuits."
        footer = ("Tick time includes evaluation, transfers and host commit."
                  if field == "mean_tick_ms" else
                  "TPS = 1,000 ÷ mean tick latency in ms. No rendering or clock pacing.")
    fig.text(.09, .095, note, fontsize=9, color="#41566b")
    fig.text(.09, .061, footer, fontsize=9, color="#41566b")
    fig.savefig(directory / filename, dpi=180, facecolor=fig.get_facecolor())
    plt.close(fig)


def draw_batches(log, directory, hardware, samples):
    rows = []
    for name, batch, mean, tps, p95, previews in BATCH_ROW.findall(log):
        rows.append(dict(mode=name, ticks_per_call=int(batch), mean_tick_us=float(mean),
                         completed_tps=int(tps), p95_call_us=float(p95), scheduled_previews=int(previews)))
    expected = {("Classic", 1), ("Terni", 1), ("Terni", 16), ("Terni", 64), ("Terni", 256),
                ("Terni resident 20 Hz", 256), ("Terni resident 60 Hz", 256)}
    if len(rows) != len(expected) or {(r["mode"], r["ticks_per_call"]) for r in rows} != expected:
        raise ValueError("The small-circuit log must contain all seven benchmark cases.")
    with (directory / "small-circuit-measurements.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)

    labels = []
    colors = []
    for row in rows:
        mode, batch = row["mode"], row["ticks_per_call"]
        if mode.startswith("Terni resident"):
            labels.append(mode.replace("Terni resident ", "Resident preview · "))
            colors.append("#7d50aa")
        else:
            labels.append(f"{mode} · {batch} tick" + ("s" if batch > 1 else ""))
            colors.append(COLORS[mode])

    fig, axes = plt.subplots(1, 2, figsize=(12, 6.0), sharey=True)
    fig.patch.set_facecolor("#f7f9fc")
    fig.subplots_adjust(left=.21, right=.965, bottom=.20, top=.73, wspace=.30)
    fig.text(.21, .94, "200-gate feedback circuit", fontsize=21, weight="bold", va="top")
    fig.text(.21, .85, f"{hardware}  •  {samples:,} measured calls per case", fontsize=10)
    specs = [("mean_tick_us", "Mean tick latency", "Microseconds per tick", lambda value: f"{value:.3f} µs"),
             ("completed_tps", "Completed throughput", "Ticks per second", lambda value: f"{value:,}")]
    for axis, (field, title, ylabel, label) in zip(axes, specs):
        values = [row[field] for row in rows]
        floor = min(values) / 2.5
        axis.set_facecolor("white")
        axis.set_xscale("log")
        axis.set_xlim(floor, max(values) * 3.0)
        axis.barh(range(len(rows)), [v - floor for v in values], left=floor,
                  color=colors, height=.61)
        axis.xaxis.grid(True, which="major", color="#dbe3ec")
        axis.set_axisbelow(True)
        axis.tick_params(axis="both", which="both", length=0)
        axis.set_title(title, loc="left", pad=11)
        axis.set_xlabel(ylabel, labelpad=8)
        for index, value in enumerate(values):
            axis.text(value * 1.10, index, label(value), va="center", fontsize=8.5)
    axes[0].set_yticks(range(len(rows)), labels)
    axes[0].invert_yaxis()
    axes[1].axvline(500_000, color="#725431", linewidth=1.0, linestyle="--", zorder=0)
    fig.text(.21, .085,
             "Log scales. Mean and throughput include final synchronization. Resident cases use 256-tick bursts.",
             fontsize=9, color="#41566b")
    fig.text(.21, .051, "Dashed line marks 500,000 TPS. Runs are uncapped and headless.",
             fontsize=9, color="#41566b")
    fig.savefig(directory / "small-circuit-batching.png", dpi=180, facecolor=fig.get_facecolor())
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent)
    parser.add_argument("--hardware", required=True)
    parser.add_argument("--ticks", type=int, required=True)
    parser.add_argument("--batch-log", type=Path)
    parser.add_argument("--batch-samples", type=int, default=5000)
    args = parser.parse_args()
    rows, sizes = parse(args.log.read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / "measurements.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    draw(rows, sizes, args.output,
         ("mean_tick_ms", "Time to complete one simulation tick", "Mean tick latency (ms)", "tick-latency.png"),
         args.hardware, args.ticks)
    draw(rows, sizes, args.output,
         ("calculated_tps", "Simulation throughput by circuit size", "Calculated ticks per second", "tps.png"),
         args.hardware, args.ticks)
    if args.batch_log:
        draw_batches(args.batch_log.read_text(), args.output, args.hardware, args.batch_samples)


if __name__ == "__main__":
    main()
