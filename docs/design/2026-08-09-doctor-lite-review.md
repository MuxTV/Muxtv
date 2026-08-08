# Doctor Lite TV review

This review follows the pinned `emil-design-eng` Android TV adaptation at revision `de33dbed000212b54400a33767d1e4d03654db2a`.

| Before | After | Why |
| --- | --- | --- |
| Typed playback observations existed only behind the service boundary. | `Диагностика` is a stable top-level D-pad destination with immediate focus on `Обновить`. | Makes recovery evidence reachable without adding a second player or probe path. |
| Playback failures had no user-readable, secret-safe history. | Bounded cards map typed categories to short actions and never accept locator, headers, credentials or exception text. | Improves state clarity while preserving the redaction boundary. |
| There was no explicit report transfer path. | `Экспортировать отчёт` opens the platform document picker and writes a fixed-name allow-listed text report only after user selection; pending state survives Activity recreation both before selection and while the idempotent write is active. | SAF avoids temporary files, providers and silent persistence; cancellation/failure changes only Doctor presentation state. The saved destination is the user-selected document URI, never a playback/source locator. |
| No Doctor empty/error state existed. | Actual absence and reader failure have distinct messages; refresh remains reachable in both states. | Empty content is not presented as a fault, and the screen has no focus trap. |
| Read-only diagnostic cards had no TV focus state. | Every event card is an immediate D-pad focus target with a high-contrast static border; focus movement drives `LazyColumn` scrolling. | Keeps long histories reachable from a remote without adding decorative motion or a no-op click action. |
| A rejected playback start required leaving context and finding Doctor in primary navigation. | Service-rejected playback exposes a direct, immediate `Диагностика` action and focuses it first; pre-observation failures and normal/loading states remain unchanged. Opening Doctor removes the failed Player entry. | Shortens recovery without presenting unrelated evidence or causing an implicit playback retry on Back. |

Evidence at this checkpoint:

- D-pad journey: `nav-home` → five Right presses → `nav-doctor` → OK → focused `doctor-refresh` is covered by instrumentation source and compiles.
- Long-list traversal from the action row into the first diagnostic event is covered by `DoctorFocusTest`; device execution remains part of the API 26/API 36 PR gate.
- Playback rejection covers direct Doctor activation in instrumentation source; source failures remain deferred until Doctor has a typed source-diagnostic substrate.
- 1280×720 and 1920×1080 device execution is required after the dependency stack is rebased and published.
- No animation, blur, scale, shader or animated layout was added; reduced-motion behavior is therefore unchanged.
- Formatter and navigation JVM tests pass; app instrumentation compiles.
- Static frame/allocation evidence is deferred to the MVP physical-device performance gate.
