# U0 -> U1 Stabilization Order Amendment

**Status:** authoritative ordering amendment for `2026-08-25-u0-u1-stabilization-execution.md`.

**Reason:** the original plan placed the combined dependency compatibility probe #190 before U0. That conflicts with the stronger stabilization authority in umbrella #179 and can unnecessarily consume the single self-hosted runner before the critical UI evidence gate.

## Superseded ordering

The original plan's Task 1 (`Finish #190 compatibility probe without contaminating U0`) must **not** execute before U0. Its position is superseded by this amendment.

## Authoritative execution order

1. **U0 / #188 / #189 first.** Execute the already queued exact-source U0 executor for frozen #189 source `6d26ca89b8ea3404c8d766d790c28133c9a481d1`. Do not enqueue a duplicate run while the existing run remains queued/active.
2. **Classify H1-H4 from U0 evidence.** No production UI mutation before analyzer/runtime evidence.
3. **U1 RED -> minimal GREEN.** Create only evidence-backed regression contracts and production corrections, one owner at a time.
4. **M0 / #178.** Restore measurement correctness before DB/performance conclusions.
5. **#190 compatibility diagnosis only after the stabilization baseline above.** Treat #190 as a combined compatibility probe, never as a mergeable dependency bundle. Split final dependency work by owner issue.

## Single-runner policy

- U0 has priority over #190 on the self-hosted runner.
- Do not create extra AVDs. Repository-owned identities remain exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- Do not rerun jobs solely because they are queued while the Windows runner is asleep/unavailable; allow the existing queued U0 run to acquire the runner after it reconnects.
- Artifact upload/storage failure is classified separately from workload failure.

## Relationship to the original plan

All original U0 characterization predicates, H1-H4 classification rules, U1 RED/GREEN tasks, #180 disposition rules and exact-head verification requirements remain valid. **Only the execution priority/order involving #190 is changed.**
