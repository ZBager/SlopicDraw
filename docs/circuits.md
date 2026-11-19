# Circuits, headless simulation and buses

## Headless simulation

Launch the desktop JAR without opening an editor window:

```sh
java -jar SlopicDraw.jar --headless maps/example.ldmap 100 --read 12,5,h
```

Coordinates are zero-based. The final component of a signal coordinate is
`h` or `v`, which selects the axis at a crossing. Use `--drive 12,5,h,1`
to schedule a high write before the first tick. For a bus use
`--read-bus 12,5,h,7` to probe lane 7, or `--tap 10,5,7` to set a tap.
Repeat probe or drive options as needed. To use Terni, pass the JVM options
described in the [Terni guide](terni.md) before `-jar`.

Kotlin callers can use `me.wieku.circuits.api.simulation.Circuit` to open or
create a board, place and erase elements, flush edits, drive scalar signals,
read scalar or bus signals, configure taps, step and save. Callers own
scheduling. A drive updates the pending signal until the next tick commits
it, and a read returns the committed signal. Close the circuit to release its
backend. The API keeps the world, renderer and backend storage private.

## Saving and migration

New saves use v3 and keep the `.ldmap` and `.ldbp` extensions. The header
contains bounded UTF-8 JSON metadata outside the gzip stream, so browsing
can read the name and dimensions without inflating the circuit. The payload
contains the state pool and independently length-delimited element records
with a type, coordinates and the element's existing binary properties.
Description, authors and a thumbnail string are optional metadata. The chooser
continues to generate topology thumbnails on demand.

Version 1 and 2 maps and blueprints can still be opened. Use the editor's Save
or blueprint Convert command to migrate, or use the headless CLI with zero
ticks and a separate output path:

```sh
java -jar SlopicDraw.jar --headless old.ldmap 0 --save migrated.ldmap
java -jar SlopicDraw.jar --headless migrated.ldmap 0 --save-legacy exported.ldmap
```

`--save-legacy` writes v2. It omits v3 sharing metadata and rejects circuits
with bus elements. For blueprints, callers can pass `legacy = true` to
`SaveManagers.saveBlueprint`. V3 readers can skip appended bytes within a
known element record. Unknown element types cause the entire load to stop
with a diagnostic because dropping a block could change circuit behavior.

## Wiring an eight-bit bus

Select these elements from the normal editor palette:

| Element | Behavior |
| --- | --- |
| `bus` | Joins cardinally adjacent bus cells and carries lanes 0–7. |
| `bus_cross` | Keeps the horizontal and vertical eight-bit routes independent. |
| `bus_in` | Samples adjacent scalar wires and drives one bus lane. |
| `bus_out` | Samples one lane and drives adjacent scalar wires. |

Place an ordinary wire between a gate and a bus tap. Each tap must touch
exactly one bus cell. At a bus crossing, the side the tap touches selects
the corresponding route. An unconnected output tap drives low. Ctrl+right-click
a tap to choose its lane. Lane 0 is the least significant bit when interpreting
the group as a number, and every segment has the same width.

Bits are independent. A high driver overrides a low driver on the same
lane in that tick, and undriven lanes retain their value. The input tap
commits a bus bit at one tick boundary and the output tap commits that bit
to the scalar wire at the next boundary. Topology edits rebuild bus routes
at the normal edit boundary. Splits retain the last value on both halves,
and merges OR the previous lane values. Clipboard operations preserve bus
geometry and tap lane choices. Signal values on new bus cells develop as
the pasted circuit runs. V3 saves preserve the committed bus values.

Bus routing and taps run on the CPU in both simulation backends. Terni
continues to accelerate supported gates in the same circuit. The parity
suite exercises propagation timing, multiple drivers, crossed routes,
splits and merges, rotated clipboard data, and persistence.
