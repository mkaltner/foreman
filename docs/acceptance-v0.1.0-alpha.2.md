# v0.1.0-alpha.2 acceptance

Record the phone model, Android version, Linux distribution, `codex --version`,
APK SHA-256, Foreman commit, and tester/date with the result. Use a trusted LAN
or a private tunnel; do not expose port 8765 publicly.

## Android phone

1. Verify the downloaded APK against `SHA256SUMS`, install
   `foreman-v0.1.0-alpha.2.apk`, and confirm Android reports version
   `0.1.0-alpha.2`.
2. On Linux, run `foreman status`, then `foreman pair`; enter the host, the
   six-digit code, and a device name on Android.
3. Confirm the repository/session list loads and shows an existing session.
4. Open that session and compare its visible user and assistant messages with
   another Codex client.
5. Send a text-only prompt and confirm assistant text arrives incrementally
   before the turn reaches a terminal state.
6. Start a new session, confirm the empty conversation opens immediately, then
   return to the session list and reopen it.
7. In that empty session, choose a non-default installed model, a supported
   reasoning effort, and each access option that is safe in the test repository;
   send a prompt and confirm the selections remain visible.
8. Attach one image plus text, send it, and confirm both the image marker and
   response appear. Repeat with four small images; confirm a fifth is rejected.
9. During a long-running turn, send a steer message and confirm it joins the
   active turn rather than starting a duplicate prompt.
10. Start another long-running turn, interrupt it, and confirm Android reaches
    an interrupted terminal state.
11. While a turn is active, close Foreman from recents, reopen it, and confirm
    the session reconciles without resending the prompt.
12. Restart the Linux service with `foreman restart`, reopen the same session,
    and confirm its transcript and terminal status reconcile.
13. Enable active-turn notifications, background Foreman during one completing
    turn and one approval/input wait, and confirm the completion and attention
    notifications contain no prompt or session-title text.

## Codex Desktop co-presence

1. Start Codex Desktop and record the process and socket-owner PID for
   `$CODEX_HOME/app-server-control/app-server-control.sock`. Confirm exactly one
   reachable listener owns the filesystem socket before starting Foreman.
2. Start or restart Foreman. Confirm its journal reports
   `SHARED_DESKTOP_LIVE_STATUS_AVAILABLE`, it has no app-server child process,
   and `~/.local/state/foreman/codex-app-server.sock` does not exist.
3. Start a turn in Desktop. Without manually refreshing Android, confirm the
   matching session changes to working/thinking, assistant deltas stream, and
   the terminal completion arrives.
4. Stop Foreman. Confirm the recorded Desktop PID remains alive and the Desktop
   socket remains reachable, then restart Foreman.
5. If shared attachment is unavailable, confirm Foreman leaves the Desktop
   socket untouched, uses only `~/.local/state/foreman/codex-app-server.sock`,
   and reports `SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE`. Mark shared live status
   untested; do not treat fallback behavior as co-presence proof.
