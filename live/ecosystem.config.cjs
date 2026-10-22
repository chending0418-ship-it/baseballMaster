// In BaoTa, select Node.js 24.12+ (24.x), fork mode, one process.
module.exports = { apps: [{
  name: 'baseballmaster-live', script: 'server.mjs', cwd: __dirname,
  instances: 1, exec_mode: 'fork', autorestart: true, max_memory_restart: '512M',
  kill_timeout: 10000, time: true,
  env: { NODE_ENV: 'production', HOST: '127.0.0.1', PORT: '8088', TRUST_PROXY: '1',
    LIVE_DB: '/www/server/baseballmaster-live-data/live.sqlite' }
}] };
