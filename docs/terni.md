# Optional Terni simulation

Classic remains the default on every platform. Linux and Windows desktop builds can opt into Vulkan simulation with `-Dlogicdraw.backend=terni`. Android, iOS, and other desktop platforms use Classic.

## Build and run

The application uses its existing Gradle wrapper and JDK 8. The optional `-PdesktopOnly` build excludes the Android and RoboVM build plugins, so desktop development does not require the legacy mobile toolchains. The default Gradle project still includes both mobile targets.

The native bridge requires Git, CMake 3.21 or newer, a C++20 compiler (GCC, Clang, or Visual Studio 2022), JDK headers, and a Vulkan SDK or distribution development packages providing the loader, headers, and `glslc`. Set `JAVA_HOME` to a JDK matching the intended JVM architecture. At runtime, Terni requires a Vulkan 1.3 compute device with synchronization2 and the Vulkan loader. The bridge embeds SPIR-V compiled during the CMake build. The Java application does not need a shader compiler at runtime.

On Linux, from the repository root:

```sh
./gradlew -PdesktopOnly :desktop:terniNative
export LOGICDRAW_TERNI="$PWD/build/terni/liblogicdraw_terni.so"
./gradlew -PdesktopOnly :desktop:run -Dlogicdraw.backend=terni \
  -Dlogicdraw.terni.library="$LOGICDRAW_TERNI"
```

On Windows, use a 64-bit JDK and Visual Studio toolchain with the Vulkan SDK:

```powershell
.\gradlew.bat -PdesktopOnly :desktop:terniNative
$terni = (Resolve-Path .\build\terni\Release\logicdraw_terni.dll).Path
.\gradlew.bat -PdesktopOnly :desktop:run "-Dlogicdraw.backend=terni" "-Dlogicdraw.terni.library=$terni"
```

Single-configuration Windows generators generally output the DLL directly under `build/terni`. To use CMake directly:

```sh
cmake -S desktop/native -B build/terni -DCMAKE_BUILD_TYPE=Release
cmake --build build/terni --config Release
```

CMake fetches [Terni at revision `7b2e534b2b80a147f903c6d85f09806ffd17e318`](https://github.com/ZBager/Terni/tree/7b2e534b2b80a147f903c6d85f09806ffd17e318). For an offline build, use a checkout of that revision and pass `-DFETCHCONTENT_SOURCE_DIR_TERNI=/absolute/path/to/Terni` to CMake or `-PterniSource=/absolute/path/to/Terni` to the Gradle task. The bridge links against Terni's public C++ `ComputeRunner` through `Terni::runtime`. LogicDraw credits Terni by Rafał Zając under the [LGPL-2.1 linking exception](https://github.com/ZBager/Terni/blob/7b2e534b2b80a147f903c6d85f09806ffd17e318/LICENSE-EXCEPTION).

The desktop distribution JAR accepts the same JVM properties before `-jar`. The native library can be specified by an absolute path or found with `java.library.path` when `logicdraw.terni.library` is omitted. If the bridge, driver, or native tick fails, LogicDraw reports the reason and uses Classic in that world. Reopen the project to retry. Add `-Dlogicdraw.terni.software=true` to permit a CPU Vulkan device for testing. The title identifies the device type, backend mode, sampled net count, accelerated gate count, simulation time and GPU compute time. It also shows the editor's current tick rate, FPS, and total allocated state nodes.


For small circuits eligible for batched Terni, open the gear icon next to
the clock controls and set **Terni preview refresh** to **20 Hz** or **60 Hz**.
This limits routine GPU readbacks independently of the **Tickrate** field.
The preview can miss activity between snapshots. Pausing and stepping
synchronize exact state. See the [performance plan](simulation-performance-plan.md)
for batch limits and behavior.

## How it works

LogicDraw owns the world, clock, topology, signal buffers and component state. The backend executes the evaluation phase of a tick. The same world code applies queued edits before evaluation and commits the next-state buffer after it. Input events, settings edits and saves synchronize with ticks. Closing the editor releases its runner.

The topology compiler flattens AND, NAND, OR, NOR, XOR, and XNOR gates into five-word rows containing an opcode and up to four input indices. An input row lists up to four wire State IDs remapped to compact net slots. An existing shared State ID becomes one slot. Each axis of a crossed wire uses the State ID established by Classic's connectivity code. Both Input and Controller terminals use their current inversion setting.

JNI sends the topology at initialization and uses one bulk call per tick. It uploads the sampled current net values and input inversion flags, runs the Terni shader, and reads back one contiguous result buffer in the same Vulkan submission. The wait is synchronous because the world needs the result before its next tick. A structural edit invalidates the circuit once, after queued operations and Classic's connectivity updates have settled. The next tick rebuilds the flattened topology. A long rebuild can stall that tick. Unchanged gate objects retain their runtime state.

- **Net reduction** runs when all tickable gates compile and private output states have independent writers. A shader invocation evaluates a gate. Integer atomic OR combines wire drivers and marks that a write occurred. Unvisited input indicators and undriven nets retain their pending values. The host merges wire results through Classic's existing `State.setActive` to honor writes made earlier in the tick, then commits.
- **Hybrid** runs mixed circuits and layouts with dependent writers. The GPU returns each accelerated gate's value along with the inputs visited during its evaluation. Kotlin applies these results in the original tickable order, interleaved with Classic components. Classic's shared-wire resolution and input indicator side effects are preserved.
- **Classic components** include T flip-flops, Memory (D flip-flop), delay, PWM, half-adder, programmer, RAM, serial IO, keyboard and stop/debug gates. They run their existing implementations in a mixed circuit. A world containing only these components reports Classic.

AND with zero inputs is false, XOR is parity, and NAND, NOR and XNOR invert their underlying results. OR and AND visit inputs in Classic's short-circuit order. Tick reads see current state, while writes affect pending state. In feedback and long wire networks this gives the same tick boundary as Classic. Save formats and persisted fields remain the same. Backend switching through `ClassicWorld.setSimulationBackend` preserves the live world. The desktop selector applies at launch.

## Verification and benchmarks

Run the differential harness on the selected Vulkan device:

```sh
./gradlew -PdesktopOnly :desktop:terniCheck -Dlogicdraw.terni.library="$LOGICDRAW_TERNI"
```

It checks both Vulkan paths against the real Kotlin simulator, including four-input truth tables, empty gates, seeded circuits, feedback, shared drivers, long wires, crosses, sequential state, live edits and paste, v1 and v2 saves, backend switching and an unavailable library. It compares the full Classic signal buffers each tick and serialized state across the mixed-circuit run. Terni's checked dispatch also compares forward, reverse and parallel shader execution once per compiled test topology. On Linux, force Mesa software Vulkan by specifying its ICD JSON in `VK_DRIVER_FILES` and allowing software with the property above.

Headless benchmark with 30 warmup ticks and 100 measured ticks per case:

```sh
./gradlew -PdesktopOnly :desktop:terniBenchmark -Dlogicdraw.terni.library="$LOGICDRAW_TERNI"
```

Adjust with `-Psizes=1000,10000,100000,1000000` and `-Pticks=100`. The fixture contains ordinary LogicDraw elements, one input wire per gate, and one driven output wire per gate. The active case changes every input wire each tick. The idle case has stable external signals. Timing includes the world tick, JNI and buffer commit. The separately timed first tick includes topology compilation, initial transfers and Vulkan setup. Native diagnostics report transfer sizes, submission latency, device elapsed and compute time, plus GPU buffer allocation. The device elapsed minus compute includes transfer and synchronization overhead. It is a combined cost. The GPU memory figure covers Terni storage buffers and excludes driver and staging allocations.

For measurements with the actual editor rendering under an X11 session or Xvfb on Linux, or on Windows:

```sh
./gradlew -PdesktopOnly :desktop:terniRenderBenchmark -Pbackend=classic -Psize=100000
./gradlew -PdesktopOnly :desktop:terniRenderBenchmark -Pbackend=terni -Psize=100000 \
  -Dlogicdraw.terni.library="$LOGICDRAW_TERNI"
```

The rendering fixture runs a stable circuit at 1024×768, disables vsync, and reports FPS and simulation TPS after a three-second warmup. Set the measurement duration with `-Pseconds=5`.

### Linux benchmark results

Measured on an AMD Ryzen 7 7800X3D with an NVIDIA GeForce RTX 4070 Ti. Values are headless milliseconds per tick, rounded. The table compares the existing Classic implementation with Terni's net reduction.

| Gates | Activity | Classic mean / p95 | Terni mean / p95 | Terni GPU compute |
| ---: | --- | ---: | ---: | ---: |
| 1,000 | Idle | 0.052 / 0.198 | 0.083 / 0.128 | 0.003 |
| 1,000 | Active | 0.046 / 0.062 | 0.060 / 0.084 | 0.003 |
| 10,000 | Idle | 0.195 / 0.259 | 0.145 / 0.201 | 0.004 |
| 10,000 | Active | 0.208 / 0.243 | 0.126 / 0.145 | 0.003 |
| 100,000 | Idle | 2.634 / 3.358 | 0.923 / 1.085 | 0.007 |
| 100,000 | Active | 2.188 / 2.537 | 0.971 / 1.138 | 0.007 |
| 1,000,000 | Idle | 94.233 / 99.117 | 28.750 / 30.096 | 0.246 |
| 1,000,000 | Active | 86.773 / 93.667 | 26.515 / 27.732 | 0.197 |

For this workload Terni first won at the 10,000-gate sample. At one million gates, the first Terni tick took 850–1,082 ms, GPU storage buffers occupied 76.3 MiB, and each tick uploaded 8.0 MB and read back 12.0 MB. The mean native call took 2.2 ms, including 1.1–1.2 ms submission and wait. Host-side state handling accounts for most of the end-to-end tick. Circuit shape, gate mix and device affect the crossover.

The 100,000-gate rendering benchmark under Xvfb with llvmpipe OpenGL measured 21.7 FPS and 319.6 TPS with Classic, and 22.6 FPS and 695.5 TPS with Terni on the RTX 4070 Ti.

The bridge and differential harness have been exercised on Linux hardware Vulkan and on Linux and Windows with Mesa software Vulkan. Mobile builds remain unverified. Sequential and external-IO transitions execute on the CPU, and hybrid circuits can spend most of a tick in ordered host replay. Current performance work covers combinational offload and bulk transfers. Persistent GPU-side state across ticks, incremental topology buffers, and an independent optimized CPU benchmark remain open.

## Continuous integration and releases

Desktop CI runs the parity harness with software Vulkan on Linux x64 and Windows
x64 and builds the desktop distribution on each platform. Push a new `v*` tag
(for example, `v0.1.0`) to test and publish both platform ZIPs and their SHA-256
checksums. Each ZIP includes the desktop JAR, platform JNI library and licenses.
Run Desktop CI manually with its package option to build the same archives as
workflow artifacts without publishing a release.
