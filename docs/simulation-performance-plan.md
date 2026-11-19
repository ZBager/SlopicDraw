# Terni performance plan

## Baseline

The current desktop tick packs sampled wire levels and input inversion flags on the CPU, uploads them, dispatches Terni, waits for a full result readback, then applies the results and commits Classic's signal buffers. This runs once per tick even if the renderer only shows a small part of the circuit.

At one million active gates on the RTX 4070 Ti, Terni averaged 21.327 ms per complete tick versus 88.157 ms for Classic. Terni transferred 8.0 MB to the device and read back 12.0 MB per tick. Its native call averaged 2.147 ms and its shader averaged 0.253 ms. These timings have different measurement scopes. Instrument host packing, result application and state commit directly before attributing the remaining time to individual operations. The largest opportunity is to eliminate the transfer and the host work it creates together. [Benchmark data](../benchmarks/classic-vs-terni/README.md)

## 1. Establish a comparable baseline

- Measure input preparation, JNI copies, CPU submission overhead and fence wait, shader stages, bytes read back, host result application, and state commit separately. Report end-to-end p50/p95, completed TPS, bytes per tick and number of synchronous GPU waits. Use GPU timestamps for device execution and host timestamps for thread latency.
- Run the existing 1k–1M gate sweep for active and stable inputs. Add a mixed board with CPU gates or buses, a large board with a small viewport, and an edit-heavy workload. Measure first-tick and topology rebuild times separately.
- Keep two benchmark contracts. One publishes the whole circuit every tick for parity with the existing benchmark. The other measures the editor or headless workflow at its real observation cadence. In the latter, finish queued GPU work and perform the final snapshot before stopping the timer.

## 2. Keep combinational state on the GPU

Start with worlds eligible for the existing net-reduction path. Keep current and pending signal buffers and input inversion flags on the device. Upload initial state once per topology and queue external drives and configuration changes at tick boundaries. Preserve the old rules for an untouched wire, a low driver, a high driver, short-circuited indicators and feedback. A GPU commit publishes pending state for the next tick and clears the per-tick driver flags.

Expose three ways to read results:

| Consumer | Readback |
| --- | --- |
| Headless stepping without probes | None during ordinary ticks. Wait for completion before reporting throughput. |
| Editor, probes and overlays | Read a compact list of visible or watched signals. Refresh the viewport once per displayed frame. Tag snapshots with their completed tick. Explicit headless probes can request an exact tick. |
| Save, copy, topology edits, CPU fallback | Flush outstanding work at a tick boundary and synchronize the state the operation needs. Start with a full snapshot for saves, topology edits and backend switches. Optimize narrower cases once correctness is established. |

Give the renderer its own published snapshot. Keep signal reads used by simulation, gate callbacks, and exports tied to the authoritative tick. Use a gather shader and Terni's batched readback range for viewport and probe data. Keep a full-readback test mode as the Classic comparison point.

**Acceptance targets** — zero routine state uploads and readbacks for a static headless board with no probes or changed inputs, at least 90% less steady-state readback on a large board with a small viewport, and lower end-to-end latency at 100k–1M gates. Measure all three. A smaller transfer alone is insufficient if host application time stays high.

## 3. Reduce waits and optimize the remaining transfers

- After GPU-resident stepping matches Classic, use Terni's submit/wait API to overlap the in-flight tick with rendering or host work. Bound queued work so pause, single-step, save and live edits can observe a known tick without a long drain. Measure input-to-display latency alongside TPS.
- Prototype multiple ticks per submission only after observation and input ordering are verified. Check the pinned Terni API for a bounded sequence of dependent stages. If it cannot express that sequence safely, retain one submission per tick and measure the benefit from reducing waits and readbacks separately.
- In the full-readback and mixed paths, test packing low/high/untouched flags and compacting changes for mostly stable circuits. Compare a fixed packed snapshot with sparse deltas. Include packing, compaction and host decoding in the measurement. Dense activity can favor the packed snapshot.

## 4. Extend readback reduction to mixed circuits

Keep ordered CPU replay as the baseline for flip-flops, delays, PWM, buses, RAM and external I/O. Start by identifying the scalar nets at the CPU/GPU boundary. GPU-only nets can remain resident when ordered CPU execution cannot observe their pending values. Exchange boundary values once per tick. Keep aliasing or ordering-dependent cases on the current hybrid path until their behavior is demonstrated.

Use the same seeded circuit for Classic, current Terni and the optimized path. Compare current and pending signals, sequential state, shared drivers, crossed wires and buses, feedback, edits, save/load and switching backends. Compare the optimized output at every tick in diagnostic mode, and at scheduled snapshots in normal mode. Run on hardware Vulkan and lavapipe. Profile the topology rebuild separately so the throughput work does not make live editing less responsive.

## Adaptive GPU-resident batching

The editor now uses parallel shared-memory workgroups for independent parts of
a circuit and a staged GPU-wide commit when a component exceeds a workgroup's
capacity. Both paths support preview snapshots at 20 or 60 Hz. The [60 Hz
sweeps](../benchmarks/classic-vs-terni/README.md#60-hz-resident-preview-sweep)
measure both independent and connected circuits.

## Original small-circuit implementation

The opt-in desktop headless path runs up to 256 dependent ticks in one
Terni workgroup for circuits eligible for net reduction with up to 256
gates and 2,048 addressed states. It uploads the starting current and
pending values, optional scheduled boundary writes, and inversion flags,
and reads back one final snapshot per headless batch. Stateful or otherwise
unsupported boards keep their ordinary per-tick execution.

In the editor, **Simulation settings → Terni preview refresh** selects 20
or 60 Hz independently of the requested TPS. The setting is saved per user.
On eligible circuits, ordinary bursts carry signal state across dispatches
on the device. The simulation publishes at most one scheduled preview
per interval and the renderer uses the most recent snapshot. A setting of
20 Hz allows up to one scheduled readback every 50 ms, and 60 Hz allows one
about every 16.7 ms. The first publication and exact actions such as
stopping, stepping, saving, copying, or editing can read back sooner.
Unsupported circuits keep their normal per-tick state updates.

The CLI opts in with `--headless MAP TICKS --batch`. The Kotlin API
accepts scheduled writes via `Circuit.stepBatch(count, drives)`.
Intermediate observation can use shorter batches. The current prototype uses word-sized shared state and executes every tick. Bit packing and fixed-point skipping can be evaluated against this baseline. The initial harness
checks every prefix through 64 ticks and additional lengths through 256
against Classic. It also checks switching back to ordinary ticks and
editing topology. A packed trace and visible-only snapshots are separate follow-ups.

To reproduce the parity checks and benchmark, build the native bridge and run:

```sh
./gradlew -PdesktopOnly :desktop:terniNative
./gradlew -PdesktopOnly :desktop:terniCheck :desktop:terniBatchBenchmark \
  -Psamples=5000 -Dlogicdraw.terni.library="$PWD/build/terni/liblogicdraw_terni.so"
```

The small-circuit sweep measures a 200-gate feedback board using Classic,
one-tick Terni, Terni batches of 16, 64 and 256 ticks, and GPU-resident
256-tick bursts with 20 Hz and 60 Hz previews. The timer includes
the final readback and host commit. It reports completed TPS, mean tick latency and p95 batch latency.

### Measured results

The latest run on a Ryzen 7 7800X3D and GeForce RTX 4070 Ti reached 2.25M
completed TPS with 256-tick batches and 2.35M with GPU-resident batches
at either preview rate. See the [benchmark report](../benchmarks/classic-vs-terni/README.md)
for the charts, measurement protocol, and exact counts.

The original and batch shaders matched Classic for the six gate truth
tables. Prefix tests covered feedback, shared and scheduled writes,
switching to regular ticks and topology changes. Parity passed on hardware
Vulkan and on Mesa software Vulkan with validation enabled. The packaged
CLI also returned the expected state after 257 headless ticks.
