#!/usr/bin/env bash
# Scoped website-owner console deployment. No App artifact or live database is copied.
set -euo pipefail
release="${1:?release name required}"
case "$release" in *[!a-zA-Z0-9._-]*|'') exit 2;; esac
stage="/root/baseballmaster-deploy/$release"
teamroot=/www/server/baseballmaster-collaboration
liveroot=/www/server/baseballmaster-live
newteam="$teamroot/releases/$release"
newlive="$liveroot/releases/$release"
oldteam=$(readlink -f "$teamroot/current")
oldlive=$(readlink -f "$liveroot/current")
nginx=/www/server/panel/vhost/nginx/baseballmaster-v3.inc
auth=/www/server/baseballmaster-collaboration-data/admin-auth.json
liveenv=/etc/baseballmaster-admin-live.env
teamdrop=/etc/systemd/system/baseballmaster-collaboration.service.d/admin.conf
livedrop=/etc/systemd/system/baseballmaster-live.service.d/admin.conf
test ! -e "$newteam"
test ! -e "$newlive"
test -d "$oldteam/collaboration"
test -f "$oldlive/server.mjs"
test -f "$stage/admin-auth.json"
cd "$stage"
sha256sum -c overlay.sha256
install -d -m 700 before
save_config(){ if test -f "$2"; then cp -a "$2" "before/$1"; else touch "before/$1.absent"; fi; }
restore_config(){ if test -f "before/$1.absent"; then rm -f "$2"; else cp -a "before/$1" "$2"; fi; }
save_config nginx "$nginx"
save_config auth "$auth"
save_config liveenv "$liveenv"
save_config teamdrop "$teamdrop"
save_config livedrop "$livedrop"
printf '%s\n%s\n' "$oldteam" "$oldlive" > before/releases.txt
systemctl start baseballmaster-team-backup.service
systemctl is-active --quiet baseballmaster-collaboration.service
systemctl is-active --quiet baseballmaster-live.service
rollback(){
  trap - ERR
  set +e
  restore_config nginx "$nginx"
  restore_config auth "$auth"
  restore_config liveenv "$liveenv"
  restore_config teamdrop "$teamdrop"
  restore_config livedrop "$livedrop"
  ln -sfn "$oldteam" "$teamroot/current.rollback"
  mv -Tf "$teamroot/current.rollback" "$teamroot/current"
  ln -sfn "$oldlive" "$liveroot/current.rollback"
  mv -Tf "$liveroot/current.rollback" "$liveroot/current"
  systemctl daemon-reload
  systemctl restart baseballmaster-live.service baseballmaster-collaboration.service
  /www/server/nginx/sbin/nginx -t && /www/server/nginx/sbin/nginx -s reload
  echo 'Admin deployment failed; prior code and configuration restored.' >&2
  exit 1
}
trap rollback ERR
cp -a "$oldteam" "$newteam"
cp -a "$oldlive" "$newlive"
tar --no-same-owner -xzf overlay.tar.gz -C "$newteam"
cp "$newteam/live/server.mjs" "$newlive/server.mjs"
chown -R root:root "$newteam" "$newlive"
chmod -R go-w "$newteam" "$newlive"
install -m 600 -o baseballmaster-team -g baseballmaster-team admin-auth.json "$auth"
install -m 600 -o root -g root admin-live.env "$liveenv"
install -d -m 755 "$(dirname "$teamdrop")" "$(dirname "$livedrop")"
printf '[Service]\nEnvironment=BM_ADMIN_CONFIG=%s\n' "$auth" > "$teamdrop"
printf '[Service]\nEnvironmentFile=%s\n' "$liveenv" > "$livedrop"
chmod 644 "$teamdrop" "$livedrop"
install -m 644 "$newteam/collaboration/deploy/nginx-v3.conf" "$nginx"
/www/server/nginx/sbin/nginx -t
systemctl daemon-reload
ln -s "$newlive" "$liveroot/current.admin-next"
mv -Tf "$liveroot/current.admin-next" "$liveroot/current"
systemctl restart baseballmaster-live.service
ln -s "$newteam" "$teamroot/current.admin-next"
mv -Tf "$teamroot/current.admin-next" "$teamroot/current"
systemctl restart baseballmaster-collaboration.service
ready=0
for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:8788/api/v3/health >/dev/null && curl -fsS http://127.0.0.1:18088/livestreaming/novideo/health >/dev/null; then ready=1; break; fi
  sleep 0.5
done
test "$ready" = 1
curl -fsS http://127.0.0.1:8788/admin/ >/dev/null
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8788/admin/api/overview)" = 401
/www/server/nginx/sbin/nginx -s reload
systemctl is-active baseballmaster-collaboration.service baseballmaster-live.service baseballmaster-team-backup.timer baseballmaster-team-monitor.timer
trap - ERR
rm -f admin-auth.json admin-live.env
printf 'Admin console deployed: %s\n' "$release"
