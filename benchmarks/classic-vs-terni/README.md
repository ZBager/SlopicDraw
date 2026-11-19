# Classic versus Terni

Measured with the desktop benchmark on Linux x86-64, an AMD Ryzen 7 7800X3D,
and an NVIDIA GeForce RTX 4070 Ti. Each case used 30 warmup ticks followed by
200 measured ticks. The synthetic board uses a repeating mix of the six
basic combinational gate types, with one input and one driven output per
gate. Active inputs alternate on every tick. Stable inputs stay constant.

The latency plots measure the complete `world.update` call, including gate
evaluation, Terni upload and readback, synchronization, and the host state
commit. Preparation of the external input pattern and initial circuit setup
occur outside that measurement. TPS is calculated as 1,000 divided by the
reported mean latency in milliseconds. The benchmark runs without rendering
or clock pacing. The CSV includes p95 latency, first-tick latency, and
Terni's separate native counters, which also include warmup ticks.

[Tick latency graph](tick-latency.png) · [TPS graph](tps.png) · [Measurements CSV](measurements.csv)

Terni first leads at the 10,000-gate sample for both workloads. At one million
active gates, mean tick latency falls from 88.157 ms to 21.327 ms and
throughput rises from 11.34 to 46.89 TPS.

## Reproduce

Build the desktop JNI library as described in the [Terni guide](../../docs/terni.md).
Then run these commands from the repository root:

```sh
./gradlew -PdesktopOnly :desktop:terniBenchmark \
  -Psizes=1000,3000,10000,30000,100000,300000,1000000 -Pticks=200 \
  -Dlogicdraw.terni.library="$PWD/build/terni/liblogicdraw_terni.so" > benchmark.log 2>&1
python3 benchmarks/classic-vs-terni/plot.py benchmark.log \
  --hardware 'Ryzen 7 7800X3D + GeForce RTX 4070 Ti' --ticks 200
```

The plotting script requires Matplotlib. Adjust the hardware label for the
machine used in the run.
