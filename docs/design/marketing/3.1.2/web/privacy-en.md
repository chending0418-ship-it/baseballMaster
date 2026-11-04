# BaseballMaster Privacy Policy

Updated and effective November 4, 2026. Covers the iOS app and its team collaboration, text broadcast and viewing services.

<a id="contact"></a>

## 1. Operator and contact

Jason Chen provides the BaseballMaster app and related baseballmaster.cc services. For privacy questions or requests to access, correct or delete personal information, email [chending0418@gmail.com](mailto:chending0418@gmail.com) or contact WeChat ID p18616870148. Provide only the information needed to locate and resolve the issue. Do not send device credentials, administrator recovery codes, complete backups or unrelated player information.

<a id="local"></a>

## 2. Local records and sharing

Scoring does not require registration using a phone number, email address, password or Apple account. The app does not use iCloud or CloudKit to establish team identities. Local teams, player names and numbers, lineups, rules, pitch-by-pitch records, match dates and time zones, corrections, statistics and preferences remain on your device. Rosters and games are not automatically uploaded unless you choose sharing or broadcasting.

Core scoring, statistics, rules and reports work offline. The app does not read contacts, GPS location, microphone or camera data. A ball landing position is a coordinate you select on a field diagram, not your device location. iPad and iPhone Duo layouts do not add location, audiovisual or biometric collection.

When you export PDF, TXT, match posters, complete local backups or team archives, the system share sheet sends files to the destination or recipient you choose; we do not automatically receive them. Files may contain player information. Local exports are not encrypted, so store and share them carefully. System backups and external services you choose operate under their own policies.

<a id="launch"></a>

## 3. Launch screen network requests

After launch, the app may request branded launch screen text and settings from baseballmaster.cc, download a background image from the same domain if needed, and cache validated resources locally. These requests do not contain team rosters, match payloads, device authorization secrets or administrator recovery codes. They do not set launch screen cookies or redirect to other domains. Servers and network infrastructure still process connection information such as IP addresses, request paths, times and responses. Offline, the app uses valid local resources or its built-in screen. This does not enable sharing or broadcasting or prevent offline scoring.

<a id="teams"></a>

## 4. Team collaboration data

When you create a shared team, confirm roster uploads, share games, upload historical games or submit corrections, the relevant data is sent over HTTPS to services we control for team synchronization, recording handoffs, recovery, access control and security:

- Team name, abbreviation and city; player names, English names, uniform numbers, stable identifiers and active/inactive status; and manually confirmed historical player mappings.
- Shared game rules, lineups, dates and time zones, pitch and plate appearance records, ball types and field coordinates, defensive plays and throws, runner outcomes, scores, statistics, revisions and submitted review drafts.
- Your chosen team nickname and device display name, member and device identifiers, roles, operation attribution and timestamps.
- Random identifiers, credential digests, revisions and operation receipts needed for synchronization, invitations, device pairing, administrator recovery, viewing authorization and duplicate-request protection.

Device authorization is held in the local system Keychain and is excluded from ordinary backup and team archive exports. The service stores digests of device credentials, invitation secrets and administrator recovery codes rather than their usable full secrets. Display names are identifiers for collaboration, not verified identities.

Bulk rosters are pasted or entered by you, one player per line in “name,English name,number” format. The app does not continuously read your clipboard. Previewing or cancelling does not add players; confirmation saves locally or submits one batch to the shared service. Necessary batch and retry state may be saved locally. Drafts remain available when a request fails or its result is unknown to help avoid duplicates. Player field charts use existing games you are entitled to access and do not infer missing coordinates.

Invited team members have role-based access. Each team can have up to three administrators. Administrators and recorders can add or edit player names and numbers; ordinary members are read-only, and administrators manage membership and permissions. Share player information only when authorized and avoid unrelated contact details or sensitive information.

<a id="live"></a>

## 5. Live links and historical viewing

Anyone with a valid live text broadcast link can view it without installing the app. It may show team names, player names and numbers, lineups, rules, timers, scores, batter/pitcher information and play text, but no live audio or video. Forwarding expands the audience. A temporary broadcast expires after successful closure, one hour after the game ends, or one hour without synchronization or an online heartbeat. Expiry does not delete the local game or a shared team archive.

Historical viewing for shared games is separately set to permanently open, approved viewers only, or closed. Open links may be viewed by anyone who has them and may later be restricted by an authorized person. Closed web viewing does not remove authorized in-app team access. Restricted viewing processes the applicant’s chosen name, request identifier, random viewing session and authorization result. A necessary Secure, HttpOnly browser session cookie maintains viewing authorization; it is not used for advertising or cross-site tracking. Clearing cookies or switching browsers may require reauthorization.

Viewing pages offer no public game directory and ask search engines not to index them. Revocation stops subsequent access but cannot retrieve screenshots, exports, external caches or offline copies already saved by others.

<a id="retention"></a>

## 6. Retention, backups and deletion

- You control local data and can export or delete it. The app maintains available local automatic backups and upgrade recovery copies; these are not remote cloud backups. Deleting the app may remove its documents. External exports must be removed separately.
- Shared team archives remain until an authorized administrator deletes games or dissolves the team. Closing historical web viewing, ending a game, leaving a team or uninstalling the app does not delete shared games for the team.
- Deleted cloud games have a 30-day recovery period. Dissolving a team immediately closes member access and sharing; recovery within the original permission scope is available for 30 days. Recovery does not automatically restore old memberships, invitations or viewing access. Game content is purged after expiry.
- The shared service creates encrypted daily backups. Routine team backups follow a 30-day retention period. The deletion recovery period and daily-backup expiry operate separately. Restores continue to apply deletion and revocation records rather than reviving revoked access.
- Complete data and configuration backups made for migration verification, rollback and recovery are managed separately from routine team backups and currently have no automatic expiry. Access is restricted to necessary personnel. Copies will be cleaned up to the necessary scope as migration closeout is completed; the routine retention period does not mean every copy is cleared within the same period. Contact the operator on this page to clarify the scope of a personal-data deletion request.
- Necessary operation attribution, objects and timestamps are kept for security and audit. Names are cleared after dissolved-team expiry. Deletion markers, revocation digests and operation identifiers that cannot be used to view or reconstruct game content may remain to prevent stale devices or backups from restoring access.
- Legacy and local-only temporary broadcasts are removed on expiry or successful closure and are not backed up as long-term team archives. Service restarts may end these temporary broadcasts earlier; their removal does not delete the local original.

Recovery cannot guarantee zero data loss. Keep important complete archives outside the app. Restoring a local backup does not overwrite the entire shared service; reconnect and verify shared state afterward.

<a id="providers"></a>

## 7. Providers, regions and security

The website, online services and independent backups use Tencent Cloud infrastructure. The provider processes requests as needed for hosting, networking, storage and security. Current production online services and primary business data are located in Shanghai, China. Routine team backups use independent private object storage in the Shanghai region. Old data, journals and backup copies in Hong Kong, China, remain for migration verification, rollback and recovery, with restricted access; they are not a separate externally writable primary service. These migration copies currently have no automatic expiry and will be cleaned up to the necessary scope as migration closeout is completed. We will update this policy if regions or processing arrangements change.

Transfers use HTTPS, access is controlled by member/device permissions, and backups are encrypted with keys held separately. IP addresses and other connection information are processed to respond to requests and apply rate limits. Operational information is limited to service operation and security troubleshooting. We do not intentionally record rosters, game payloads, link secrets or credentials in logs. No security measure eliminates every risk. Protect your devices and administrator recovery card.

We use no advertising SDK, cross-app tracking, third-party behavioral analytics or additional crash collection SDK, and do not sell game or player data. Apple’s store, TestFlight diagnostics, system sharing, email and other services you select have their own policies.

<a id="rights"></a>

## 8. Access, correction, revocation and deletion

Within your permissions, you can view or export records, correct games, deactivate players, revoke members or devices, leave a team, delete cloud games or dissolve a team. Deactivating a player preserves historical results. Disabling synchronization, leaving or uninstalling is not a substitute for a deletion confirmed by the server.

Use mutually confirmed device pairing or a new invitation when changing devices. Administrators should save their recovery card in advance. A team name or nickname alone cannot restore management rights without a valid device or recovery card. There is no personal phone/email registration account to close using a login password; team exit, revocation, authorized deletion and privacy requests remain available.

Contact us to request access, correction, deletion, limits on public availability or withdrawal of relevant authorization. We verify the requester’s relationship to the information and permissions before acting. We do not need device secrets or a complete database. We cannot restore local information that was never uploaded or backed up.

<a id="updates"></a>

## 9. Minors, support and updates

The app is a match recording tool without a public community or chat. A parent, guardian or authorized team representative should obtain appropriate authorization before entering, sharing or publishing a minor’s information. Prefer display-appropriate nicknames and uniform numbers. If information is inappropriately exposed, stop forwarding it and contact the team administrator and us.

If you contact support, we receive the contact information, messages and attachments you provide solely to resolve the request, and delete them when no longer needed, except where retention is legally required.

When processing changes, we update this page’s date and explain the applicable changes. New upload or public sharing features describe their scope before activation. Upgrading does not automatically share local games. See the [support page](https://baseballmaster.cc/scorekeeper/support-en.html) for operating instructions.
