# Foreman themes

Appearance has two independent settings on web and Android:

- **Color mode** controls whether Foreman follows the system or always uses Light or Dark.
- **Theme** selects a named palette whose backgrounds, surfaces, text, controls, and accent roles are designed together.

Both clients use the same stable theme IDs and names:

| ID | Name | Intent |
| --- | --- | --- |
| `foreman` | Foreman | Signature violet and cool neutral default |
| `harbor` | Harbor | Ocean blue and teal |
| `grove` | Grove | Natural green and warm neutral |
| `ember` | Ember | Warm plum and clay |
| `dune` | Dune | Warm sand, amber, and earthy neutral |
| `slate` | Slate | Cool blue-gray and steady blue |
| `high-contrast` | High Contrast | Maximum separation for text, controls, borders, focus, and status cues |

Every theme has an explicit light and dark palette. Semantic success, working, attention, warning, failure, and full-access roles remain consistent and separate from theme accents. Labels, icons, borders, and selected-state marks supplement color throughout the UI.

High Contrast uses stronger surface boundaries, focus indicators, disabled-state separation, and AAA-level contrast targets for primary, muted, accent, and semantic foreground roles. It remains a named palette independent from System/Light/Dark color mode, so users can combine it with either forced mode or their current OS mode.

## Local persistence and migration

Appearance remains a client-local, host-scoped preference. Web stores version 2 appearance records under `foreman.appearance.v2.<host-id>`; Android stores appearance version 2 in the existing per-host preferences file. Forgetting a host deletes its appearance data with the rest of that host's local presentation state.

Legacy accents migrate deterministically on first load:

| Legacy accent | Theme |
| --- | --- |
| Purple | Foreman |
| Blue, Teal | Harbor |
| Green | Grove |
| Orange, Red, Pink | Ember |

Migration preserves color mode, activity detail, and repository grouping. Unknown or malformed values fall back to Foreman and cannot prevent startup. After migration, the legacy accent key is removed.
