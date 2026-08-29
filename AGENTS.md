# Foreman agent guidelines

## UX state retention

- Treat user-facing state described as retained, remembered, or preserved as durable unless the product requirement explicitly says otherwise.
- For web and Android, check all relevant lifecycle boundaries: screen navigation, component recreation, browser tab close/reopen, page reload, app force-close/relaunch, and host switching.
- Scope host-specific state by stable host ID, remove it when that host is forgotten, and add tests for both durable restoration and cross-host isolation.
- When implementing a UI preference or organizer state, proactively distinguish transient screen state from app-level state and persisted state before considering the work complete.
