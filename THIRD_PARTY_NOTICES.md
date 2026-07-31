# Third-party notices

Foreman distributes or links the following declared runtime dependency families.
This list is a release-review aid, not a substitute for the license text and
notices supplied by each dependency.

| Component | Version source | License |
| --- | --- | --- |
| `websockets` | `requirements.txt` | BSD-3-Clause; the vendored license is at `linux/vendor/websockets-16.1.1.dist-info/licenses/LICENSE` |
| Anthropic Claude Agent SDK 0.3.220 | `linux/claude_bridge/package-lock.json` | Anthropic commercial terms; package license text is packaged with the optional production Node dependency |
| React and React DOM | `web/package-lock.json` | MIT |
| react-markdown and its runtime dependency graph | `web/package-lock.json` | package-specific license fields, predominantly MIT |
| AndroidX Activity, Core, Lifecycle, and Compose | `android/app/build.gradle.kts` and Gradle resolution metadata | Apache-2.0 |
| Kotlin coroutines and serialization | `android/app/build.gradle.kts` and Gradle resolution metadata | Apache-2.0 |

Before publishing a release, regenerate and review the complete resolved web and
Android dependency inventories, retain every required copyright/license notice,
and confirm that the packaged notice set is complete. The current repository
does not automate that legal review.
