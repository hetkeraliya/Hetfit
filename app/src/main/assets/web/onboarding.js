(function () {
  var KEY = 'kestrel.user.plan.v1';
  function load() {
    try { return JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { return null; }
  }
  function save(v) {
    try { localStorage.setItem(KEY, JSON.stringify(v)); } catch (e) {}
  }

  function $(id) { return document.getElementById(id); }

  function parseLines(text) {
    return String(text || '')
      .split(/\n+/)
      .map(function (l) { return l.replace(/^[\s\-*•\d.\)\]]+/, '').trim(); })
      .filter(function (l) { return l.length >= 2 && l.length < 120; })
      .slice(0, 20)
      .map(function (name, i) {
        return { id: 'u' + i + '_' + name.toLowerCase().replace(/\s+/g, '-').slice(0, 24), name: name, sets: 3, reps: 10 };
      });
  }

  function open() {
    var sheet = $('ob-sheet');
    if (!sheet) return;
    var existing = load();
    var ta = $('ob-plan-text');
    if (ta) {
      ta.value = existing && existing.raw ? existing.raw :
        'Push-ups\nSquats\nPlank\nDumbbell curls\nLunges';
    }
    sheet.classList.add('on');
  }

  function close() {
    var sheet = $('ob-sheet');
    if (sheet) sheet.classList.remove('on');
  }

  function finish() {
    var ta = $('ob-plan-text');
    var raw = ta ? ta.value.trim() : '';
    if (!raw) { alert('Type at least one exercise'); return; }
    var items = parseLines(raw);
    if (!items.length) { alert('Could not read exercises — one per line'); return; }
    var plan = { raw: raw, items: items, updated: Date.now() };
    save(plan);
    close();
    if (window.KestrelWorkout && window.KestrelWorkout.applyUserPlan) {
      window.KestrelWorkout.applyUserPlan(plan);
    }
  }

  function wire() {
    // Rebuild panel content for full-screen text plan
    var panel = document.querySelector('.ob-panel');
    if (panel) {
      panel.innerHTML =
        '<h2>Your workout plan</h2>' +
        '<p class="ob-sub">Type what you want to do — one exercise per line. When you start freestyle on the band, this plan opens automatically.</p>' +
        '<textarea class="ob-textarea" id="ob-plan-text" placeholder="Push-ups&#10;Squats&#10;Plank&#10;…"></textarea>' +
        '<p class="ob-hint">Example: 3 sets × 10 reps will be used by default. You can change sets during the session.</p>' +
        '<div class="ob-actions">' +
        '<button type="button" class="ob-skip" id="ob-skip">Skip</button>' +
        '<button type="button" class="ob-next" id="ob-save">Save plan</button>' +
        '</div>';
    }
    var saveBtn = $('ob-save');
    if (saveBtn) saveBtn.onclick = finish;
    var skip = $('ob-skip');
    if (skip) skip.onclick = close;

    // Do NOT auto-open — user builds when they want
    var existing = load();
    if (existing && window.KestrelWorkout && window.KestrelWorkout.applyUserPlan) {
      window.KestrelWorkout.applyUserPlan(existing);
    }
  }

  window.openPlanOnboarding = open;
  window.kestrelGetUserPlan = load;

  // Band freestyle → auto start user plan
  window.kestrelOnBandFreestyle = function () {
    var plan = load();
    if (!plan || !plan.items || !plan.items.length) {
      open();
      return;
    }
    if (window.KestrelWorkout && window.KestrelWorkout.startUserPlan) {
      window.KestrelWorkout.startUserPlan(plan);
    }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', wire);
  else wire();
})();
