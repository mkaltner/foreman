export const FOREMAN_REPOSITORY_URL = "https://github.com/mkaltner/foreman";
export const FOREMAN_RELEASES_URL = `${FOREMAN_REPOSITORY_URL}/releases`;
export const FOREMAN_LICENSE_URL = `${FOREMAN_REPOSITORY_URL}/blob/main/LICENSE`;
export const FOREMAN_THIRD_PARTY_NOTICES_URL = `${FOREMAN_REPOSITORY_URL}/blob/main/THIRD_PARTY_NOTICES.md`;

export const WEB_CLIENT_VERSION = __FOREMAN_CLIENT_VERSION__;
export const WEB_CLIENT_COMMIT = __FOREMAN_CLIENT_COMMIT__;

export interface AboutSectionProps {
  serverVersion: string | null;
  connected: boolean;
}

export function clientBuildDescription(version: string, commit: string): string {
  return commit && commit !== "unknown" ? `${version} · ${commit}` : version;
}

export function AboutSection({ serverVersion, connected }: AboutSectionProps) {
  return (
    <section className="settings-card about-card" aria-labelledby="about-heading">
      <div className="about-identity">
        <img src="/favicon.svg" alt="Foreman logo" />
        <div>
          <h2 id="about-heading">Foreman</h2>
          <p>Created by Michael Kaltner</p>
        </div>
      </div>
      <dl className="about-versions">
        <div><dt>Server</dt><dd>{serverVersion ?? (connected ? "Unavailable" : "Unavailable while disconnected")}</dd></div>
        <div><dt>Web client</dt><dd>{clientBuildDescription(WEB_CLIENT_VERSION, WEB_CLIENT_COMMIT)}</dd></div>
      </dl>
      <nav className="about-links" aria-label="Foreman links">
        <a href={FOREMAN_REPOSITORY_URL} target="_blank" rel="noreferrer noopener">GitHub repository</a>
        <a href={FOREMAN_RELEASES_URL} target="_blank" rel="noreferrer noopener">Current releases</a>
        <a href={FOREMAN_LICENSE_URL} target="_blank" rel="noreferrer noopener">License</a>
        <a href={FOREMAN_THIRD_PARTY_NOTICES_URL} target="_blank" rel="noreferrer noopener">Third-party notices</a>
      </nav>
    </section>
  );
}
