# Classic versus Terni

Measured on Linux x86-64 with an AMD Ryzen 7 7800X3D and NVIDIA GeForce RTX 4070 Ti. Both benchmarks use the implementation with GPU-resident preview batches.

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

The plotting script requires Matplotlib. Adjust the hardware label for the
machine used in the run.
