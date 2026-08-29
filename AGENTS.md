# Foreman agent guidelines

## Definition of done

- Follow a user-facing change through every affected surface instead of stopping at the first component. Check web and Android, settings, creation flows, lists, session views, status indicators, empty states, and reconnect/relaunch behavior as applicable.
- Test the lifecycle implied by the requirement. Navigation-only verification is insufficient for behavior described as remembered, retained, backgrounded, reconnected, or restored.
- Verify the running artifact, not only the source build. For a local web deployment, confirm the installed asset hash is the one served on port 8766 and that `foreman.service` is active.
- Do not report a PR ready while relevant CI or review feedback is unresolved. When fixing a Greptile comment, reply with the concrete fix and resolve the thread after verification.

## UX state retention

- Treat user-facing state described as retained, remembered, or preserved as durable unless the product requirement explicitly says otherwise.
- For web and Android, check all relevant lifecycle boundaries: screen navigation, component recreation, browser tab close/reopen, page reload, app force-close/relaunch, and host switching.
- Scope host-specific state by stable host ID, remove it when that host is forgotten, and add tests for both durable restoration and cross-host isolation.
- When implementing a UI preference or organizer state, proactively distinguish transient screen state from app-level state and persisted state before considering the work complete.

## Cross-client product behavior

- Treat web and Android as two presentations of the same product behavior. Keep capabilities, provider rules, labels, defaults, and state semantics aligned while adapting layout to each platform.
- Do not force desktop layout density onto Android. Check narrow screens and crowded top bars, remove redundant context, and place actions near the task they affect.
- When a provider can be enabled or disabled, update every provider-dependent surface and enforce shared invariants, such as keeping at least one provider enabled.
- Distinguish optional integrations from required runtime dependencies. Do not show a warning merely because an optional companion, provider, or co-presence feature is absent.

## Notifications and presence

- Validate notification work end to end: in-app preference, OS permission, Android channel settings, foreground service behavior, foreground/background state, delivery, navigation target, badge clearing, and privacy-safe content.
- Test the relevant presence matrix: originating client foreground/background, other web and Android clients foreground/background, exact-session focus, and both clients backgrounded.
- A lifecycle event must have one user-visible alert. Use stable notification identities and ownership rules so foreground-service monitoring, reconnects, and event notifications do not create duplicates.
- Cross-client suppression must suppress only redundant alerts while another client is visibly focused on the exact session; it must not suppress delivery when every client is backgrounded.
- Reconcile notification and badge state when the app becomes foregrounded, allowing for asynchronous lifecycle updates before concluding that clearing failed.
- Physical-device and browser testing is part of notification verification because unit tests cannot prove OS delivery, permission defaults, channels, or background restrictions.

## Verification and packaging

- Run focused tests while iterating, then the relevant full checks before handoff. Web changes normally require tests, typecheck, and build; Android changes normally require unit tests and lint/compile checks.
- Do not assemble an Android APK for routine verification unless explicitly requested; the user normally builds through Android Studio. When an APK is requested, provide it with a mobile-clickable file link.
- Building `web/dist` does not deploy it. Copy the built assets into the installed Foreman web payload and verify the served bundle when the user asks to stand up or deploy a branch locally.
- Avoid unnecessary service restarts, but confirm the service is running after deployment so the user is not left to run `foreman start` manually.

## Repository hygiene

- Preserve unrelated user changes and untracked files. Stage only files belonging to the requested change.
- Keep host-scoped client data bounded, keyed by stable host ID, isolated between hosts, and deleted when the host is forgotten.
- Add regression tests for the failure mode the user actually encountered, including the lifecycle boundary that exposed it.
