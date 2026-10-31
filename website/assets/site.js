const slider = document.querySelector('#zoom-range');
const output = document.querySelector('#zoom-output');
const readout = document.querySelector('#zoom-readout');
const cropScenes = [...document.querySelectorAll('.viewfinder__scene--crop')];
const sceneImages = [...document.querySelectorAll('[data-scene-image]')];

function updateFraming() {
  if (!slider || !cropScenes.length) return;
  const scale = Number(slider.value);
  output.value = `${scale}×`;
  output.textContent = `${scale}×`;
  readout.textContent = `${scale}×`;
  for (const scene of cropScenes) {
    scene.style.opacity = scene.classList.contains('is-active') ? String(Math.min(1, (scale - 1) / 5)) : '0';
    scene.style.transform = `scale(${1.025 * scale})`;
  }
}

function setScene(sceneName) {
  for (const scene of sceneImages) {
    const active = scene.dataset.sceneImage === sceneName;
    scene.classList.toggle('is-active', active);
    if (active && !scene.hasAttribute('src') && scene.dataset.sceneSrc) scene.src = scene.dataset.sceneSrc;
  }
  updateFraming();
}

slider?.addEventListener('input', updateFraming);
updateFraming();

if ('IntersectionObserver' in window && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
  const sceneObserver = new IntersectionObserver((entries) => {
    for (const entry of entries) if (entry.isIntersecting) setScene(entry.target.dataset.scene);
  }, { rootMargin: '-40% 0px -40% 0px', threshold: 0 });
  document.querySelectorAll('[data-scene]').forEach((section) => sceneObserver.observe(section));

  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      }
    }
  }, { rootMargin: '0px 0px 200px 0px', threshold: 0 });
  document.querySelectorAll('.section').forEach((section) => {
    if (!section.classList.contains('hero')) {
      section.querySelectorAll('h2, p, article, li, details, table, pre').forEach((item, index) => {
        item.dataset.reveal = '';
        item.style.setProperty('--reveal-delay', `${Math.min(index * 55, 220)}ms`);
        observer.observe(item);
      });
    }
  });
  let revealQueued = false;
  const revealPassedContent = () => {
    if (revealQueued) return;
    revealQueued = true;
    requestAnimationFrame(() => {
      revealQueued = false;
      for (const item of document.querySelectorAll('[data-reveal]:not(.is-visible)')) {
        const rect = item.getBoundingClientRect();
        if (rect.bottom < 0 || rect.top < innerHeight + 200) {
          item.classList.add('is-visible');
          observer.unobserve(item);
        }
      }
    });
  };
  addEventListener('scroll', revealPassedContent, { passive: true });
  addEventListener('scrollend', revealPassedContent, { passive: true });
  addEventListener('resize', revealPassedContent, { passive: true });
  addEventListener('hashchange', () => setTimeout(revealPassedContent, 0));
  revealPassedContent();
} else if (!('IntersectionObserver' in window)) {
  for (const scene of sceneImages) {
    if (!scene.hasAttribute('src') && scene.dataset.sceneSrc) scene.src = scene.dataset.sceneSrc;
  }
}
