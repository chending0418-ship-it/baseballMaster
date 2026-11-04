#!/usr/bin/env python3
"""Start only the approved mainland COS prefix; never provision cloud permissions."""
import json
import os
from pathlib import Path
import pwd
import sys
import urllib.request


try:
    assert os.geteuid() == 0
    config = json.loads(Path(sys.argv[1]).read_text())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    assert opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4', timeout=3).read().decode().strip() == config['host']
    gate = json.loads(Path(config['activationFile']).read_text())
    assert gate['dataCopyApproved'] is True
    assert gate['activeHost'] == config['host'] and gate['approvalReference']
    password = Path(config['cosfsPasswordFile'])
    assert password.stat().st_mode & 0o077 == 0
    assert password.read_text().split(':', 1)[0] == config['bucket']
    account = pwd.getpwnam('bmprod-team')
    if config.get('syntheticValidation') is True:
        assert config['activationFile'].startswith('/etc/baseballmaster-candidate-validation/')
        assert config['teamMount'].startswith('/mnt/baseballmaster-candidate-validation-')
        prefix = config['validationTeamPrefix']
        assert prefix.startswith('staging/candidate-validation/') and '..' not in prefix
    else:
        assert config['activationFile'] == '/etc/baseballmaster-production/activation.json'
        assert config['teamMount'] == '/mnt/baseballmaster-production-team'
        prefix = 'production/team'
    point = Path(config['teamMount'])
    assert point.is_dir() and not point.is_symlink() and point.stat().st_uid == 0
    assert point.stat().st_mode & 0o077 == 0, 'Unmounted backing directory must deny application access'
    options = [
        'url=https://cos.ap-shanghai.myqcloud.com', 'passwd_file=' + str(password),
        'uid=' + str(account.pw_uid), 'gid=' + str(account.pw_gid),
        'default_acl=private', 'allow_other', 'default_permissions', 'umask=077', 'mp_umask=077',
        'nosuid,nodev,noexec', 'dbglevel=crit', 'connect_timeout=5', 'readwrite_timeout=15',
        'retries=1', 'stat_cache_expire=1', 'parallel_count=2',
    ]
    args = ['/usr/local/bin/cosfs', config['bucket'] + ':/' + prefix, str(point), '-f']
    for option in options:
        args.extend(['-o', option])
    os.execv(args[0], args)
except Exception:
    print('Mainland storage start blocked; secret details suppressed', file=sys.stderr)
    sys.exit(1)
