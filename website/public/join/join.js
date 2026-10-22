'use strict';
// The secret stays in the URL fragment. This page sends no invitation to a server,
// analytics service or App Store URL, and never redeems an invitation automatically.
const open = document.getElementById('open-app');
const copy = document.getElementById('copy-link');
function render() {
  const token = location.hash.slice(1);
  const valid = /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(token);
  open.hidden = !valid; copy.hidden = !valid;
  document.getElementById('status').textContent = '';
  if (!valid) {
    open.removeAttribute('href'); copy.onclick = null;
    document.getElementById('message').textContent = '邀请链接不完整，请联系球队管理员重新发送。';
    return;
  }
  document.getElementById('message').textContent = '在 App 中核对球队名称和角色，再确认加入。无需注册账号。';
  open.href = `baseballmaster://join#${token}`;
  copy.onclick = async () => {
    try {
      await navigator.clipboard.writeText(`https://baseballmaster.cc/join#${token}`);
      document.getElementById('status').textContent = '邀请链接已复制。';
    } catch {
      document.getElementById('status').textContent = '请使用浏览器的分享或复制地址功能，再回到 App 粘贴。';
    }
  };
}
render();
addEventListener('hashchange', render);
