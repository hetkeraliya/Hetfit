(function () {
  var KEY = 'kestrel.plan.v1';
  function load() {
    try { return JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { return null; }
  }
  function save(v) {
    try { localStorage.setItem(KEY, JSON.stringify(v)); } catch (e) {}
  }

  var step = 0;
  var draft = { style: null, equipment: null, focus: null };

  var STEPS = [
    {
      title: 'Build your plan',
      sub: 'What kind of training do you want?',
      key: 'style',
      opts: [
        { id: 'calisthenics', label: 'Calisthenics', desc: 'Bodyweight only' },
        { id: 'gym', label: 'Gym', desc: 'Machines & free weights' },
        { id: 'both', label: 'Both', desc: 'Mix bodyweight + gym' }
      ]
    },
    {
      title: 'Equipment',
      sub: 'What do you usually have?',
      key: 'equipment',
      opts: [
        { id: 'none', label: 'No weights', desc: 'Bodyweight & bands' },
        { id: 'free', label: 'Free weights', desc: 'Dumbbells / barbell' },
        { id: 'full', label: 'Full gym', desc: 'Machines + free weights' }
      ]
    },
    {
      title: 'Focus',
      sub: 'Primary goal for the next weeks',
      key: 'focus',
      opts: [
        { id: 'strength', label: 'Strength', desc: 'Get stronger' },
        { id: 'hypertrophy', label: 'Hypertrophy', desc: 'Build muscle' },
        { id: 'general', label: 'General', desc: 'Stay consistent' }
      ]
    }
  ];

  function $(id) { return document.getElementById(id); }

  function render() {
    var s = STEPS[step];
    if (!s) return;
    $('ob-title').textContent = s.title;
    $('ob-sub').textContent = s.sub;
    var box = $('ob-opts');
    box.innerHTML = s.opts.map(function (o) {
      var on = draft[s.key] === o.id ? ' on' : '';
      return '<button type="button" class="ob-opt' + on + '" data-id="' + o.id + '">' +
        o.label + '<small>' + o.desc + '</small></button>';
    }).join('');
    box.querySelectorAll('.ob-opt').forEach(function (btn) {
      btn.onclick = function () {
        draft[s.key] = btn.getAttribute('data-id');
        render();
      };
    });
    $('ob-next').textContent = step >= STEPS.length - 1 ? 'Start training' : 'Continue';
  }

  function open() {
    var sheet = $('ob-sheet');
    if (!sheet) return;
    step = 0;
    draft = { style: null, equipment: null, focus: null };
    sheet.classList.add('on');
    render();
  }

  function close() {
    var sheet = $('ob-sheet');
    if (sheet) sheet.classList.remove('on');
  }

  function finish() {
    if (!draft.style || !draft.equipment || !draft.focus) return;
    var plan = {
      style: draft.style,
      equipment: draft.equipment,
      focus: draft.focus,
      created: Date.now(),
      daysPerWeek: 4
    };
    save(plan);
    close();
    if (window.KestrelWorkout && window.KestrelWorkout.applyPlan) {
      window.KestrelWorkout.applyPlan(plan);
    }
    if (window.kestrelGoto) window.kestrelGoto('activity');
  }

  function wire() {
    var next = $('ob-next');
    if (next) next.onclick = function () {
      var s = STEPS[step];
      if (!draft[s.key]) return;
      if (step < STEPS.length - 1) { step++; render(); }
      else finish();
    };
    // First open
    if (!load()) {
      setTimeout(open, 600);
    } else if (window.KestrelWorkout && window.KestrelWorkout.applyPlan) {
      window.KestrelWorkout.applyPlan(load());
    }
  }

  window.openPlanOnboarding = open;
  window.kestrelGetPlan = load;

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', wire);
  else wire();
})();
