# Foreman roadmap

This roadmap records Foreman's product priorities. GitHub Issues are the source
of truth for implementation status, acceptance criteria, and discussion; this
file provides an ordered, repository-visible index.

Priorities are ordered within each section. New ideas normally enter **Later**
until the current **Now** list is complete or a correctness or security issue
requires reprioritization.

Foreman's current supported boundary remains the one documented in
[Architecture](docs/architecture.md). Items marked **Architecture gate** are
approved product direction for design, not implementation commitments. Their
issues must first produce a reviewed architecture and security decision and
update the applicable boundary documentation before implementation starts.

## Now — finish the current product pass

- [ ] [#69 Fail closed when tagged release assets are
  incomplete](https://github.com/mkaltner/foreman/issues/69).
- [ ] [#55 Add About information to web and
  Android](https://github.com/mkaltner/foreman/issues/55).
- [ ] [#56 Replace accent selection with coherent
  themes](https://github.com/mkaltner/foreman/issues/56).

## Next — updates and releases

- [ ] [#57 Show when a newer Foreman release is
  available](https://github.com/mkaltner/foreman/issues/57).
- [ ] **Architecture gate:** [#58 Add a shared recoverable Foreman server update
  mechanism](https://github.com/mkaltner/foreman/issues/58).
- [ ] **Architecture gate:** [#59 Support Android APK self-update outside the
  Play Store](https://github.com/mkaltner/foreman/issues/59).

## Later — product foundations

### Architecture gate: [#60 First-class project management](https://github.com/mkaltner/foreman/issues/60)

Model projects as durable host-scoped workspaces, optionally backed by Git, and
support safe import, creation, cloning, management, migration, and new-session
selection. The design must resolve Foreman's current no-Git-write boundary
before clone or filesystem mutation is implemented.

### [#61 Session lifecycle management](https://github.com/mkaltner/foreman/issues/61)

Add durable renaming, project reassignment, bulk organization, export, stable
links, and duplicate/fork workflows while keeping archive, hide, forget,
provider deletion, and local-file deletion explicitly distinct.

### Architecture gate: [#62 Multi-agent session orchestration](https://github.com/mkaltner/foreman/issues/62)

Explore durable parent-child sessions, scoped tasks, isolated worktrees,
concurrency limits, progress, follow-up instructions, interruption, and result
collection. The design must resolve Foreman's current no-coordinator,
no-Git-write, and bounded-persistence boundaries before implementation.

### Architecture gate: [#63 OpenCode managed-session provider support](https://github.com/mkaltner/foreman/issues/63)

Add OpenCode as an optional third provider through its documented server and
SDK contracts. Begin with an ownership and live-steering spike, then establish
provider-neutral service and protocol boundaries before implementing the
adapter and complete web/Android parity.

## Recently completed

- [x] Hide provider UI when only one provider is enabled
  ([PR #68](https://github.com/mkaltner/foreman/pull/68)).
- [x] Remember the last-opened session per host across navigation and relaunch
  ([PR #66](https://github.com/mkaltner/foreman/pull/66)).
- [x] Preserve provider session activity and terminal timestamps across
  service restarts
  ([PR #64](https://github.com/mkaltner/foreman/pull/64)).
- [x] Consolidate Android monitoring, attention, and foreground outcomes into
  one user-visible notification
  ([PR #50](https://github.com/mkaltner/foreman/pull/50)).
- [x] Restore pending approvals after navigation and reconnect
  ([PR #46](https://github.com/mkaltner/foreman/pull/46)).
- [x] Make session configuration immutable while active and durable across
  reconnects and restarts
  ([PR #47](https://github.com/mkaltner/foreman/pull/47)).
- [x] Handle newly created Codex sessions before their first prompt
  ([PR #48](https://github.com/mkaltner/foreman/pull/48)).
- [x] Add explicit Android Home-to-Sessions navigation and preserve navigation
  origin
  ([PR #49](https://github.com/mkaltner/foreman/pull/49)).
- [x] Upgrade Android Gradle Plugin to 9.3.2 and Gradle to 9.5.0
  ([PR #43](https://github.com/mkaltner/foreman/pull/43)).

## Tracking convention

- GitHub Issues are the canonical task list and hold status, acceptance
  criteria, design discussion, and implementation links.
- This file communicates product order and larger outcomes by linking those
  issues; it does not maintain a second independent status record.
- GitHub Projects can provide board and timeline views once the issue backlog
  is large enough to benefit from structured status, priority, and dependency
  fields.
- Pull requests should link their issue and update this roadmap when they
  complete or materially change a listed outcome.
