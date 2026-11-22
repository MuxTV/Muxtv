# C09 Media3 renderer / decoder policy A/B/C

Owner: #360 under #348
Baseline: `MuxTV/Muxtv@baad87b5eb46eca8c608b3e01113c70655e4fb73`
Reference pins: OwnTV `b70af186731fa860da2b99aa05ead5d77f4da927`, OwnTV_Core `630e9c09c80345279e248c336657645300208c09`

This contract is recorded before any C09 production-policy change. It extends, and does not replace, `.work/quality/benchmark-methodology.md`.

## Question

Can Media3 renderer/decoder policy improve useful IPTV playback and decoder recovery while preserving the single `MuxTvPlaybackService`-owned Media3 player/session and the existing #132 seek authority?

## Variants

| Variant | Renderer policy | Initial production disposition |
|---|---|---|
| A | Existing MuxTV Media3 defaults | Baseline/default |
| B | A + Media3 decoder fallback enabled | Experimental |
| C | B + MediaCodec asynchronous queueing force-disabled (synchronous queueing) | Experimental |
| D | Software-first/software rescue after typed decoder/no-first-frame evidence | Separate evidence candidate only; never default in C09 A/B/C |

C is admissible only if the Media3 API pinned by this repository exposes the synchronous-queueing control and the project compiles/tests against that exact pin.

## Correctness gate

Performance comparison is forbidden until every measured variant passes the scenario correctness gate.

Required useful-playback observations:

1. playback attempt is generation-owned and not stale;
2. the expected local fixture is prepared;
3. a video decoder is initialized when the fixture requires video decode;
4. first rendered frame is observed for successful-video scenarios;
5. fatal decoder-initialization failures are recorded distinctly from diagnostic codec errors;
6. no-first-frame is a bounded benchmark outcome, not a new production watchdog;
7. stale-generation callbacks cannot mutate the active runtime measurement snapshot.

`AnalyticsListener.onVideoCodecError` is diagnostic evidence only. It must not be relabeled as a decoder-initialization failure. A decoder-initialization failure is counted only from a Media3 error classification that explicitly means decoder initialization failed.

## Metrics

Primary:

- `useful_playback_success` (correctness outcome; not a latency proxy);
- `tune_to_first_frame_ms`;
- `decoder_init_failure_count` / scenario incidence;
- `no_first_frame_count` / scenario incidence.

Secondary:

- `rebuffer_count`;
- `completed_rebuffer_duration_ms`;
- `dropped_video_frame_count`;
- CPU time where the environment can measure it reproducibly;
- PSS/RSS where the environment can measure it reproducibly;
- actual initialized video decoder name;
- diagnostic video codec error count;
- fallback/rescue count only when directly known from controlled fault injection or an explicit event. Do not infer a fallback count merely from decoder/codec callbacks.

## Runtime measurement ownership

C09 extends the #347 runtime playback measurement boundary only. It must not introduce another measurement owner or another first-frame tracker.

Allowed additions to the existing bounded snapshot are scalar, provider-neutral observations such as:

- initialized decoder name;
- decoder initialization duration;
- decoder initialization failure count;
- diagnostic video codec error count;
- dropped video frame count.

CPU/PSS/RSS and no-first-frame incidence remain benchmark/evidence derivations unless later evidence justifies a separate owner-specific design change.

## Corpus contract

Begin with deterministic, local, non-secret fixtures where licensing and repository size permit:

- H.264/AVC;
- HEVC;
- HLS;
- raw MPEG-TS;
- representative resolution/fps classes;
- controlled decoder-init/fallback fault cases where deterministic injection is possible.

Every corpus used for a result must record both manifest SHA-256 and content SHA-256. Synthetic metadata must not be presented as physical decoder evidence.

## Execution contract

For each evidence set record:

- exact MuxTV SHA;
- exact benchmark/corpus hashes;
- exact variant mapping;
- environment fingerprint/provenance;
- correctness result before performance;
- deterministic seed;
- randomized/interleaved variant ordering;
- warmup rounds separately from measured rounds;
- median/p90/p95/p99 for latency/count distributions where applicable;
- raw evidence references and redaction gate result.

The existing C01 competitive planner/aggregator is authoritative for randomized/interleaved ordering and distribution reporting. C09 must not create a parallel general benchmark framework.

## Evidence authority

Emulator/local synthetic evidence can establish deterministic behavior, callback semantics, stale-generation safety, and relative implementation correctness. It cannot establish vendor MediaCodec compatibility, weak-TV performance, hardware decoder support, HDR/passthrough behavior, or a vendor/device-specific adoption claim.

A vendor/device compatibility conclusion requires physical-device evidence with device/build/decoder provenance. Absence of that evidence requires `DEFER` for device-specific claims.

## Scope exclusions

- no libmpv;
- no LoadControl/buffer tuning (C10/#109);
- no recovery-ladder/candidate-order changes (C11/#30/#184);
- no production no-frame watchdog (C12);
- no audio rescue (C14);
- no #132 seek-authority changes;
- no second player owner.

## Disposition rule

Final disposition is one of `ADOPT`, `ADAPT`, `REJECT`, or `DEFER` and must cite the exact evidence set. A faster tune time cannot override a useful-playback correctness regression. Production default may change from A only in a focused post-evidence implementation slice.