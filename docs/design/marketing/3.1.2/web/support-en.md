# BaseballMaster Support

Updated November 4, 2026. This page explains the iOS app; available features depend on the installed app. Available downloads are shown on the [App Store](https://apps.apple.com/cn/app/id6802063886). Android is currently distributed for testing, with features and operations defined by its test package. Updating this website does not mean an app update is available on the store.

Online services now run in Shanghai, China, with existing team, invitation and viewing URLs retained. Unshared rosters and games stay on the device. See the [privacy policy](https://baseballmaster.cc/scorekeeper/privacy-en.html) for regions, migration rollback copies and deletion arrangements.

## Contact the developer

Jason Chen · [chending0418@gmail.com](mailto:chending0418@gmail.com?subject=BaseballMaster%20Support) · WeChat ID p18616870148.

Include the app version/build, device model, iOS/iPadOS version, time, steps, actual and expected results, and only necessary screenshots. For Duo issues, specify inner screen, outer screen or half-folded posture. For iPad issues, include orientation and window size. For scoring issues, include inning/half-inning and the before/after situation. Redact unrelated player information. Never send device authorization secrets, administrator recovery codes or a complete database, and do not post viewing links publicly. If original records are needed, we will explain the minimum necessary scope first.

## iPad and iPhone Duo

Wide layouts form a scoring workspace with the field, inning scores, current pitcher/batter, on-deck player, lineup, play feed and recent plate appearances. Narrow windows reflow. Wide-screen Pro entry appears within the page; the field and confirmation area expand naturally and the page scrolls as a whole. Larger text may require scrolling.

Rotation, resizing and Duo screen/posture changes reflow the view while retaining the ball type, field position and defensive draft being edited. Cancelling records nothing; confirming commits the play. Undo/redo remain available. Controls that would change game context are locked during editing, so confirm or cancel the current entry first. If something appears lost, avoid repeated confirmation and report the steps.

The iPhone minimum is iOS 16. iPad requires a compatible iPadOS version; Duo requires an OS supported by its model. Layouts adjust to the space provided by the system; larger text and narrow windows can be viewed by scrolling. Local scoring, statistics and reports work offline. Sharing, broadcasts and browser viewing require a connection.

## Bulk player entry

Local team players, opponent rosters and authorized shared team rosters have bulk entry alongside the existing single-player form. Choose the bulk option and paste one player per line:

```text
姓名,英文名,背号
陈昊,Hao Chen,12
周子墨,,00
```

The format is “name,English name,number”. The English name and number may be blank, but keep the commas. Chinese or English commas are supported, numbers can be 0–99 or 00, and a batch can contain up to 200 players. Preview first, fix errors, check duplicate-name/number warnings, then confirm. Confirmation adds new players without overwriting existing players or historical games; previewing/cancelling adds nothing.

Shared bulk entry requires an administrator or recorder role and a connection. Preview the roster, then confirm the batch. If a revision conflicts, permissions change, a network error occurs or the result is unknown, keep the draft and follow refresh/review prompts. Do not create duplicate batches. Retry the original batch to reconcile an unknown result; a batch request does not automatically fall back to individual writes. Use the existing single-player form only after confirming that the earlier batch did not succeed, to avoid adding duplicate players.

## Player field charts

Open Statistics, choose a player, and use the batting section of the player detail. The field chart follows the selected year, team, mode and games. Tap markers or the play list for details. Exact positions, area-only records and missing positions stay distinct; missing historical data is not estimated as an exact coordinate. Empty filters stay empty rather than reverting to all games. These are manually recorded field coordinates, not GPS locations.

## Pitcher inning allowances

Automatically carrying the same pitcher into the next inning does not immediately consume a new inning allowance; actual pitching in that inning does. Explicitly confirming a different pitcher’s appearance still counts an inning. The same inning is not counted twice. Example: with a two-inning limit, A finishes inning one and B replaces A at the start of inning two; each has used one inning and has one remaining. Competition allowances are separate from statistical IP, which uses outs: 0.1 means one out. Review unclear historical records against the actual match rather than changing results solely from the allowance display.

## Basic, Pro and practice

Basic follows observed outcomes step by step. Pro records ball type, landing position, first fielder, actual consecutive throws and separate error responsibility on one field. Pro is a scoring method, with no subscription or in-app purchase. Choose a method in new game setup, pregame preparation or the independent practice inning. Practice does not write to official matches. Supplementing a play completes the original operation and runner outcomes without adding another pitch or plate appearance. Tap the current batter on either side for match/year performance based on existing authorized records.

## Local originals, team sharing and postgame uploads

Local-only use remains available without registration. Selected team/roster/game content uploads only when you choose sharing; keep complete local originals safe. Postgame confirmation uploads and live broadcasts are separate. Before uploading, check the destination team, player mappings and game content. Corrections after changing player mappings still require current permissions and revision checks. On conflict, read the latest state instead of overwriting another person’s work.

A team may have up to three administrators. Administrators manage members/permissions; administrators and recorders can add or edit roster information; ordinary members are read-only. Join by invitation and change devices through mutually confirmed pairing or a new invitation. A team name/nickname alone cannot recover management rights. Transfer an in-progress game through the confirmed handoff flow to avoid simultaneous editing.

## Broadcasts, historical access and privacy

Text broadcasts show scores, lineups and play text, not captured audio or video. Anyone with a valid live link can watch, so share only within your authorization. Temporary broadcasts expire one hour after the game ends or one hour without synchronization/heartbeat, or after successful closure. Local matches, shared archives and temporary broadcasts have separate lifecycles.

Historical web access may be open, approved viewers only or closed. Restricted viewers must apply and be approved; forwarding does not authorize another viewer. Clearing cookies or changing browsers may require reapproval. Closing web access does not delete the game, and revocation cannot retrieve already saved copies.

The app may fetch branded launch screen settings; this does not upload local rosters or games. Offline, local resources are used. See the [privacy policy](https://baseballmaster.cc/scorekeeper/privacy-en.html) for processing scope, regions, retention and deletion.

## Backups, exports and deletion

Use Profile → Backup and Restore to export a complete backup outside the app. PDF/TXT reports are for reading and cannot replace complete recovery files. Local exports are unencrypted: verify recipients and remove unrelated information. Team archives exclude device authorization, administrator recovery codes and viewing permissions.

After restoring an older local backup, reconnect to reconcile shared records; it does not overwrite the entire server. Deactivating players preserves historical results. Leaving a team or deleting the app does not delete the team’s cloud records. Administrators can delete cloud games or dissolve teams; the privacy policy describes the 30-day recovery period and backup expiry. We cannot restore local records that were never uploaded or backed up.

## Historical corrections and pregame lineups

### What if a substitution, position or result was missed?

Use the correction entry at the relevant point in the game history, preview its effects, then save. Correcting a finished game leaves it finished. Review revisions in correction history; the latest correction can be previewed for withdrawal before saving. Check RBI, earned runs and defensive responsibility against the official ruling for complex errors. Saved corrections update local statistics and valid broadcasts after synchronization. Previously exported PDFs, TXT files and images are not replaced automatically; generate them again.

### How do adult slow-pitch games work?

Choose adult slow pitch in new game setup, select time-based or inning-based play, initial ball/strike counts and the foul-ball policy after two strikes. Assign 10 different defensive positions per side, including Free; the batting order may contain more than 10 players, with extra batters not currently fielding. Adjust lineups again before Play Ball. Pregame edits become the initial lineup; in-game substitutions and pitching changes are recorded at their actual point. Record an Illegal ruling, its final outcome and whether a pitch was actually delivered separately; an undelivered penalty ball does not increase actual pitch count. Reaching the time limit does not automatically cut off a plate appearance: follow the official’s decision.

### How do I reorder players or filter statistics?

Use drag handles in lineup adjustment. Away/home offense can be exchanged before the first pitch; recording it locks the choice, and undoing it does not unlock it. Select only the finished games you need in statistics. An empty selection stays empty or zero rather than reverting to all records. After finishing a game, return home from the final card; results remain available from recent games.

## Reports and exports

Use export on game, team or player statistics pages, then save or send PDFs through the system share sheet. Plate appearance reports have detailed and concise text layouts; match posters can be generated on their relevant page. Check available storage and try saving to the Files app if needed. Reading reports are not complete recovery backups.

## Broadcast content and deletion requests

If player information is exposed without authorization, a minor’s privacy is affected or play text is inappropriate, [contact the developer](mailto:chending0418@gmail.com?subject=BaseballMaster%20Privacy). Provide the necessary viewing link or code, the information concerned, the reason and a reply contact. Redact unrelated information in screenshots. Do not send complete backups or publishing credentials. We verify the request and may stop public access where necessary. Publishers can first close the broadcast in the app and confirm success; other viewers should stop forwarding it.

## Connection and display problems

Check your network, app version, permissions and link validity. Avoid repeatedly submitting or recreating shared objects. Save the error text, time and shortest reproduction steps and contact support. For data problems, preserve originals before uninstalling, clearing storage or overwriting backups. Field decisions remain the responsibility of match officials and the competition’s rules; built-in references and reminders assist scoring.
