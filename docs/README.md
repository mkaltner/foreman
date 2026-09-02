# Foreman documentation

This directory is the long-form documentation source for Foreman. The project
README stays intentionally concise; use this page to find operational,
technical, security, and release detail. The structure is suitable for a future
static documentation site without creating a second source of truth.

## Get started

- [Install and run](install.md) — stable bootstrap, manual installation,
  source checkouts, service commands, Android setup, and uninstalling.
- [User guide](user-guide.md) — hosts, sessions, controls, notifications,
  organization, web, Android, updates, and current limitations.
- [Themes](themes.md) — curated appearance settings and local persistence.
- [Compatibility policy](compatibility.md) — supported protocol, clients,
  upgrades, state, and versioning expectations.

## Understand the system

- [Architecture](architecture.md) — components, data flow, and multi-host
  topology.
- [Protocol v1](protocol.md) — authenticated client messages and events.
- [Security overview](security.md) — deployment boundary, authentication,
  transport, authorization, and update trust.
- [Codex integration](codex-integration.md) — app-server methods, events,
  approvals, and structured input.
- [Claude Code integration](claude-code-integration.md) — managed bridge,
  capabilities, permissions, and external-session limits.

## Install and update securely

- [Bootstrap installer trust model](bootstrap-installer.md)
- [Recoverable server updates](server-updates.md)
- [Android APK self-update](android-apk-updates.md)
- [Release process](releasing.md)

## Project planning and release history

- [Product roadmap](../ROADMAP.md)
- [GitHub issues](https://github.com/mkaltner/foreman/issues)
- [GitHub releases](https://github.com/mkaltner/foreman/releases)
- [v1.0 acceptance record](acceptance-v1.0.0.md)
- [Release notes archive](releases/)

Older acceptance records and release notes remain in this directory for
historical verification.
