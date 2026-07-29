# Player proxy measurement baseline — 2026-07-29

## Scope

This report records one descriptive Android TV debug-instrumentation series for repository-owned Player control-plane boundaries. It does **not** measure ExoPlayer preparation, MediaSource creation, network/HLS access, Binder service startup, UI, Surface work, buffering, zapping, first frame, decoder performance, Fire OS or a physical weak TV.

No regression threshold or production optimization is selected from this series.

## Evidence identity

| Field | Value |
|---|---|
| Source head | `a5e8756f03716873531db9da2155f3a7de21bb15` |
| Workflow run | `30478201477` |
| Artifact | `pr-player-proxy-measurement-30478201477-1` |
| Artifact digest | `sha256:59566b3da0d34eb111adb2d471fe23d60b74444344e11d4996bdd8bcd87feba1` |
| Request profile SHA-256 | `de27c2dad7cb740dab5a62189b7ff5da78b851a217d18e1698497fd44c135a75` |
| Build mode | `debug-instrumentation` |
| Threshold applied | `false` |
| Failure count | `0` |

## Environment

| Field | Value |
|---|---|
| Platform | Android TV emulator |
| API | 36 |
| System image | `system-images;android-36;android-tv;x86_64` |
| ABI | `x86_64` (`arm64-v8a` also reported) |
| Model | `sdk_google_atv64_x86_64` |
| CPU cores | 2 |
| Configured RAM | 2048 MB |
| Android memory class | 192 MB |
| Low-RAM flag | `false` |
| Image fallback | `false` |

The runner used two warmup samples, ten retained samples and 1,000 operations per retained sample.

## Results

Normalized values are integer nanoseconds per repository proxy operation.

| Operation | Minimum | p50 | p90 | p95 / max |
|---|---:|---:|---:|---:|
| Request construction | 255,950 ns | 365,202 ns | 543,598 ns | 607,248 ns |
| SET envelope round-trip | 37,969 ns | 78,649 ns | 419,570 ns | 426,223 ns |
| Coordinator install + active clear | 2,048 ns | 3,314 ns | 7,242 ns | 71,287 ns |
| Coordinator cancel-before-install | 541 ns | 1,207 ns | 2,031 ns | 64,217 ns |
| Registry disconnect + reacquire | 8,323 ns | 12,458 ns | 46,866 ns | 63,951 ns |

## Retained normalized samples

### `request-construct`

```text
418943, 282188, 255950, 339963, 440584,
543598, 345937, 456891, 365202, 607248
```

### `setup-envelope-roundtrip`

```text
426223, 245575, 419570, 54582, 77010,
78649, 105678, 37969, 125715, 63109
```

### `coordinator-install-active-clear`

```text
71287, 7226, 7242, 3267, 3603,
3330, 3314, 2048, 2513, 2282
```

### `coordinator-cancel-before-install`

```text
1592, 1336, 1316, 1207, 2031,
64217, 575, 565, 541, 557
```

### `registry-disconnect-reacquire`

```text
29174, 12458, 39246, 26659, 63951,
11010, 46866, 10880, 8323, 12032
```

## Interpretation

1. All five control paths completed 10,000 successful operations with exact typed agreement and no failed sample.
2. Request construction is the largest measured proxy boundary in this synthetic profile, but it is still only object construction and validation—not playback startup.
3. Envelope and coordinator series contain large outliers. One emulator series is insufficient to distinguish ART/JIT, host scheduling, GC or thermal effects from code cost.
4. Registry and coordinator medians do not indicate a reason to replace the current ownership/state contracts.
5. No evidence here supports adding another Player engine, Rust/UniFFI, changing Media3 ownership, or weakening request validation.

## Decision

- Keep the current `PlaybackSetupCoordinator`, `ControllerConnectionRegistry`, request codec and one-process Player ownership unchanged.
- Do not add a benchmark threshold yet.
- Repeat comparable series on current, old-edge and low-RAM virtual profiles before any regression-budget decision.
- Real startup/zapping/decoder claims remain deferred to physical-device and release evidence.
