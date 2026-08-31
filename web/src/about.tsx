import { useState } from "react";
import type { ReleaseUpdateSnapshot } from "./protocol";
import { componentUpdateStatus, type ComponentUpdateStatus } from "./update-status";

export const FOREMAN_REPOSITORY_URL = "https://github.com/mkaltner/foreman";
export const FOREMAN_RELEASES_URL = `${FOREMAN_REPOSITORY_URL}/releases`;
export const FOREMAN_LICENSE_URL = `${FOREMAN_REPOSITORY_URL}/blob/main/LICENSE`;
export const FOREMAN_THIRD_PARTY_NOTICES_URL = `${FOREMAN_REPOSITORY_URL}/blob/main/THIRD_PARTY_NOTICES.md`;

export const WEB_CLIENT_VERSION = __FOREMAN_CLIENT_VERSION__;
export const WEB_CLIENT_COMMIT = __FOREMAN_CLIENT_COMMIT__;
export const WEB_RELEASE_BUILD = __FOREMAN_RELEASE_BUILD__;

export interface AboutSectionProps {
  serverVersion: string | null;
  serverReleaseBuild?: boolean | null;
  connected: boolean;
  releaseUpdates?: ReleaseUpdateSnapshot | null;
  onCheckAgain?: () => Promise<void>;
}

export function clientBuildDescription(version: string, commit: string, releaseBuild: boolean): string {
  const identity = releaseBuild ? version : `${version} (development build)`;
  return commit && commit !== "unknown" ? `${identity} · ${commit}` : identity;
}

function UpdateStatus({ status }: { status: ComponentUpdateStatus }) {
  return <div className={`about-update-status ${status.kind}`}>
    <strong>{status.label}</strong>
    {status.detail && <small>{status.detail}</small>}
    {status.release && <a href={status.release.releaseNotesUrl} target="_blank" rel="noreferrer noopener">
      Release notes for {status.release.tag}
    </a>}
  </div>;
}

export function AboutSection({
  serverVersion,
  serverReleaseBuild = null,
  connected,
  releaseUpdates = null,
  onCheckAgain,
}: AboutSectionProps) {
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState("");
  const browserMatchesServer = connected
    && serverVersion === WEB_CLIENT_VERSION
    && serverReleaseBuild === WEB_RELEASE_BUILD;
  const serverStatus = componentUpdateStatus(
    serverVersion,
    serverReleaseBuild,
    releaseUpdates,
    releaseUpdates?.components.server ?? null,
    "server",
  );
  const webStatus = componentUpdateStatus(
    WEB_CLIENT_VERSION,
    WEB_RELEASE_BUILD,
    releaseUpdates,
    releaseUpdates?.components.server ?? null,
    "server bundle",
  );
  const observedAt = releaseUpdates?.observedAt
    ? new Date(releaseUpdates.observedAt).toLocaleString()
    : null;
  const refreshUnavailable = releaseUpdates?.refreshStatus === "unavailable";
  return (
    <section className="settings-card about-card" aria-labelledby="about-heading">
      <div className="about-identity">
        <img src="/favicon.svg" alt="Foreman logo" />
        <div>
          <h2 id="about-heading">Foreman</h2>
          <p>Created by Michael Kaltner</p>
        </div>
      </div>
      <div className="about-component-list">
        <section aria-labelledby="server-version-heading">
          <h3 id="server-version-heading">
            {browserMatchesServer ? "Connected Foreman installation" : "Connected Foreman server"}
          </h3>
          <p className="about-installed">
            <span>{connected ? "Server" : "Last connected server"}</span>
            <strong>{serverVersion ?? "Unavailable"}</strong>
          </p>
          {browserMatchesServer && <p className="about-installed about-matching-build">
            <span>Bundled web client</span>
            <strong>{clientBuildDescription(WEB_CLIENT_VERSION, WEB_CLIENT_COMMIT, WEB_RELEASE_BUILD)} · matches server</strong>
          </p>}
          <UpdateStatus status={serverStatus} />
        </section>
        {!browserMatchesServer && <section aria-labelledby="web-version-heading">
          <h3 id="web-version-heading">This browser’s Foreman web client</h3>
          <p className="about-installed"><span>Build</span><strong>{clientBuildDescription(WEB_CLIENT_VERSION, WEB_CLIENT_COMMIT, WEB_RELEASE_BUILD)}</strong></p>
          <UpdateStatus status={webStatus} />
        </section>}
      </div>
      <div className="about-refresh">
        <p className="muted">
          {releaseUpdates?.stale && observedAt
            ? `Cached release information from ${observedAt}; the latest check is unavailable.`
            : refreshUnavailable && observedAt
              ? `Using release information observed ${observedAt}.`
              : observedAt
                ? `Release information checked ${observedAt}.`
                : "No validated release information is cached."}
        </p>
        {onCheckAgain && <button
          type="button"
          className="secondary"
          disabled={!connected || checking || releaseUpdates?.refreshStatus === "checking"}
          onClick={() => {
            setChecking(true);
            setCheckError("");
            void onCheckAgain()
              .catch(() => setCheckError("The update check is unavailable; cached information was kept."))
              .finally(() => setChecking(false));
          }}
        >{checking || releaseUpdates?.refreshStatus === "checking" ? "Checking…" : "Check again"}</button>}
        {checkError && <small role="status">{checkError}</small>}
      </div>
      <p className="muted about-scope">Foreman only checks official release metadata. Server installation is tracked in #58; Android APK installation is tracked in #59.</p>
      <nav className="about-links" aria-label="Foreman links">
        <a href={FOREMAN_REPOSITORY_URL} target="_blank" rel="noreferrer noopener">GitHub repository</a>
        <a href={FOREMAN_RELEASES_URL} target="_blank" rel="noreferrer noopener">Current releases</a>
        <a href={FOREMAN_LICENSE_URL} target="_blank" rel="noreferrer noopener">License</a>
        <a href={FOREMAN_THIRD_PARTY_NOTICES_URL} target="_blank" rel="noreferrer noopener">Third-party notices</a>
      </nav>
    </section>
  );
}
