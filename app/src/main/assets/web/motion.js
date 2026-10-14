/* Premium micro-interactions — no layout thrash */
(function () {
  if (window.__kestrelMotion) return;
  window.__kestrelMotion = true;

  // Ripple-free press class for any button
  document.addEventListener('touchstart', function (e) {
    var t = e.target.closest('button, .rn-item, .rn-chip, .chip, .sub-tab-btn, .rn-btn');
    if (!t) return;
    t.classList.add('is-pressing');
  }, { passive: true });

  document.addEventListener('touchend', clearPress, { passive: true });
  document.addEventListener('touchcancel', clearPress, { passive: true });
  document.addEventListener('mouseup', clearPress, { passive: true });

  function clearPress() {
    document.querySelectorAll('.is-pressing').forEach(function (el) {
      el.classList.remove('is-pressing');
    });
  }

  // Re-trigger card rise when sub-tab content is swapped
  var obs = new MutationObserver(function (muts) {
    muts.forEach(function (m) {
      if (m.type !== 'childList' || !m.addedNodes.length) return;
      m.addedNodes.forEach(function (n) {
        if (n.nodeType !== 1) return;
        if (n.classList && (n.classList.contains('glass-card') || n.classList.contains('rn-card') || n.classList.contains('sheet-stack'))) {
          n.classList.add('just-mounted');
          requestAnimationFrame(function () {
            n.classList.add('mounted');
          });
        }
      });
    });
  });

  function watch() {
    var root = document.querySelector('.tab-pages') || document.body;
    try { obs.observe(root, { childList: true, subtree: true }); } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watch);
  else watch();
})();
