// The app adds this to every site page (MainActivity.serve). It marks the game list's cards by their download state:
// a game never downloaded gets a small download chip, a game with an update gets a dark grey cover with an update
// button. A tap on either downloads in place without opening the game, with a progress ring; when it is done the mark
// goes away. It also shows the banner for a new version of the app itself (AppUpdate.java).
// The site itself is not changed: the cards are found by their thumbnail, /<games|io>/<slug>/thumb.png.
(function () {
  var app = window.TOGApp;
  if (!app || !app.games) return;
  var ICON = {
    get: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v11m0 0l-4.5-4.5M12 14l4.5-4.5M4 17v3h16v-3" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    update: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 12a8 8 0 0 1-14.3 4.9M4 12a8 8 0 0 1 14.3-4.9M18.5 3v4.3h-4.3M5.5 21v-4.3h4.3" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>'
  };
  // A determinate ring: the orange arc's dash offset is 100 - percent (r = 15.9155 makes the circumference 100).
  // Before the first byte it is a quarter arc and the whole square SVG turns about its own centre.
  var RING = '<div class="tog-dial"><svg class="tog-ring" viewBox="0 0 36 36" aria-hidden="true">'
    + '<circle class="tog-t" cx="18" cy="18" r="15.9155"/><circle class="tog-v" cx="18" cy="18" r="15.9155" transform="rotate(-90 18 18)"/></svg><span></span></div>';
  var css = document.createElement('style');
  css.textContent = [
    '.tog-mark{position:absolute;z-index:2;color:#fff;font:bold 11px Verdana,sans-serif;cursor:pointer;-webkit-tap-highlight-color:transparent}',
    '.tog-mark svg{display:block;width:30px;height:30px;margin:0 auto 4px;filter:drop-shadow(0 1px 0 rgba(0,0,0,.6))}',
    '.tog-mark b{display:block;font:12px "Arial Black","Arial Bold",sans-serif;text-transform:uppercase;letter-spacing:.3px}',
    '.tog-cover{inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;background:rgba(38,38,38,.82);text-shadow:0 1px 0 rgba(0,0,0,.6)}',
    '.tog-cover small{opacity:.85}',
    '.tog-chip{right:6px;bottom:6px;display:flex;align-items:center;gap:4px;padding:3px 8px 3px 5px;border-radius:10px;background:rgba(38,38,38,.88);border:1px solid rgba(255,255,255,.35);box-shadow:0 1px 2px rgba(0,0,0,.4)}',
    '.tog-chip svg{width:14px;height:14px;margin:0}',
    '.tog-dial{position:relative;width:46px;height:46px;margin:0 auto 4px}',
    '.tog-mark .tog-ring,.tog-ring{display:block;width:100%;height:100%;margin:0;filter:none;overflow:visible}',
    '.tog-ring circle{fill:none;stroke-width:3.4}',
    '.tog-ring .tog-t{stroke:rgba(255,255,255,.25)}',
    '.tog-ring .tog-v{stroke:#ff8a00;stroke-dasharray:100;stroke-dashoffset:100;transition:stroke-dashoffset .3s linear}',
    '.tog-ring.tog-wait{animation:tog-turn 1s linear infinite;transform-origin:center;transform-box:fill-box}',
    '.tog-ring.tog-wait .tog-v{stroke-dasharray:25 75;stroke-dashoffset:0;transition:none}',
    '@keyframes tog-turn{to{transform:rotate(360deg)}}',
    '.tog-dial span{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font:bold 11px Verdana,sans-serif;color:#fff}',
    '.tog-banner{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:0 0 12px;padding:8px 10px;color:#fff;background:#262626;border:1px solid #000;border-left:4px solid #ff8a00;border-radius:4px;font:12px Verdana,sans-serif}',
    '.tog-banner b{flex:1 1 160px}',
    '.tog-banner button{font:12px "Arial Black","Arial Bold",sans-serif;text-transform:uppercase;cursor:pointer}',
    '.tog-banner .later{background:none;border:0;color:#ffc23d}',
    '.tog-banner .tog-dial{width:30px;height:30px;margin:0}'
  ].join('');
  document.head.appendChild(css);

  var mb = function (n) { var m = n / 1048576; return m < 1 ? Math.min(1023, Math.max(1, Math.round(n / 1024))) + ' KB' : m.toFixed(m < 10 ? 1 : 0) + ' MB'; }; // under 1 MB in whole KB, never 0
  var games = {}, errors = {}, timer = 0;

  // Sets a ring (inside el) to done / total; total 0 means not started yet.
  function dial(el, done, total) {
    var svg = el.querySelector('.tog-ring'), p = total ? Math.min(100, Math.floor(done * 100 / total)) : -1;
    svg.classList.toggle('tog-wait', p < 0);
    svg.lastChild.style.strokeDashoffset = p < 0 ? '' : 100 - p;
    el.querySelector('.tog-dial span').textContent = p < 0 ? '' : p + '%';
  }

  function paint() {
    try { games = JSON.parse(app.games()); } catch (e) { return; }
    var busy = false;
    document.querySelectorAll('.card .thumb img').forEach(function (img) {
      var m = (img.getAttribute('src') || '').match(/^\/((games|io)\/[^\/]+\/)thumb\.png$/);
      var g = m && games[m[1]], thumb = img.closest('.thumb'), old = thumb.querySelector('.tog-mark');
      var s = !g ? 'ready' : errors[m[1]] ? 'error' : g.s;
      if (s === 'ready') { if (old) old.remove(); return; }
      var el = old || document.createElement('div');
      if (s === 'busy') {
        busy = true;
        // Built once and then only updated, so the ring moves smoothly between polls.
        if (el.dataset.mode !== 'busy') el.innerHTML = RING + '<small>' + (g.installed ? 'Updating' : 'Downloading') + '</small>';
        dial(el, g.done, g.total);
      } else {
        var html = s === 'get' ? ICON.get + mb(g.bytes)
          : s === 'update' ? ICON.update + '<b>Update</b><small>' + mb(g.bytes) + '</small>'
          : ICON[g.installed ? 'update' : 'get'] + '<b>No internet</b><small>Try again later</small>';
        if (el.dataset.mode !== s || el.dataset.html !== html) el.innerHTML = html;
        el.dataset.html = html;
      }
      el.dataset.mode = s;
      el.className = 'tog-mark ' + (s === 'get' ? 'tog-chip' : 'tog-cover');
      el.dataset.game = m[1];
      el.setAttribute('role', 'button');
      el.setAttribute('aria-label', s === 'get' ? 'Download this game' : 'Update this game');
      if (!old) thumb.appendChild(el);
    });
    busy = banner() || busy;
    clearTimeout(timer);
    if (busy) timer = setTimeout(paint, 300);
  }

  // A new version of the app: a banner at the top of the game list. Returns true while it downloads.
  function banner() {
    var u = null, main = document.querySelector('.card') && document.getElementById('main');
    try { u = app.appUpdate && JSON.parse(app.appUpdate() || 'null'); } catch (e) { /* no offer */ }
    var el = document.querySelector('.tog-banner');
    if (!u || !main) { if (el) el.remove(); return false; }
    var html = u.state === 'busy' ? RING + '<b>Downloading ThatOneGame ' + u.versionName + '</b>'
      : '<b>' + (u.state === 'failed' ? 'The download failed. Try again later.' : 'Update available: ' + u.versionName + ' (' + mb(u.size) + ')') + '</b>'
        + (u.state === 'failed' ? '' : '<button class="btn" data-act="start">Update</button>') + '<button class="later" data-act="later">Later</button>';
    if (!el) { el = document.createElement('div'); el.className = 'tog-banner'; }
    if (el.dataset.html !== html) { el.innerHTML = html; el.dataset.html = html; }
    if (main.firstChild !== el) main.insertBefore(el, main.firstChild);
    if (u.state === 'busy') dial(el, u.done, u.total);
    return u.state === 'busy';
  }
  window.togPaint = function () { paint(); }; // the app calls it when its update check finds a new version

  document.addEventListener('click', function (e) {
    var act = e.target.closest && e.target.closest('.tog-banner [data-act]');
    if (act) {
      if (act.dataset.act === 'start') app.appUpdateStart(); else app.appUpdateLater();
      return paint();
    }
    // A tap on a mark downloads in place; the card's link (which opens the game) does not follow.
    var el = e.target.closest && e.target.closest('.tog-mark');
    if (!el) return;
    e.preventDefault();
    e.stopPropagation();
    var k = el.dataset.game, g = games[k];
    if (!g || g.s === 'busy' || errors[k]) return;
    app.install(k);
    var wait = function () {
      paint();
      var now = games[k];
      if (now && now.s === 'busy') return setTimeout(wait, 300);
      if (now && now.error !== undefined) {
        errors[k] = 1;
        paint();
        setTimeout(function () { delete errors[k]; paint(); }, 2500);
      }
    };
    wait();
  }, true);

  var queued = false;
  new MutationObserver(function () {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () { queued = false; paint(); });
  }).observe(document.documentElement, { childList: true, subtree: true });
  addEventListener('pageshow', paint);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) paint(); });
  paint();
})();
