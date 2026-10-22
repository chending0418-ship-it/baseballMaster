'use strict';
(() => {
  const dialog = document.getElementById('screenshot-dialog');
  if (!dialog || typeof dialog.showModal !== 'function') return;
  const links = [...document.querySelectorAll('[data-gallery]')];
  const image = document.getElementById('gallery-image');
  const title = document.getElementById('screenshot-title');
  const version = document.getElementById('gallery-version');
  const count = document.getElementById('gallery-count');
  const original = document.getElementById('gallery-original');
  let active = 0;
  let previousOverflow = '';
  function show(index) {
    active = (index + links.length) % links.length;
    const link = links[active];
    image.src = link.href;
    image.alt = link.querySelector('img')?.alt || link.dataset.caption;
    title.textContent = link.dataset.caption;
    if (version) version.textContent = `BASEBALLMASTER · ${link.dataset.version || '2.0'}`;
    count.textContent = `${active + 1} / ${links.length}`;
    original.href = link.href;
  }
  for (const [index, link] of links.entries()) {
    link.addEventListener('click', event => {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      show(index);
      previousOverflow = document.documentElement.style.overflow;
      document.documentElement.style.overflow = 'hidden';
      dialog.showModal();
    });
  }
  dialog.querySelector('.gallery-close').addEventListener('click', () => dialog.close());
  dialog.querySelector('.gallery-previous').addEventListener('click', () => show(active - 1));
  dialog.querySelector('.gallery-next').addEventListener('click', () => show(active + 1));
  dialog.addEventListener('keydown', event => {
    if (event.key === 'ArrowLeft') { event.preventDefault(); show(active - 1); }
    if (event.key === 'ArrowRight') { event.preventDefault(); show(active + 1); }
  });
  dialog.addEventListener('click', event => {
    if (event.target !== dialog) return;
    const bounds = dialog.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
  });
  dialog.addEventListener('close', () => { document.documentElement.style.overflow = previousOverflow; });
})();
