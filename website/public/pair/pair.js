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
    document.getElementById('message').textContent = '配对请求不完整，请在新设备重新生成。';
    return;
  }
  document.getElementById('message').textContent = '请在原设备选择球队，核对新设备显示的同一短语后再授权。';
  open.href = `baseballmaster://pair#${token}`;
  copy.onclick = async () => {
    try {
      await navigator.clipboard.writeText(`https://baseballmaster.cc/pair#${token}`);
      document.getElementById('status').textContent = '配对请求已复制。';
    } catch {
      document.getElementById('status').textContent = '请使用浏览器的分享或复制地址功能，再回到 App 粘贴。';
    }
  };
}
render();
addEventListener('hashchange', render);
