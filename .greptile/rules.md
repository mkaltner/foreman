\# Foreman review rules



\## Product scope



Foreman is a lightweight Android control surface for Codex running through a

Linux companion service.



Flag changes that unnecessarily expand Foreman into:



\- a generic agent orchestration platform;

\- a mobile IDE or terminal;

\- a multi-user collaboration system;

\- a remote filesystem or Git-writing tool;

\- a networking or deployment-management product.



\## Task boundaries



Each pull request corresponds to one numbered implementation task.



Flag:



\- implementation of later tasks;

\- unrelated refactors;

\- speculative abstractions;

\- new behavior not required by the task;

\- changes to frozen protocol, migration, schema, fixture, or planning artifacts

&#x20; without explicit task authorization.



\## Companion architecture



Android communicates only with the Foreman companion.



The companion alone communicates with local Codex app-server.



Codex remains authoritative for Codex execution and thread state.



Git remains authoritative for repository state.



SQLite stores only Foreman-owned coordination and recovery state.



\## Transactions



Flag:



\- SQLite writes that bypass the host-global command coordinator;

\- repository methods that begin or commit transactions;

\- async waits or external I/O inside SQLite transactions;

\- after-commit effects that run before commit;

\- automatic replay when commit or downstream outcome is ambiguous.



\## Persistence



Flag:



\- generic repositories or arbitrary SQL gateways;

\- JavaScript number conversion of unsigned 64-bit sequences;

\- silent coercion of malformed stored values;

\- unbounded reads;

\- private paths leaking outside their dedicated persistence boundary;

\- credentials, pairing secrets, or structured-input secrets stored in plaintext.



\## Security



Flag:



\- shell invocation with interpolated input;

\- bearer credentials or secret values in logs, diagnostics, errors, events,

&#x20; fixtures, or audit records;

\- raw native errors exposed outside internal boundaries;

\- controls enabled under unknown or unsupported Codex compatibility;

\- stale control revisions being accepted.



\## Testing



Flag changes lacking deterministic tests for their task invariants.



Prefer explicit coordination and fault injection over timing sleeps.



Do not request style-only refactors unless they identify a concrete correctness,

security, maintainability, or scope issue.

