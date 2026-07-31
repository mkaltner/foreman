# v0.1.0-alpha.6 acceptance record

Do not tag or publish until every required row is dated, attributed, and marked
PASS. Use `N/A` only for an explicitly documented optional capability. Record
Linux distribution, Python and Codex versions, browser/OS versions, phone model
and Android version, commit, artifact SHA-256 values, and signing-certificate
digest. Use only a trusted LAN or private overlay.

## Automated and artifact gates

- [x] `python3 scripts/verify_release.py --tag v0.1.0-alpha.6`
- [x] Linux compile and full unit/integration suite
- [x] Web tests, typecheck, production build, and clean `web/dist` diff
- [x] Android unit tests, lint, and debug/release builds
- [x] Signed release APK verifies; version name is `0.1.0-alpha.6`, version code
      is 7, and signing certificate matches `release.properties`
- [x] Linux archive contains the vendored dependency license and web assets but
      no `__pycache__` or `.pyc` files
- [x] `SHA256SUMS` verifies every release artifact
- [ ] Complete resolved dependency-license inventory and required notices

## Clean install, upgrade, and rollback

- [ ] Fresh clone/install succeeds as an unprivileged user with command traps
      proving `sudo`, pip, venv, Node, and Java are not invoked
- [ ] Service is active; `foreman pair` is single-use; `foreman web` is correct;
      browser assets load; Android and browser pair
- [x] Same-version reinstall succeeds
- [x] Upgrade from the verified `v0.1.0-alpha.5` archive preserves configuration,
      state, paired Android/browser clients, and removes an injected obsolete file
- [x] Forced activation failure restores the previous service, launcher, unit,
      and payload while preserving configuration and state
- [x] Reinstall `v0.1.0-alpha.5` to prove Linux rollback, then reinstall this
      candidate; record the service/version at each step

## Shared runtime and fallback

- [x] Shared attach reports `SHARED_DESKTOP_LIVE_STATUS_AVAILABLE`, reconciles
      existing Desktop state, and stopping Foreman leaves Desktop PID/socket alive
- [ ] Start a new turn in Desktop and confirm its live deltas and terminal state
      reach the candidate without manual refresh
- [x] Forced shared-attach failure leaves the Desktop socket untouched, starts
      only Foreman's fallback, and reports `SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE`
- [x] Automated reconnect proof performs list/read/subscribe reconciliation
      without replaying a prompt, steer, interrupt, approval, or input response
- [ ] Prompt, steer, interrupt, one/four images, model, reasoning effort, access,
      search, dashboard, approval, supported structured input, and safe shutdown
- [ ] Two real hosts demonstrate bounded multi-host overview behavior and stale
      labeling; host-scoped search/conversation/notification behavior is retained

## Physical Android

- [ ] Install the verified release APK over the prior alpha and confirm pairing,
      conversation, prompt, image, steer, interrupt, approval, structured input,
      and completion/attention notifications
- [ ] Force-stop/process recreation and service/network reconnect restore state
      without replay; host switching and two-host overview behave as documented
- [ ] Light/dark/system themes, large font/display size, TalkBack labels/focus,
      rotation, and minimum supported Android smoke tests

## Browsers

- [ ] Current stable Chrome, Firefox, and Edge: pairing, durable token-free URLs,
      dashboard, search, conversation, images, approvals/input, reconnect, forget
      host, and two-host overview
- [ ] Trusted HTTPS deployment: permission request, completion/attention
      notifications, notification navigation, and no prompt/title leakage
- [ ] Plain LAN HTTP correctly explains that system notifications are unavailable

## Evidence

| Area | Result | Tester/date | Evidence or issue |
| --- | --- | --- | --- |
| Automated/artifacts | PASS | Codex / 2026-07-30 | 73 Linux tests; 108 web tests; 48 Android tests; Android lint; debug/release APKs; local archive/checksums |
| Clean install | PARTIAL | Codex / 2026-07-30 | Rootless/offline install integration and real candidate reinstall passed; fresh remote candidate clone and Android pairing remain |
| Upgrade/rollback | PASS | Codex / 2026-07-30 | Real `alpha.5 → alpha.6 → alpha.5 → alpha.6`; config/state hashes stable; obsolete probe removed; forced failure covered by integration test |
| Shared/fallback runtime | PASS | Codex / 2026-07-30 | Live shared attach/safe stop plus live isolated fallback ownership; protocol operations covered by integration tests |
| Physical Android | BLOCKED | Codex / 2026-07-30 | Network ADB device is unauthorized; no APK installation or physical UX evidence |
| Chrome | PARTIAL | Codex / 2026-07-30 | Chrome for Testing 151: pairing, dashboard/search, reload/new-page reconnect, durable token-free URL, shared health; remaining interaction matrix not manual |
| Firefox | BLOCKED | Codex / 2026-07-30 | Firefox 153 headless crashes during font-list initialization in this stripped host |
| Edge | BLOCKED | Codex / 2026-07-30 | No Edge installation available |
| HTTPS notifications | BLOCKED | Codex / 2026-07-30 | No trusted HTTPS test origin available |
| Dependency licenses | BLOCKED | Codex / 2026-07-30 | Complete resolved inventory and packaged notices are still required |
