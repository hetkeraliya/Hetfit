(function () {
  var EXERCISES = [
    { id: 'pushup', name: 'Push-up', muscle: 'Chest', equip: 'Bodyweight', diff: 'Beginner', home: true },
    { id: 'dip', name: 'Dip', muscle: 'Chest', equip: 'Bodyweight', diff: 'Intermediate', home: true },
    { id: 'db-press', name: 'Dumbbell Bench Press', muscle: 'Chest', equip: 'Free Weights', diff: 'Intermediate', home: false },
    { id: 'pullup', name: 'Pull-up', muscle: 'Back', equip: 'Bodyweight', diff: 'Intermediate', home: true },
    { id: 'row', name: 'Dumbbell Row', muscle: 'Back', equip: 'Free Weights', diff: 'Beginner', home: true },
    { id: 'lat', name: 'Lat Pulldown', muscle: 'Back', equip: 'Machines', diff: 'Beginner', home: false },
    { id: 'ohp', name: 'Overhead Press', muscle: 'Shoulders', equip: 'Free Weights', diff: 'Intermediate', home: true },
    { id: 'lraise', name: 'Lateral Raise', muscle: 'Shoulders', equip: 'Free Weights', diff: 'Beginner', home: true },
    { id: 'curl', name: 'Dumbbell Bicep Curl', muscle: 'Arms', equip: 'Free Weights', diff: 'Beginner', home: true },
    { id: 'tri', name: 'Tricep Extension', muscle: 'Arms', equip: 'Free Weights', diff: 'Beginner', home: true },
    { id: 'squat', name: 'Dumbbell Squat', muscle: 'Legs', equip: 'Free Weights', diff: 'Beginner', home: true },
    { id: 'rdl', name: 'Romanian Deadlift', muscle: 'Legs', equip: 'Free Weights', diff: 'Intermediate', home: true },
    { id: 'lunge', name: 'Reverse Lunge', muscle: 'Legs', equip: 'Bodyweight', diff: 'Beginner', home: true },
    { id: 'legpress', name: 'Leg Press', muscle: 'Legs', equip: 'Machines', diff: 'Beginner', home: false },
    { id: 'plank', name: 'Plank', muscle: 'Core', equip: 'Bodyweight', diff: 'Beginner', home: true },
    { id: 'rollout', name: 'Ab Rollout', muscle: 'Core', equip: 'Bands & Accessories', diff: 'Advanced', home: true },
    { id: 'hang', name: 'Hanging Knee Raise', muscle: 'Core', equip: 'Bodyweight', diff: 'Intermediate', home: true },
    { id: 'burpee', name: 'Burpee', muscle: 'Cardio', equip: 'Bodyweight', diff: 'Intermediate', home: true },
    { id: 'jump', name: 'Jump Rope', muscle: 'Cardio', equip: 'Bands & Accessories', diff: 'Beginner', home: true },
    { id: 'askip', name: 'A-Skips', muscle: 'Legs', equip: 'Bodyweight', diff: 'Beginner', home: true }
  ];

  var filter = { muscle: null, equip: null, diff: null, fav: false, home: false };
  var selected = {};
  var favorites = {};
  try { favorites = JSON.parse(localStorage.getItem('kestrel.wx.fav') || '{}'); } catch (e) {}

  var session = null;
  var sessTimer = null;

  function $(id) { return document.getElementById(id); }

  function applyPlan(plan) {
    if (!plan) return;
    var label = $('wx-plan-label');
    if (label) {
      label.textContent = (plan.style || '') + ' · ' + (plan.equipment || '') + ' · ' + (plan.focus || '');
    }
    // Pre-filter by plan
    if (plan.equipment === 'none') filter.equip = 'Bodyweight';
    else if (plan.equipment === 'free') filter.equip = null;
    renderFilters();
    renderList();
  }

  function renderFilters() {
    var box = $('wx-filters');
    if (!box) return;
    var chips = [
      { k: 'fav', v: true, label: '★ Favorites' },
      { k: 'muscle', v: null, label: 'Muscle', menu: ['Chest','Back','Shoulders','Arms','Legs','Core','Cardio'] },
      { k: 'equip', v: null, label: 'Equipment', menu: ['Bodyweight','Free Weights','Machines','Bands & Accessories'] },
      { k: 'diff', v: null, label: 'Difficulty', menu: ['Beginner','Intermediate','Advanced'] },
      { k: 'home', v: true, label: 'Home Friendly' }
    ];
    box.innerHTML = chips.map(function (c) {
      var on = (c.k === 'fav' && filter.fav) || (c.k === 'home' && filter.home) ||
        (c.k === 'muscle' && filter.muscle) || (c.k === 'equip' && filter.equip) || (c.k === 'diff' && filter.diff);
      var text = c.label;
      if (c.k === 'muscle' && filter.muscle) text = filter.muscle;
      if (c.k === 'equip' && filter.equip) text = filter.equip;
      if (c.k === 'diff' && filter.diff) text = filter.diff;
      return '<button type="button" class="wx-filter' + (on ? ' on' : '') + '" data-k="' + c.k + '">' + text + '</button>';
    }).join('');
    box.querySelectorAll('.wx-filter').forEach(function (btn) {
      btn.onclick = function () {
        var k = btn.getAttribute('data-k');
        if (k === 'fav') filter.fav = !filter.fav;
        else if (k === 'home') filter.home = !filter.home;
        else if (k === 'muscle') {
          var opts = ['Chest','Back','Shoulders','Arms','Legs','Core','Cardio'];
          var cur = filter.muscle;
          var i = opts.indexOf(cur);
          filter.muscle = i < 0 ? opts[0] : (i === opts.length - 1 ? null : opts[i + 1]);
        } else if (k === 'equip') {
          var opts2 = ['Bodyweight','Free Weights','Machines','Bands & Accessories'];
          var j = opts2.indexOf(filter.equip);
          filter.equip = j < 0 ? opts2[0] : (j === opts2.length - 1 ? null : opts2[j + 1]);
        } else if (k === 'diff') {
          var opts3 = ['Beginner','Intermediate','Advanced'];
          var m = opts3.indexOf(filter.diff);
          filter.diff = m < 0 ? opts3[0] : (m === opts3.length - 1 ? null : opts3[m + 1]);
        }
        renderFilters();
        renderList();
      };
    });
  }

  function filtered() {
    return EXERCISES.filter(function (e) {
      if (filter.fav && !favorites[e.id]) return false;
      if (filter.home && !e.home) return false;
      if (filter.muscle && e.muscle !== filter.muscle) return false;
      if (filter.equip && e.equip !== filter.equip) return false;
      if (filter.diff && e.diff !== filter.diff) return false;
      return true;
    });
  }

  function renderList() {
    var box = $('wx-list');
    if (!box) return;
    var list = filtered();
    box.innerHTML = list.map(function (e) {
      var on = selected[e.id] ? ' on' : '';
      var star = favorites[e.id] ? '★' : '☆';
      return '<div class="wx-item' + on + '" data-id="' + e.id + '">' +
        '<span class="check"></span>' +
        '<div style="flex:1"><strong>' + e.name + '</strong><span>' + e.muscle + ' · ' + e.equip + ' · ' + e.diff + '</span></div>' +
        '<button type="button" data-fav="' + e.id + '" style="border:0;background:transparent;font-size:16px">' + star + '</button>' +
        '</div>';
    }).join('') || '<p style="color:#8E8E93;padding:12px">No exercises match filters.</p>';

    box.querySelectorAll('.wx-item').forEach(function (row) {
      row.onclick = function (ev) {
        if (ev.target && ev.target.getAttribute('data-fav')) return;
        var id = row.getAttribute('data-id');
        if (selected[id]) delete selected[id]; else selected[id] = true;
        renderList();
      };
    });
    box.querySelectorAll('[data-fav]').forEach(function (b) {
      b.onclick = function (e) {
        e.stopPropagation();
        var id = b.getAttribute('data-fav');
        if (favorites[id]) delete favorites[id]; else favorites[id] = true;
        try { localStorage.setItem('kestrel.wx.fav', JSON.stringify(favorites)); } catch (err) {}
        renderList();
      };
    });
  }

  function startSession() {
    var ids = Object.keys(selected);
    if (!ids.length) {
      // default starter set
      ids = ['pushup', 'squat', 'plank', 'curl'];
    }
    var queue = ids.map(function (id) {
      return EXERCISES.find(function (e) { return e.id === id; }) || { name: id };
    });
    session = {
      queue: queue,
      idx: 0,
      set: 1,
      sets: 3,
      reps: 10,
      weight: 0,
      started: Date.now(),
      paused: false,
      kcal: 0
    };
    var scr = $('sess-screen');
    if (scr) {
      scr.hidden = false;
      scr.style.display = 'flex';
      scr.classList.add('on');
    }
    tickSession();
    if (sessTimer) clearInterval(sessTimer);
    sessTimer = setInterval(tickSession, 1000);
    // Dynamic Island / ongoing notification via bridge
    try {
      var A = window.KestrelAndroidBridge;
      if (A && A.startWorkoutSession) A.startWorkoutSession(JSON.stringify({
        name: queue[0].name,
        sets: 3
      }));
    } catch (e) {}
  }

  function tickSession() {
    if (!session || session.paused) return;
    var elapsed = Math.floor((Date.now() - session.started) / 1000);
    var h = String(Math.floor(elapsed / 3600)).padStart(2, '0');
    var m = String(Math.floor((elapsed % 3600) / 60)).padStart(2, '0');
    var s = String(elapsed % 60).padStart(2, '0');
    var t = $('sess-timer');
    if (t) t.textContent = h + ':' + m + ':' + s;
    var ex = session.queue[session.idx];
    if ($('sess-ex')) $('sess-ex').textContent = ex ? ex.name : 'Done';
    if ($('sess-meta')) $('sess-meta').textContent = 'Set ' + session.set + ' of ' + session.sets + ' · ' + session.reps + ' reps';
    session.kcal = Math.floor(elapsed / 12);
    if ($('sess-kcal')) $('sess-kcal').textContent = String(session.kcal);
    if ($('sess-active')) $('sess-active').textContent = String(session.kcal);
  }

  function logSet() {
    if (!session) return;
    if (session.set < session.sets) {
      session.set++;
    } else {
      session.set = 1;
      session.idx++;
      if (session.idx >= session.queue.length) {
        endSession();
        return;
      }
    }
    tickSession();
  }

  function endSession() {
    if (sessTimer) clearInterval(sessTimer);
    sessTimer = null;
    var scr = $('sess-screen');
    if (scr) {
      scr.classList.remove('on');
      scr.style.display = 'none';
      scr.hidden = true;
    }
    openEffort();
    try {
      var A = window.KestrelAndroidBridge;
      if (A && A.endWorkoutSession) A.endWorkoutSession();
    } catch (e) {}
    session = null;
  }

  var effortColors = {
    1: '#1B5E4A', 2: '#1B6B4A', 3: '#1B7A4A',
    4: '#2E5A8C', 5: '#3D4A9C', 6: '#5B3A9C',
    7: '#6B2A8A', 8: '#7A1F7A', 9: '#8B1A5A', 10: '#9C143C'
  };
  var effortLabels = {
    1: 'Easy', 2: 'Easy', 3: 'Easy',
    4: 'Moderate', 5: 'Moderate', 6: 'Moderate',
    7: 'Hard', 8: 'Hard', 9: 'All Out', 10: 'All Out'
  };

  function openEffort() {
    var sh = $('effort-sheet');
    if (!sh) return;
    sh.hidden = false;
    sh.style.display = 'flex';
    sh.classList.add('on');
    var sl = $('effort-slider');
    if (sl) sl.value = 5;
    updateEffortUI(5);
  }

  function updateEffortUI(v) {
    v = Math.max(1, Math.min(10, parseInt(v, 10) || 5));
    if ($('effort-num')) $('effort-num').textContent = String(v);
    if ($('effort-label')) $('effort-label').textContent = effortLabels[v] || '';
    if ($('effort-panel')) $('effort-panel').style.background = effortColors[v] || '#5B2A8A';
    var dots = $('effort-dots');
    if (dots) {
      dots.innerHTML = '';
      for (var i = 1; i <= 10; i++) {
        var d = document.createElement('i');
        if (i <= v) d.className = 'on';
        dots.appendChild(d);
      }
    }
  }

  function wire() {
    renderFilters();
    renderList();
    var start = $('wx-start');
    if (start) start.onclick = startSession;
    var ep = $('wx-edit-plan');
    if (ep) ep.onclick = function () { if (window.openPlanOnboarding) window.openPlanOnboarding(); };
    var pause = $('sess-pause');
    if (pause) pause.onclick = function () {
      if (!session) return;
      session.paused = !session.paused;
      pause.textContent = session.paused ? 'Resume' : 'Pause';
    };
    var log = $('sess-log');
    if (log) log.onclick = logSet;
    var sl = $('effort-slider');
    if (sl) sl.oninput = function () { updateEffortUI(sl.value); };
    var ex = $('effort-x');
    if (ex) ex.onclick = function () {
      var e = $('effort-sheet');
      if (e) { e.classList.remove('on'); e.style.display = 'none'; e.hidden = true; }
    };
    var ok = $('effort-ok');
    if (ok) ok.onclick = function () {
      var e = $('effort-sheet');
      if (e) { e.classList.remove('on'); e.style.display = 'none'; e.hidden = true; }
    };

    // AI fab opens chat group via page or sheet
    var fab = $('ai-fab');
    if (fab) fab.onclick = function () {
      // Prefer chat page if exists
      var chatPage = document.querySelector('.tab-page[data-group="chat"]');
      if (chatPage && window.kestrelGoto) {
        // temporarily show chat without nav item - jump via display
        document.querySelectorAll('.tab-page').forEach(function (p) { p.classList.remove('active'); });
        chatPage.classList.add('active');
        if (window.kestrelOnChatOpen) window.kestrelOnChatOpen();
        return;
      }
      if (window.__openKestrelAi) window.__openKestrelAi();
    };

    // Insights numbers
    try {
      var plan = window.kestrelGetPlan && window.kestrelGetPlan();
      if ($('ins-goal')) $('ins-goal').textContent = plan ? (plan.daysPerWeek || 4) + ' days' : '4 days';
      if ($('ins-consist')) $('ins-consist').textContent = '1 week';
      if ($('ins-vol')) $('ins-vol').textContent = '—';
      if ($('ins-load')) $('ins-load').textContent = '—';
    } catch (e) {}
  }

  function applyUserPlan(plan) {
    if (!plan || !plan.items) return;
    var label = $('wx-plan-label');
    if (label) label.textContent = plan.items.length + ' exercises in your plan';
    selected = {};
    plan.items.forEach(function (it) { selected[it.id] = true; });
    // inject user exercises into list
    plan.items.forEach(function (it) {
      if (!EXERCISES.find(function (e) { return e.id === it.id; })) {
        EXERCISES.unshift({ id: it.id, name: it.name, muscle: 'Custom', equip: 'Custom', diff: 'Custom', home: true });
      }
    });
    renderFilters();
    renderList();
  }
  function startUserPlan(plan) {
    if (!plan || !plan.items) return;
    selected = {};
    plan.items.forEach(function (it) { selected[it.id] = true; });
    applyUserPlan(plan);
    startSession();
  }
  window.KestrelWorkout = { applyPlan: applyPlan, applyUserPlan: applyUserPlan, startSession: startSession, startUserPlan: startUserPlan };

  function forceHideOverlays() {
    ['sess-screen', 'effort-sheet', 'ob-sheet'].forEach(function (id) {
      var el = $(id);
      if (!el) return;
      el.classList.remove('on', 'show');
      el.style.display = 'none';
      el.hidden = true;
    });
  }
  // FORCE_HIDE_SESSION
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { forceHideOverlays(); wire(); });
  else { forceHideOverlays(); wire(); }
})();
