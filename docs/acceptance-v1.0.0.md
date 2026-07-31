# v1.0.0 acceptance record

This record follows the `v1.0.0-rc.*` series. Validate the exact commit and
artifacts proposed for promotion. Do not promote to `v1.0.0` until every
required row is dated, attributed, and marked PASS. Use `N/A` only for an
explicitly documented optional capability.

Record the Linux distribution, Python and Codex versions, browser/OS versions,
phone model and Android version, commit, artifact SHA-256 values, and signing
certificate digest. Use only a trusted LAN or private overlay.

## Automated and artifact gates

- [ ] `python3 scripts/verify_release.py --tag <candidate-tag>`
- [ ] Full Linux compile and unit/integration suite
- [ ] Web tests, typecheck, production build, and clean `web/dist` diff
- [ ] Android unit tests, lint, and signed release build
- [ ] Signed APK version name/code and certificate match `release.properties`
- [ ] Linux archive contents and exclusions verified
- [ ] `SHA256SUMS` verifies every release artifact
- [ ] Complete resolved dependency-license inventory and required notices

## Install, upgrade, and rollback

- [ ] Fresh clone and unprivileged `./install.sh`
- [ ] Service, one-use pairing, web assets, Android pairing, and browser pairing
- [ ] Upgrade from `v0.1.0-alpha.6` preserves configuration, tokens, paired
      clients, and application state
- [ ] Same-version reinstall preserves configuration and state
- [ ] Forced activation failure restores the previous payload, launcher, unit,
      and service while preserving configuration and state
- [ ] Previous-release Linux rollback and candidate reinstall both succeed

## Runtime and interaction matrix

- [ ] Desktop-originated live turn streams deltas and terminal state through
      the shared Desktop runtime without manual refresh
- [ ] Forced shared-attach failure uses the isolated fallback runtime without
      modifying or stopping the Desktop socket/process
- [ ] Prompt, steer, interrupt, one/four images, model, reasoning effort, access,
      search, dashboard, approval, structured input, and safe shutdown
- [ ] Reconnect and process recreation reconcile state without replaying a
      prompt, steer, interrupt, approval, or input response
- [ ] Two real Foreman hosts validate bounded multi-host behavior, stale labels,
      host switching, and host-scoped search/conversations/notifications

## Physical Android

- [ ] Install the signed candidate APK over the prior alpha on a physical phone
- [ ] Pairing, multi-host overview, host dashboard, conversation, prompt, images,
      steer, interrupt, approvals, structured input, and notifications
- [ ] Force-stop/process recreation plus service/network reconnect restore state
      without replay
- [ ] Light/dark/system themes, large font/display size, TalkBack focus/labels,
      rotation, and Android 6.0 minimum-version smoke coverage

## Browsers and HTTPS

- [ ] Current stable Chrome: full interaction matrix, reconnect, durable
      token-free URLs, forget-host behavior, and two-host overview
- [ ] Current stable Firefox: same required matrix
- [ ] Edge smoke test recorded as best-effort, not a stable-release blocker
- [ ] Trusted HTTPS: permission request, completion/attention notifications,
      navigation, and no prompt/title leakage
- [ ] Plain LAN HTTP explains that system notifications are unavailable

## Evidence

| Area | Result | Tester/date | Environment and evidence |
| --- | --- | --- | --- |
| Automated/artifacts | PENDING |  |  |
| Fresh install | PENDING |  |  |
| Upgrade/rollback | PENDING |  |  |
| Shared runtime | PENDING |  |  |
| Fallback runtime | PENDING |  |  |
| Physical Android | PENDING |  |  |
| Chrome | PENDING |  |  |
| Firefox | PENDING |  |  |
| Edge (best-effort) | PENDING |  |  |
| Two real hosts | PENDING |  |  |
| HTTPS notifications | PENDING |  |  |
| Dependency licenses | PENDING |  |  |
