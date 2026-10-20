#!/bin/sh
# Explicitly authorized 3.0 service deployment. Never copies the 2.x temporary live database.
set -eu
release="${1:?release name required}"
case "$release" in *[!a-zA-Z0-9._-]*|'') exit 2;; esac
root=/www/server/baseballmaster-collaboration
data=/www/server/baseballmaster-collaboration-data
cos=/lhcos-data/baseballmaster-private-v3
test -d "$root/releases/$release/collaboration"
mountpoint -q /lhcos-data
id baseballmaster-team >/dev/null 2>&1 || useradd --system --no-create-home --shell /sbin/nologin baseballmaster-team
install -d -m 700 -o baseballmaster-team -g baseballmaster-team "$data" "$cos" "$cos/backups" "$cos/journal"
test -f /root/baseballmaster-deploy/team-backup.key
if test ! -f "$data/backup.key"; then install -m 600 -o baseballmaster-team -g baseballmaster-team /root/baseballmaster-deploy/team-backup.key "$data/backup.key"; fi
cmp /root/baseballmaster-deploy/team-backup.key "$data/backup.key"
cat > /etc/baseballmaster-collaboration.env <<'EOF'
NODE_ENV=production
BM_TEAM_DATABASE=/www/server/baseballmaster-collaboration-data/collaboration.sqlite
BM_TEAM_PORT=8788
BM_TEAM_TRUST_PROXY=1
BM_TEAM_REQUESTS_PER_MINUTE=600
BM_TEAM_BACKUP_KEY=/www/server/baseballmaster-collaboration-data/backup.key
BM_TEAM_SAFETY_JOURNAL=/lhcos-data/baseballmaster-private-v3/journal
BM_TEAM_BACKUPS=/lhcos-data/baseballmaster-private-v3/backups
BM_TEAM_BACKUP_STATUS=/www/server/baseballmaster-collaboration-data/backup-status.json
EOF
chmod 600 /etc/baseballmaster-collaboration.env
for file in "$root/releases/$release/collaboration/deploy/"*.service "$root/releases/$release/collaboration/deploy/"*.timer; do install -m 644 "$file" /etc/systemd/system/; done
chown -R root:root "$root/releases/$release"
chmod -R go-w "$root/releases/$release"
ln -s "$root/releases/$release" "$root/current.next"
mv -Tf "$root/current.next" "$root/current"
systemctl daemon-reload
systemctl enable --now baseballmaster-collaboration.service
systemctl restart baseballmaster-collaboration.service
for count in $(seq 1 30); do if curl -fsS http://127.0.0.1:8788/api/v3/health >/dev/null; then break; fi; sleep 1; done
curl -fsS http://127.0.0.1:8788/api/v3/health
systemctl start baseballmaster-team-backup.service
systemctl start baseballmaster-team-monitor.service
systemctl enable --now baseballmaster-team-backup.timer baseballmaster-team-monitor.timer
systemctl is-active baseballmaster-collaboration.service baseballmaster-team-backup.timer baseballmaster-team-monitor.timer
rm /root/baseballmaster-deploy/team-backup.key
