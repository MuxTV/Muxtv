---
status: accepted
last_reviewed: 2026-08-11
---

# Repository and GitHub hygiene review — 2026-08-11

## Snapshot

- Accepted source: `main@6e852d364db6904e80f87deb9deaba58ec58025a` / merged PR #157.
- Pull requests: 114 total; 91 merged; 23 closed without merge; 0 open.
- Remote branches: 144; 89 are merged-PR heads including protected main; 23 are closed-unmerged PR heads; 32 have no PR.
- Local snapshot before cleanup: 134 branches and 117 attached worktrees.
- Dirty work was preserved before cleanup: Search Paging prototype commit `16c8678b56a59a741f68f70f19ac3407a18dfc1d`, safety tag `safety/search-paging-prototype-20260811`, recoverable local quarantine and verified `pre-s5-hygiene-20260811.bundle`.

## Disposition rules

- Merged PR branches are cleanup candidates only when the live branch tip still equals the recorded PR head SHA.
- The default branch main is always protected and retained, regardless of historical PR head metadata.
- Closed-unmerged branches require an explicit successor/content-containment proof and no post-PR commits.
- No-PR branches require ancestor, identical-tree or empty-diff proof; otherwise they are retained.
- Squash-merge ancestry alone is not deletion proof. Dirty, unknown and unique histories fail closed.
- Comments are added only where supersession metadata is missing; merged PRs receive no hygiene noise.

## Pull-request ledger

| PR | State | Head | Head SHA | Disposition |
| ---: | --- | --- | --- | --- |
| #1 | merged | docs/architecture-foundation | `8a0cc8384a000227ea7baf22cef8df6b3c174ef8` | verify exact live tip; then branch cleanup candidate |
| #2 | merged | docs/deep-architecture-research | `3f0d25ec7989b1d1a07d2a0b2dc75e0f7220791e` | verify exact live tip; then branch cleanup candidate |
| #3 | merged | feat/phase-00-foundation | `60840876148dae6a7d6171471a4ef8412f86e0fa` | verify exact live tip; then branch cleanup candidate |
| #4 | merged | feat/local-validation-foundation | `d3e8653a6b5d0172ff585ad1a9bd660f42722a7e` | verify exact live tip; then branch cleanup candidate |
| #5 | merged | feat/network-foundation | `e7bda189dcb4f59640d9f4cfad86d101027f995a` | verify exact live tip; then branch cleanup candidate |
| #6 | merged | feat/credential-storage | `170978e8cc222b7389c1d992653a6597ef313994` | verify exact live tip; then branch cleanup candidate |
| #7 | merged | feat/m3u-ingestion | `6f3aa8aa6cb4cc3c457a8ca5069c6b28c33bce52` | verify exact live tip; then branch cleanup candidate |
| #8 | merged | feat/source-revisions | `e32c465905718938d6be26744de4b8df4c674ed2` | verify exact live tip; then branch cleanup candidate |
| #9 | closed-unmerged draft | feat/source-refresh-v2 | `5afc1f43fa30a43148a8d6ec005da8cf9eec16b9` | retain until explicit supersession/content proof |
| #10 | merged | feat/source-refresh | `e6a9c83d09fd50124df4afcd9227c0983afcdaea` | verify exact live tip; then branch cleanup candidate |
| #11 | merged | infra/tv-device-harness | `5f460e1b21d75f195e9d8b09d4ffa04e21e45e7f` | verify exact live tip; then branch cleanup candidate |
| #12 | merged | main | `a01aad5ddcf2486571bbd6f80490e00926018535` | protected default branch; retain |
| #13 | merged | feat/source-scheduling | `3999ef6007aaaa37ff732df4c7bcb872cf0e7260` | verify exact live tip; then branch cleanup candidate |
| #14 | merged | feat/playback-catalog | `ccbcc59e545af102260a190c0b2c1753306d3878` | verify exact live tip; then branch cleanup candidate |
| #15 | merged | feat/media3-session | `2a96d56b6b10867f1ca2bffd6782f4098ea785e7` | verify exact live tip; then branch cleanup candidate |
| #16 | merged | feat/channels-browser | `a1b31aa671ab1d7f95081789fd98ec6ec829487e` | verify exact live tip; then branch cleanup candidate |
| #17 | merged | feat/player-screen | `34d5408d584f3becc6d4cf5b1a9756532c435b62` | verify exact live tip; then branch cleanup candidate |
| #18 | merged | feat/source-management | `fe62b329e7239dca3b883201f0f9ccb6f3ef2842` | verify exact live tip; then branch cleanup candidate |
| #19 | merged | feat/source-onboarding | `58d9ab8177e113b88a8f910c7c42c03a4f9e671a` | verify exact live tip; then branch cleanup candidate |
| #20 | merged | feat/source-onboarding-registry | `b3aa8f9a69beb49da9831f30c3874e89d8ee2f32` | verify exact live tip; then branch cleanup candidate |
| #21 | closed-unmerged draft | docs/performance-reliability-hardening | `27ea842d262fd58c4bfbd953be5dda4fbb06b438` | retain until explicit supersession/content proof |
| #22 | merged | perf/catalog-staging-hardening | `88964c91fb9ec4fa98b3bf78cabc6031307e1072` | verify exact live tip; then branch cleanup candidate |
| #32 | merged | feat/source-entry-wizard | `9361cd5dbd81387ae50c6fbbdc54b0dcc41afbcd` | verify exact live tip; then branch cleanup candidate |
| #34 | merged | feat/focus-restoration | `8296d33be0674c0ab1e30df67ce6ba6166627ca6` | verify exact live tip; then branch cleanup candidate |
| #35 | merged | docs/repository-truth | `fbab24db9db5e3bd51c8b76fd958070f79cd7cee` | verify exact live tip; then branch cleanup candidate |
| #36 | merged | feat/media3-okhttp-transport | `4e46540c60086c3dc5c152c9c43debeb07adf5d0` | verify exact live tip; then branch cleanup candidate |
| #37 | merged | feat/media3-controller-lifecycle | `756a15e01a4dd4a8f7e6d83b0ebbc15ab6e5c1db` | verify exact live tip; then branch cleanup candidate |
| #38 | merged | feat/media3-setup-reconnect | `f4c7731dff930200c5cefb77765d0fa37b13b02f` | verify exact live tip; then branch cleanup candidate |
| #41 | merged | docs/repository-truth-after-media3 | `e7a6ad26832e0e16451cca9335d645c13b490069` | verify exact live tip; then branch cleanup candidate |
| #42 | merged | feat/exact-origin-http-playback-approval | `46551218a8d37da9b04b53878b5de073afc03961` | verify exact live tip; then branch cleanup candidate |
| #43 | merged | chore/repository-truth-runner-hygiene | `9e89b7260e71c661b2bd5f2581201c592e98bfe3` | verify exact live tip; then branch cleanup candidate |
| #44 | merged | feat/deterministic-m3u-corpus-foundation | `cfec8992a260a428078a58547017c04fabd58fd4` | verify exact live tip; then branch cleanup candidate |
| #45 | merged | fix/redact-m3u-entry-diagnostics | `49ca243fef98e27c4a87904dcdd07211c0eeace0` | verify exact live tip; then branch cleanup candidate |
| #46 | merged | docs/synchronize-corpus-foundation | `539c22b819b62738b38d3f2b969acb05751f0d5d` | verify exact live tip; then branch cleanup candidate |
| #47 | merged | feat/canonical-corpus-manifest-artifact | `e150e5d4c0219149a12e7bcdde6ba307e57e5c9f` | verify exact live tip; then branch cleanup candidate |
| #48 | merged | feat/corpus-artifact-pair-publisher | `ebafd7d9fc5aa2f2aa03f4f1d9476e56d792b06a` | verify exact live tip; then branch cleanup candidate |
| #49 | merged | docs/synchronize-corpus-artifacts | `181fa03d9b8d92b530fb21c318209adfec76e069` | verify exact live tip; then branch cleanup candidate |
| #50 | merged | feat/corpus-generation-entry-point | `9a15923d6d008e2bc0846c5db30561ecb916fa2a` | verify exact live tip; then branch cleanup candidate |
| #51 | merged | feat/typed-iptv-starter-fixtures | `bb7fb7394d667e540bdff987ffa36c14d597c016` | verify exact live tip; then branch cleanup candidate |
| #52 | merged | docs/synchronize-corpus-entry-fixtures | `77c29762007180f184338b8e4e9c0b83e379837d` | verify exact live tip; then branch cleanup candidate |
| #53 | merged | feat/m3u-parse-measurements | `3a76af3d6f5efc839df671b1eec49b281e0060b6` | verify exact live tip; then branch cleanup candidate |
| #54 | merged | perf/catalog-database-measurements | `04a90586f4107753aafb347124c3f96c8bad2ca6` | verify exact live tip; then branch cleanup candidate |
| #55 | merged | docs/sync-after-room-measurements | `996f9d7da9bd0baf452a99944b8c8ec201d3eb86` | verify exact live tip; then branch cleanup candidate |
| #56 | merged | perf/player-proxy-measurements | `53569e2388f96d8508a5ccabc1c566568af38b11` | verify exact live tip; then branch cleanup candidate |
| #57 | merged | fix/playback-request-header-ownership | `43f4700b6bf982dea003dcdca0ca9bc63e0e0fab` | verify exact live tip; then branch cleanup candidate |
| #58 | merged | docs/synchronize-player-evidence | `9b6582a8a609b3a252353509f796ebe596ea90b3` | verify exact live tip; then branch cleanup candidate |
| #59 | merged | feat/measurement-variance-foundation | `bc8beb220b824983b2e26aa763c1a5cf62b2400f` | verify exact live tip; then branch cleanup candidate |
| #60 | merged | feat/measurement-report-adapters | `a822e7f042545f483caec0a279539480e0619d05` | verify exact live tip; then branch cleanup candidate |
| #61 | merged | feat/measurement-series-orchestrator | `5091d3a1bdc005a5682b5d0915c617f7491885eb` | verify exact live tip; then branch cleanup candidate |
| #62 | merged | docs/post-pr61-truth-sync | `000d4906fc60d1a738ff6a7d5bf80516457c4175` | verify exact live tip; then branch cleanup candidate |
| #63 | merged | feat/xmltv-streaming-parser | `7b1e8cd76f723de3a661a49d10234aa2e48f9de5` | verify exact live tip; then branch cleanup candidate |
| #64 | merged | feat/epg-immutable-revisions | `8bf90403ebde17cf9438a6da6a84fb1cc489fc53` | verify exact live tip; then branch cleanup candidate |
| #68 | merged | feat/epg-bounded-payload-decoding | `b1f812628d61c912ff2a4bf3116c4b0c6c767aed` | verify exact live tip; then branch cleanup candidate |
| #72 | merged | feat/remote-epg-refresh | `ffbc7ea34a459a8bff0a52e796de41ac49c55d4b` | verify exact live tip; then branch cleanup candidate |
| #73 | merged | docs/epg-truth-sync-2026-08-01 | `2e3eb6a89e8a65923f64c3775c04a92b319c1239` | verify exact live tip; then branch cleanup candidate |
| #74 | merged | feat/epg-refresh-state-70 | `1941cfd7e9bdb83b75190c2bfd5cd3d0ff9aa811` | verify exact live tip; then branch cleanup candidate |
| #75 | merged | wip/epg-refresh-access-guard-70 | `a61705cb8d4fb464bf7e6dc5748d3ee4779693c2` | verify exact live tip; then branch cleanup candidate |
| #77 | merged | docs/repository-truth-after-70 | `c526a13475c48c20bf0fb7a41d63ac394bd8d3d3` | verify exact live tip; then branch cleanup candidate |
| #78 | merged | fix/source-refresh-ownership-76 | `527958ebe8770f34f2628f7f2846cae1981d6b16` | verify exact live tip; then branch cleanup candidate |
| #79 | closed-unmerged draft | wip/epg-matching-now-next-71 | `7d38c0f44dc49cc4c5e3903db6e1856bf0347bb1` | retain until explicit supersession/content proof |
| #80 | merged | fix/epg-matching-now-next-71 | `8d6227c1ad6570111246c28875d1bb0aae898321` | verify exact live tip; then branch cleanup candidate |
| #81 | closed-unmerged draft | feat/channels-now-next-29 | `9a8a9cae3eeb6bb21d91ab895cfb1153340e6b5a` | retain until explicit supersession/content proof |
| #83 | closed-unmerged draft | perf/core-allocation-stage1 | `9ac00e9a3f24cadffa24ea1d125a2080c3527972` | retain until explicit supersession/content proof |
| #84 | merged | feat/epg-match-policy-version-82 | `b2788efa64ee37ddd84155fd107346b3091a98a4` | verify exact live tip; then branch cleanup candidate |
| #85 | closed-unmerged draft | perf/epg-matching-allocation-stage2 | `8f14b5e932dbd74132dc19239c9ef905dc2e6a90` | retain until explicit supersession/content proof |
| #86 | closed-unmerged draft | feat/channel-favorites-29 | `0e14d53adef1ac82b768e23ad6b967ac5016215f` | retain until explicit supersession/content proof |
| #87 | closed-unmerged draft | perf/xmltv-allocation-stage2 | `e617bb9c4198758aa7873a802c7b98bc089a627b` | retain until explicit supersession/content proof |
| #88 | closed-unmerged | chore/truth-sync-2026-08-03 | `2cc6b00de763a4708a19884f0aee15ad76bb2ced` | retain until explicit supersession/content proof |
| #89 | closed-unmerged draft | rebuild/epg-matching-allocation-stage2-v8 | `a5de62323265323c9c7d893f52957f99f1218232` | retain until explicit supersession/content proof |
| #90 | merged | rebuild/channels-now-next-room-v8 | `3745c507d551ecd426e1c8dd3f0e6f2124f04980` | verify exact live tip; then branch cleanup candidate |
| #91 | closed-unmerged draft | rebuild/channel-favorites-room-v8 | `db9fd397d897f9d81cc91fc1c5dcba8ae6bb0d99` | retain until explicit supersession/content proof |
| #92 | merged | rebuild/channel-favorites-post-90 | `cdd43173d00f3817555b2c640c411d82a9d75244` | verify exact live tip; then branch cleanup candidate |
| #94 | closed-unmerged draft | feat/search-core-room-v9 | `1e7f9a3226209f003c450fb8776ab6d84246471b` | retain until explicit supersession/content proof |
| #95 | merged | docs/truth-sync-post-favorites-2026-08-04 | `1522d53d5e9fe00e5f3f356482428e29461fc0fd` | verify exact live tip; then branch cleanup candidate |
| #96 | merged | rebuild/search-core-post-favorites | `ab7ae512934529cea7ee2dccf8c1d00fbad70973` | verify exact live tip; then branch cleanup candidate |
| #97 | closed-unmerged draft | perf/ci-host-before-device | `a7f8ca5e9fb357f42c4f5857e55a50938698c8ac` | retain until explicit supersession/content proof |
| #98 | closed-unmerged draft | perf/baseline-profile-foundation | `7746290ee2b72732d4253cec5a1eda1d144d34e2` | retain until explicit supersession/content proof |
| #99 | closed-unmerged draft | fix/single-player-owner-guard | `07aa43fa5066d9f4710d7a5a922a60ed4d114ab5` | retain until explicit supersession/content proof |
| #102 | merged | rebuild/single-player-owner-post-search | `8dc341cff4f2644f8c153850361b2865b9054d57` | verify exact live tip; then branch cleanup candidate |
| #103 | merged | rebuild/ci-host-before-device-post-search | `a6d7597fb6fe25c6699101e947e9d7dc429f0389` | verify exact live tip; then branch cleanup candidate |
| #104 | merged | work/search-tv-foundation | `d3478020b3af05c55fbbe71ee9ec656a7413a405` | verify exact live tip; then branch cleanup candidate |
| #105 | merged | docs/truth-sync-post-search-tv | `0ab931ac15718b3fe690bd04f8dd11c6f1064042` | verify exact live tip; then branch cleanup candidate |
| #106 | merged | work/first-rendered-frame-post-search | `6bc33d8b61d0f687d52cdf6f65ca216035ef369d` | verify exact live tip; then branch cleanup candidate |
| #107 | merged | feat/recent-channels-v10 | `d095fb0e99485f93f9dbed8675c13b0f5ac52537` | verify exact live tip; then branch cleanup candidate |
| #119 | merged | work/epg-gzip-hardening | `a931cdd8d2a9c645bd8a119e5e37fd3f4629988a` | verify exact live tip; then branch cleanup candidate |
| #120 | closed-unmerged | docs/truth-sync-post-first-frame-20260805 | `33ee206b0d64196e0fa4f8617a9e6b079c2d4d74` | retain until explicit supersession/content proof |
| #122 | merged | docs/truth-sync-post-recent-v10 | `efebdd254e5780c2da5801a5471c4af51ed4905f` | verify exact live tip; then branch cleanup candidate |
| #123 | merged | work/active-channel-truth-contract-114 | `3fc9c7b25f7359bb66e6b5d6ef89e60d817ab33f` | verify exact live tip; then branch cleanup candidate |
| #124 | merged | work/database-current-chain-schema-guard-121-v2 | `a2916c40d338d56cae3eb698ca8c751137c35a27` | verify exact live tip; then branch cleanup candidate |
| #125 | closed-unmerged draft | feat/bounded-guide-data-window | `dec5b6edf472c0c13967404246e4d33e57d99707` | retain until explicit supersession/content proof |
| #126 | closed-unmerged draft | work/ci-connected-suite-split-101-v2 | `c79df06cb1b219d31308e448f4d77b083a9b4afb` | retain until explicit supersession/content proof |
| #127 | closed-unmerged draft | work/playback-transport-classification-108 | `4bc9e12c9a29b2fcdacc0df32af7a685e7aa0070` | retain until explicit supersession/content proof |
| #128 | closed-unmerged draft | feat/bounded-guide-data-window-v2 | `985fbda8bd90ebde0f29fc1adc0632a8a05704a2` | retain until explicit supersession/content proof |
| #129 | closed-unmerged draft | work/source-bare-host-normalization-116 | `396424d8f98b4d949ebc157c79f641168c7f6b79` | retain until explicit supersession/content proof |
| #130 | closed-unmerged draft | feat/guide-tv-route-29 | `1d8eb91cc13b668545b39b44992a0696f5f9362f` | retain until explicit supersession/content proof |
| #131 | merged | feat/guide-tv-route-29-v2 | `a5e42d6aaa628b9fe09d6afb37e25ecb7d368773` | verify exact live tip; then branch cleanup candidate |
| #133 | merged | work/provider-readiness-contract-112-v2 | `13ac65c77e8b33538bdd28bf7d16bac8c8b0eda3` | verify exact live tip; then branch cleanup candidate |
| #134 | merged | work/measurement-large-m3u-series-27-v2 | `0a466f77a26bf2378d9a39d1781ec014a548e80d` | verify exact live tip; then branch cleanup candidate |
| #135 | merged | docs/truth-sync-2026-08-08 | `8d6a3793198ea1e901f6463c8a5253af1c0bb529` | verify exact live tip; then branch cleanup candidate |
| #137 | merged | fix/136-pr-evidence-provenance | `02d6ee4b2641e12d88ace83bcd6af510f18bac08` | verify exact live tip; then branch cleanup candidate |
| #138 | merged | work/111-tv-design-craft-restack | `f14b83812650be221bf98470f7c97572158172b8` | verify exact live tip; then branch cleanup candidate |
| #142 | merged | work/140-accepted-main-m3u-evidence | `af721a2dcec0416c10e97b4d29dc4eb781757820` | verify exact live tip; then branch cleanup candidate |
| #143 | merged | work/139-evidence-worktree-provenance | `6dcf58dd9de8d10b0bca7635449c074108efaa3d` | verify exact live tip; then branch cleanup candidate |
| #145 | merged | work/30a-playback-recovery-policy | `4c0074bb5417da261561250a75328cf9739eb9ab` | verify exact live tip; then branch cleanup candidate |
| #148 | closed-unmerged draft | docs/147-truth-sync | `9dda62897132718471dca15b6157c1c50453f07d` | retain until explicit supersession/content proof |
| #149 | merged | upd/mvp-alpha-1 | `4265256fb0e341710941500444f2d10ab5faf6d7` | verify exact live tip; then branch cleanup candidate |
| #150 | merged | upd/playback-runtime-recovery | `5921567761fe18e7f58cea418d33f310748ab1f5` | verify exact live tip; then branch cleanup candidate |
| #151 | merged | upd/playback-observations | `8e04861af7b606773c88dfc1e8900433cde93c40` | verify exact live tip; then branch cleanup candidate |
| #152 | merged | upd/doctor-lite | `9d8c01a92c1064f0ca6b856a96bd6e4b26cd4c61` | verify exact live tip; then branch cleanup candidate |
| #153 | merged | upd/release-identity-r8 | `42bcf7558d7a637847d6a776ed9b3f40eda1c961` | verify exact live tip; then branch cleanup candidate |
| #154 | merged | upd/mvp-truth-sync | `dfbd6b7ce10272c86fce84f0fe54d483f0536880` | verify exact live tip; then branch cleanup candidate |
| #155 | merged | upd/self-hosted-runner-hardening | `62c88d15fa52dd3ce822101b3b7248f00b0b51c4` | verify exact live tip; then branch cleanup candidate |
| #156 | merged | upd/measurement-foundation | `aa9d8b8cc5570ec040c23274be3491b77a3e183f` | verify exact live tip; then branch cleanup candidate |
| #157 | merged | upd/channels-paging | `47560e555d2ec20bdfd7f962c38a8173203c8085` | verify exact live tip; then branch cleanup candidate |

## Remote branches without a PR

- `backup/epg-p1b-before-clean-stack` — retain until ancestor/identical-tree/empty-diff proof.
- `chore/publish-room-schema-v5` — retain until ancestor/identical-tree/empty-diff proof.
- `chore/truth-sync-post-transport-guide-2026-08-06` — retain until ancestor/identical-tree/empty-diff proof.
- `design/bounded-search-2026-08-03` — retain until ancestor/identical-tree/empty-diff proof.
- `docs/synchronize-corpus-artifact-publishing` — retain until ancestor/identical-tree/empty-diff proof.
- `docs/truth-sync-after-133-138` — retain until ancestor/identical-tree/empty-diff proof.
- `feat/source-onboarding-ui` — retain until ancestor/identical-tree/empty-diff proof.
- `plan/reference-adoption-local-validation` — retain until ancestor/identical-tree/empty-diff proof.
- `rebase/playback-catalog` — retain until ancestor/identical-tree/empty-diff proof.
- `rebuild/channels-now-next-29` — retain until ancestor/identical-tree/empty-diff proof.
- `rebuild/epg-match-policy-version-82` — retain until ancestor/identical-tree/empty-diff proof.
- `rebuild/xmltv-allocation-stage2` — retain until ancestor/identical-tree/empty-diff proof.
- `tmp-noop` — retain until ancestor/identical-tree/empty-diff proof.
- `wip/epg-refresh-store-contracts` — retain until ancestor/identical-tree/empty-diff proof.
- `work/alpha-release-evidence-contract-31-v2` — retain until ancestor/identical-tree/empty-diff proof.
- `work/alpha-release-evidence-contract-31` — retain until ancestor/identical-tree/empty-diff proof.
- `work/ci-database-suite-split` — retain until ancestor/identical-tree/empty-diff proof.
- `work/database-current-chain-schema-guard-121` — retain until ancestor/identical-tree/empty-diff proof.
- `work/first-rendered-frame-signal` — retain until ancestor/identical-tree/empty-diff proof.
- `work/measurement-large-m3u-series-27` — retain until ancestor/identical-tree/empty-diff proof.
- `work/portable-backup-envelope-113` — retain until ancestor/identical-tree/empty-diff proof.
- `work/provider-readiness-contract-112` — retain until ancestor/identical-tree/empty-diff proof.
- `work/tv-design-craft-111` — retain until ancestor/identical-tree/empty-diff proof.
- `work/user-unlocked-startup-gate-118` — retain until ancestor/identical-tree/empty-diff proof.
- `work/30a-red2-duplicate` — retain until ancestor/identical-tree/empty-diff proof.
- `work/30a-red3-foreign-channel` — retain until ancestor/identical-tree/empty-diff proof.
- `work/30a-red4-attempt-budget` — retain until ancestor/identical-tree/empty-diff proof.
- `work/30a-red5a-duration-budget` — retain until ancestor/identical-tree/empty-diff proof.
- `work/30a-red5b-deadline` — retain until ancestor/identical-tree/empty-diff proof.
- `work/30a-red6-disposition` — retain until ancestor/identical-tree/empty-diff proof.
- `work/30a-red7-generation` — retain until ancestor/identical-tree/empty-diff proof.
- `work/30a-red8-preference` — retain until ancestor/identical-tree/empty-diff proof.

## Post-clean checkpoint

Pending after the truth/hygiene PR is accepted. The next accepted branch must append final local/remote/worktree counts and the exact retained-exception list; no target count justifies deleting unverified history.
