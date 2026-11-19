# Classic versus Terni

Measured on Linux x86-64 with an AMD Ryzen 7 7800X3D and NVIDIA GeForce RTX 4070 Ti. Both benchmarks use the implementation with GPU-resident preview batches.

- [60 Hz preview tick latency by gate count](resident-60hz-tick-latency.png) · [60 Hz preview TPS by gate count](resident-60hz-tps.png) · [60 Hz preview CSV](resident-60hz-measurements.csv)
- [Connected stress case tick latency](resident-60hz-connected-tick-latency.png) · [Connected stress case TPS](resident-60hz-connected-tps.png) · [Connected stress case CSV](resident-60hz-connected-measurements.csv)
- [Tick latency by gate count](tick-latency.png) · [Throughput by gate count](tps.png) · [Measurements CSV](measurements.csv)
- [200-gate batching and preview graph](small-circuit-batching.png) · [Small-circuit CSV](small-circuit-measurements.csv)

## Gate-count sweep

The sweep covers 1,000 to 1,000,000 gates in seven steps, with 30 warmup ticks and 200 measured ticks per case. The circuit has a repeating mix of six basic combinational gate types, one input and one driven output per gate. Active inputs alternate on every tick. Stable inputs stay constant.

The plots measure the complete world tick, including evaluation, Terni upload and readback, synchronization, and host state commit. Initialization and preparation of the external input pattern occur outside the timed tick. Each gate count in this sweep exceeds the 256-gate batch limit, so every Terni tick publishes its result. TPS is calculated as 1,000 divided by the reported mean latency in milliseconds. The CSV also includes p95 latency, first-tick latency, and separate Terni native counters averaged over warmup and measured ticks.

Terni first leads at the 10,000-gate sample for both workloads. At one million active gates, mean tick latency drops from **86.073 ms to 28.203 ms** and throughput rises from **11.62 to 35.46 TPS**.

## 200-gate feedback circuit

The small benchmark has 100 independent feedback nets that toggle every tick, each driven by a NOR and an OR. It uses 30 warmup calls and 5,000 measured calls per case. Each resident preview case runs 256 ticks per call and includes final synchronization in the total time. The preview count covers routine snapshots during the measured interval. The final synchronization can add one readback. Ordinary Terni batches publish their result every call.

| Mode | Mean µs per tick | Completed TPS | p95 call latency | Scheduled previews |
| --- | ---: | ---: | ---: | ---: |
| Classic, single tick | 3.336 | 299,773 | 5.991 µs | — |
| Terni, single tick | 43.760 | 22,852 | 53.251 µs | — |
| Terni, 16 ticks per call | 2.899 | 344,972 | 57.509 µs | — |
| Terni, 64 ticks per call | 0.935 | 1,068,962 | 65.223 µs | — |
| Terni, 256 ticks per call | 0.445 | 2,248,559 | 127.502 µs | — |
| Terni resident, 20 Hz preview | 0.426 | 2,345,614 | 121.760 µs | 10 |
| Terni resident, 60 Hz preview | 0.426 | 2,345,116 | 123.825 µs | 32 |

Both workloads run headlessly without clock pacing or rendering. The small-circuit result measures completed simulation throughput. The interactive editor caps the requested rate at 1,000,000 TPS.

## 60 Hz resident preview sweep

The 60 Hz graphs compare Classic and Terni from 16 to 1,000,000 gates, including the entire gate-count range of the original graphs. Both backends run the same feedback circuit at each size. In the toggling workload, a NOR drives its own input and shares the net with an OR. The stable workload replaces the NOR with an OR, so the net stays low.

The Terni run uses the editor's preview path at 60 Hz. The main sweep uses independent feedback pairs. Larger boards with this layout can run one shared-memory batch per workgroup, so the 256-gate single-workgroup limit does not end GPU residency. A second sweep links the pairs with one shared low-driven net. It uses GPU-wide gate evaluation and net commit and also exercises contention from many drivers on a single net.

Through 256 gates, Classic warms up for 50,000 ticks. Larger Classic cases warm up for 30 ticks. Terni warms up for 300 calls at every size. Each run then measures 5,000 calls through 256 gates and 200 calls at larger sizes. Terni adapts the number of ticks per call. Mean tick time includes compute, readbacks, host overhead, and final synchronization. TPS uses the same elapsed time. The runs are headless and uncapped. The CSV records completed ticks, mean burst length, and scheduled preview counts. The final synchronization can add one readback.

On the toggling independent circuit, the 256- and 258-gate samples measured 0.427 and 0.434 µs per tick. At 50,000 gates Terni reached 0.743 µs per tick and 1.35 million TPS. At 100,000 it reached 1.085 µs per tick and 922,042 TPS. The toggling shared-net stress case reached 9.030 µs per tick at 50,000 gates and 11.659 µs at 100,000. It continues batching across the workgroup limit, with a visible increase from the GPU-wide commit and shared-net contention.

## Reproduce

Build the desktop JNI library as described in the [Terni guide](../../docs/terni.md).
Then run these commands from the repository root:

```sh
./gradlew -PdesktopOnly :desktop:terniBenchmark \
  -Psizes=1000,3000,10000,30000,100000,300000,1000000 -Pticks=200 \
  -Dlogicdraw.terni.library="$PWD/build/terni/liblogicdraw_terni.so" > benchmark.log 2>&1

./gradlew -PdesktopOnly :desktop:terniBatchBenchmark -Psamples=5000 \
  -Dlogicdraw.terni.library="$PWD/build/terni/liblogicdraw_terni.so" > batching.log 2>&1

python3 benchmarks/classic-vs-terni/plot.py benchmark.log --batch-log batching.log \
  --hardware 'Ryzen 7 7800X3D + GeForce RTX 4070 Ti' --ticks 200 --batch-samples 5000
```

To reproduce the 60 Hz sweep and its two graphs:

```sh
./gradlew -PdesktopOnly :desktop:terniResidentBenchmark \
  -Psamples=5000 \
  -Dlogicdraw.terni.library="$PWD/build/terni/liblogicdraw_terni.so" > resident.log 2>&1

python3 benchmarks/classic-vs-terni/plot_resident.py resident.log \
  --hardware 'Ryzen 7 7800X3D + GeForce RTX 4070 Ti'
```

To reproduce the connected stress case, use the same commands with
`-Pconnected=true -Psizes=256,258,512,1000,10000,50000,100000` on Gradle and
`--connected` on the plotting script.

The plotting scripts require Matplotlib. Adjust the hardware label for the
machine used in the run.
