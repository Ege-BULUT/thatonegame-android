// The app adds this to every site page (MainActivity.serve). It marks the game list's cards by their download state:
// a game never downloaded gets a small download chip, a game with an update gets a dark grey cover with an update
// button. A tap on either downloads in place without opening the game; when it is done the mark goes away.
// The site itself is not changed: the cards are found by their thumbnail, /<games|io>/<slug>/thumb.png.
(function () {
  var app = window.TOGApp;
  if (!app || !app.games) return;
  var ICON = {
    get: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v11m0 0l-4.5-4.5M12 14l4.5-4.5M4 17v3h16v-3" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    update: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 12a8 8 0 0 1-14.3 4.9M4 12a8 8 0 0 1 14.3-4.9M18.5 3v4.3h-4.3M5.5 21v-4.3h4.3" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>'
  };
  var css = document.createElement('style');
  css.textContent = [
    '.tog-mark{position:absolute;z-index:2;color:#fff;font:bold 11px Verdana,sans-serif;cursor:pointer;-webkit-tap-highlight-color:transparent}',
    '.tog-mark svg{display:block;width:30px;height:30px;margin:0 auto 4px;filter:drop-shadow(0 1px 0 rgba(0,0,0,.6))}',
    '.tog-mark b{display:block;font:12px "Arial Black","Arial Bold",sans-serif;text-transform:uppercase;letter-spacing:.3px}',
    '.tog-cover{inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;background:rgba(38,38,38,.82);text-shadow:0 1px 0 rgba(0,0,0,.6)}',
    '.tog-cover small{opacity:.85}',
    '.tog-chip{right:6px;bottom:6px;display:flex;align-items:center;gap:4px;padding:3px 8px 3px 5px;border-radius:10px;background:rgba(38,38,38,.88);border:1px solid rgba(255,255,255,.35);box-shadow:0 1px 2px rgba(0,0,0,.4)}',
    '.tog-chip svg{width:14px;height:14px;margin:0}',
    '.tog-bar{width:70%;height:8px;margin-top:6px;background:#111;border:1px solid #000;border-radius:4px;overflow:hidden}',
    '.tog-bar i{display:block;height:100%;width:0;background:linear-gradient(#ffc23d,#ff8a00);transition:width .2s}',
    '.tog-busy svg{animation:tog-spin 1s linear infinite}',
    '@keyframes tog-spin{to{transform:rotate(360deg)}}'
  ].join('');
  document.head.appendChild(css);

  var mb = function (n) { return n < 5e4 ? '<0.1 MB' : (n / 1e6).toFixed(n < 1e7 ? 1 : 0) + ' MB'; };
  var games = {}, errors = {}, timer = 0;

  function paint() {
    try { games = JSON.parse(app.games()); } catch (e) { return; }
    var busy = false;
    document.querySelectorAll('.card .thumb img').forEach(function (img) {
      var m = (img.getAttribute('src') || '').match(/^\/((games|io)\/[^\/]+\/)thumb\.png$/);
      var g = m && games[m[1]], thumb = img.closest('.thumb'), old = thumb.querySelector('.tog-mark');
      var s = !g ? 'ready' : errors[m[1]] ? 'error' : g.s;
      if (s === 'busy') busy = true;
      var html = s === 'get' ? ICON.get + mb(g.bytes)
        : s === 'update' ? ICON.update + '<b>Update</b><small>' + mb(g.bytes) + '</small>'
        : s === 'busy' ? ICON[g.installed ? 'update' : 'get'] + '<b>' + (g.total ? Math.round(g.done * 100 / g.total) + '%' : 'Waiting') + '</b>'
          + '<div class="tog-bar"><i style="width:' + (g.total ? g.done * 100 / g.total : 0) + '%"></i></div>'
        : s === 'error' ? ICON[g.installed ? 'update' : 'get'] + '<b>No internet</b><small>Try again later</small>' : '';
      var cls = 'tog-mark ' + (s === 'get' ? 'tog-chip' : 'tog-cover') + (s === 'busy' ? ' tog-busy' : '');
      if (!html) { if (old) old.remove(); return; }
      if (old && old.dataset.html === html) return;
      var el = old || document.createElement('div');
      el.className = cls;
      el.innerHTML = html;
      el.dataset.html = html;
      el.dataset.game = m[1];
      el.setAttribute('role', 'button');
      el.setAttribute('aria-label', s === 'get' ? 'Download this game' : 'Update this game');
      if (!old) thumb.appendChild(el);
    });
    clearTimeout(timer);
    if (busy) timer = setTimeout(paint, 300);
  }

  // A tap on a mark downloads in place; the card's link (which opens the game) does not follow.
  document.addEventListener('click', function (e) {
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
