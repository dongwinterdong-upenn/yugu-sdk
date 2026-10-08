/* 优谷雅言 SDK 文档站：主题，导航抽屉，复制，页签，本页目录，搜索，错误码定位。 */
(function () {
  'use strict';
  var root = document.documentElement;
  var store = {
    get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) { /* storage blocked */ } }
  };
  function $(s, el) { return (el || document).querySelector(s); }
  function $$(s, el) { return Array.prototype.slice.call((el || document).querySelectorAll(s)); }

  // Theme: system, light, dark.
  var THEMES = ['system', 'light', 'dark'];
  var THEME_LABEL = { system: '主题 跟随系统', light: '主题 浅色', dark: '主题 深色' };
  function currentTheme() { return root.getAttribute('data-theme') || 'system'; }
  function applyTheme(t) {
    if (t === 'system') root.removeAttribute('data-theme'); else root.setAttribute('data-theme', t);
    store.set('yugu-docs-theme', t);
    $$('.theme-toggle').forEach(function (b) { b.setAttribute('aria-label', THEME_LABEL[t]); b.title = THEME_LABEL[t]; });
  }
  $$('.theme-toggle').forEach(function (b) {
    b.setAttribute('aria-label', THEME_LABEL[currentTheme()]);
    b.title = THEME_LABEL[currentTheme()];
    b.addEventListener('click', function () { applyTheme(THEMES[(THEMES.indexOf(currentTheme()) + 1) % 3]); });
  });

  // Navigation drawer on narrow screens.
  var sidebar = $('#sidebar'), scrim = $('.scrim'), opener = $('.nav-open');
  function setDrawer(open) {
    if (!sidebar) return;
    sidebar.classList.toggle('open', open);
    if (scrim) scrim.hidden = !open;
    if (opener) opener.setAttribute('aria-expanded', String(open));
    document.body.style.overflow = open ? 'hidden' : '';
    if (open) { var a = $('.nav-item.active > .nav-link', sidebar) || $('a', sidebar); if (a) a.focus(); } else if (opener) opener.focus();
  }
  if (opener) opener.addEventListener('click', function () { setDrawer(true); });
  $$('.nav-close').forEach(function (b) { b.addEventListener('click', function () { setDrawer(false); }); });
  if (scrim) scrim.addEventListener('click', function () { setDrawer(false); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && sidebar && sidebar.classList.contains('open')) setDrawer(false); });
  var activeNav = $('.sidebar .nav-item.active > .nav-link');
  var scroller = $('.sidebar-scroll');
  if (activeNav && scroller) {
    var top = activeNav.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
    if (top > scroller.clientHeight - 80) scroller.scrollTop = top - scroller.clientHeight / 3;
  }

  // Copy.
  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    return new Promise(function (resolve, reject) {
      var ta = document.createElement('textarea');
      ta.value = text; ta.setAttribute('readonly', ''); ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy') ? resolve() : reject(new Error('copy')); } catch (err) { reject(err); }
      document.body.removeChild(ta);
    });
  }
  function flash(btn, okText) {
    var label = $('span', btn);
    var old = label ? label.textContent : null;
    btn.classList.add('done');
    if (label) label.textContent = okText;
    setTimeout(function () { btn.classList.remove('done'); if (label) label.textContent = old; }, 1600);
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest('.code-copy');
    if (b) {
      var code = $('pre code', b.closest('.code'));
      copyText(code.innerText.replace(/\n$/, '')).then(function () { flash(b, '已复制'); });
      return;
    }
    var ep = e.target.closest('.ep-copy');
    if (ep) { copyText(ep.getAttribute('data-copy')).then(function () { flash(ep, ''); }); return; }
    var cp = e.target.closest('.copy-page');
    if (cp) {
      fetch(cp.getAttribute('data-md')).then(function (r) { return r.text(); }).then(copyText).then(function () { flash(cp, '已复制'); });
    }
  });

  // Tabs. Groups with the same data-sync switch together; the choice is remembered per sync key.
  function selectTab(group, key, focus) {
    var tabs = $$(':scope > .tab-list > .tab', group);
    var hit = tabs.filter(function (t) { return t.getAttribute('data-key') === key; })[0];
    if (!hit) return false;
    tabs.forEach(function (t) {
      var on = t === hit;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
      var panel = document.getElementById(t.getAttribute('aria-controls'));
      if (panel) panel.hidden = !on;
    });
    if (focus) hit.focus();
    return true;
  }
  function syncAll(sync, key, origin) {
    // Keep the tab bar the reader clicked at the same place on screen while other groups change height.
    var before = origin ? origin.getBoundingClientRect().top : 0;
    $$('.tabs[data-sync="' + sync + '"]').forEach(function (g) { selectTab(g, key, false); });
    if (origin) {
      var delta = origin.getBoundingClientRect().top - before;
      if (Math.abs(delta) > 0.5) window.scrollBy(0, delta);
    }
    store.set('yugu-docs-tab:' + sync, key);
  }
  $$('.tabs').forEach(function (g) {
    var sync = g.getAttribute('data-sync');
    var saved = sync && store.get('yugu-docs-tab:' + sync);
    if (saved) selectTab(g, saved, false);
    var list = $(':scope > .tab-list', g);
    list.addEventListener('click', function (e) {
      var t = e.target.closest('.tab');
      if (!t) return;
      if (sync) syncAll(sync, t.getAttribute('data-key'), list); else selectTab(g, t.getAttribute('data-key'), false);
    });
    list.addEventListener('keydown', function (e) {
      var tabs = $$('.tab', list);
      var i = tabs.indexOf(document.activeElement);
      if (i < 0) return;
      var j = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : null;
      if (j === null) return;
      e.preventDefault();
      j = (j + tabs.length) % tabs.length;
      var key = tabs[j].getAttribute('data-key');
      if (sync) syncAll(sync, key, list); else selectTab(g, key, false);
      tabs[j].focus();
    });
  });

  // Outline: highlight the section being read.
  var tocLinks = $$('.toc-list a');
  if (tocLinks.length && 'IntersectionObserver' in window) {
    var byId = {};
    tocLinks.forEach(function (a) { byId[decodeURIComponent(a.getAttribute('href').slice(1))] = a; });
    var heads = $$('.prose h2[id], .prose h3[id]').filter(function (h) { return byId[h.id]; });
    var visible = {};
    var mark = function () {
      var current = null;
      for (var k = 0; k < heads.length; k++) {
        if (heads[k].getBoundingClientRect().top < 140) current = heads[k];
      }
      if (!current) current = heads[0];
      tocLinks.forEach(function (a) { a.classList.toggle('active', current && a === byId[current.id]); });
    };
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) { visible[en.target.id] = en.isIntersecting; });
      mark();
    }, { rootMargin: '-60px 0px -60% 0px' });
    heads.forEach(function (h) { io.observe(h); });
    window.addEventListener('scroll', function () { window.requestAnimationFrame(mark); }, { passive: true });
    mark();
  }

  // Search.
  var dialog = $('.search-dialog');
  var input = dialog && $('.search-input', dialog);
  var results = dialog && $('.search-results', dialog);
  var index = null, loading = null, selected = -1, lastFocus = null;
  function loadIndex() {
    if (index) return Promise.resolve(index);
    if (!loading) {
      loading = fetch(document.body.getAttribute('data-search')).then(function (r) { if (!r.ok) throw new Error('index ' + r.status); return r.json(); }).then(function (d) {
        index = d.map(function (e) { e._p = e.p.toLowerCase(); e._h = e.h.toLowerCase(); e._t = e.t.toLowerCase(); return e; });
        return index;
      });
      loading.catch(function () { loading = null; });
    }
    return loading;
  }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function highlight(text, terms) {
    var out = esc(text);
    terms.forEach(function (t) {
      if (!t) return;
      var re = new RegExp(esc(t).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
      out = out.replace(re, function (m) { return '<mark>' + m + '</mark>'; });
    });
    return out;
  }
  function snippet(text, term) {
    var i = text.toLowerCase().indexOf(term);
    if (i < 0) return text.slice(0, 90);
    var s = Math.max(0, i - 24);
    return (s > 0 ? '…' : '') + text.slice(s, s + 110);
  }
  function search(q) {
    var terms = q.toLowerCase().split(/\s+/).filter(Boolean);
    if (!terms.length) return [];
    var scored = [];
    index.forEach(function (e) {
      var score = 0;
      for (var k = 0; k < terms.length; k++) {
        var t = terms[k], s = 0;
        if (e._h === t || e._p === t) s = 60;
        else if (e._h.indexOf(t) >= 0) s = 30;
        else if (e._p.indexOf(t) >= 0) s = 20;
        else if (e._t.indexOf(t) >= 0) s = 6;
        if (!s) return;
        score += s;
      }
      if (!e.h) score += 2;
      scored.push({ e: e, s: score });
    });
    scored.sort(function (a, b) { return b.s - a.s; });
    return scored.slice(0, 24).map(function (x) { return x.e; });
  }
  function renderResults(q) {
    var terms = q.toLowerCase().split(/\s+/).filter(Boolean);
    var list = search(q);
    selected = list.length ? 0 : -1;
    if (!q.trim()) { results.innerHTML = ''; return; }
    if (!list.length) { results.innerHTML = '<li class="sr-empty">无匹配结果</li>'; return; }
    results.innerHTML = list.map(function (e, i) {
      var href = e.u + (e.a ? '#' + e.a : '');
      var path = (e.g ? e.g + ' › ' : '') + e.p;
      return '<li role="option" id="sr-' + i + '" aria-selected="' + (i === 0) + '"><a href="' + esc(href) + '">' +
        '<span class="sr-path">' + esc(path) + '</span>' +
        '<span class="sr-title">' + highlight(e.h || e.p, terms) + '</span>' +
        '<span class="sr-text">' + highlight(snippet(e.t, terms[0]), terms) + '</span></a></li>';
    }).join('');
  }
  function moveSel(d) {
    var items = $$('li[role="option"]', results);
    if (!items.length) return;
    selected = (selected + d + items.length) % items.length;
    items.forEach(function (li, i) { li.setAttribute('aria-selected', String(i === selected)); });
    items[selected].scrollIntoView({ block: 'nearest' });
    input.setAttribute('aria-activedescendant', items[selected].id);
  }
  function openSearch() {
    if (!dialog) return;
    lastFocus = document.activeElement;
    dialog.hidden = false;
    document.body.style.overflow = 'hidden';
    input.value = '';
    results.innerHTML = '';
    input.focus();
    loadIndex();
  }
  function closeSearch() {
    if (!dialog || dialog.hidden) return;
    dialog.hidden = true;
    document.body.style.overflow = '';
    if (lastFocus) lastFocus.focus();
  }
  if (dialog) {
    $$('.search-open').forEach(function (b) {
      b.addEventListener('click', openSearch);
      // Fetch the index as soon as the reader heads for the search box.
      b.addEventListener('pointerenter', loadIndex, { once: true });
      b.addEventListener('focus', loadIndex, { once: true });
    });
    $$('[data-close]', dialog).forEach(function (b) { b.addEventListener('click', closeSearch); });
    input.addEventListener('input', function () {
      if (!index && input.value.trim()) results.innerHTML = '<li class="sr-empty">加载中</li>';
      loadIndex().then(function () { renderResults(input.value); }, function () { results.innerHTML = '<li class="sr-empty">索引加载失败</li>'; });
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown') { e.preventDefault(); moveSel(1); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); moveSel(-1); }
      else if (e.key === 'Enter') {
        var a = $('li[aria-selected="true"] a', results);
        if (a) { e.preventDefault(); location.href = a.getAttribute('href'); closeSearch(); }
      } else if (e.key === 'Escape') { e.preventDefault(); closeSearch(); }
    });
    results.addEventListener('click', function (e) { if (e.target.closest('a')) closeSearch(); });
    document.addEventListener('keydown', function (e) {
      var typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName) || document.activeElement.isContentEditable;
      if ((e.key === 'k' || e.key === 'K') && (e.metaKey || e.ctrlKey)) { e.preventDefault(); dialog.hidden ? openSearch() : closeSearch(); }
      else if (e.key === '/' && !typing && dialog.hidden) { e.preventDefault(); openSearch(); }
      else if (e.key === 'Escape' && !dialog.hidden) closeSearch();
    });
  }

  // Error code lookup: finds the table row whose first cell is the code and scrolls to it.
  var lookup = $('.lookup-input');
  if (lookup) {
    var status = $('.lookup-status');
    var lastRow = null;
    var rows = $$('.prose table tbody tr').filter(function (tr) { return tr.cells.length > 1; });
    var find = function () {
      var q = lookup.value.trim();
      if (lastRow) lastRow.classList.remove('hit');
      if (!q) { status.textContent = ''; return; }
      var hit = rows.filter(function (tr) { return tr.cells[0].textContent.trim() === q; })[0] ||
        rows.filter(function (tr) { return tr.cells[1] && tr.cells[1].textContent.trim().toLowerCase() === q.toLowerCase(); })[0];
      if (!hit) { status.textContent = '无匹配'; return; }
      hit.classList.add('hit');
      lastRow = hit;
      status.textContent = '';
      hit.scrollIntoView({ block: 'center', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    };
    lookup.addEventListener('keydown', function (e) { if (e.key === 'Enter') find(); });
    lookup.addEventListener('input', function () { if (/^\d{4,5}$/.test(lookup.value.trim())) find(); else if (lastRow) { lastRow.classList.remove('hit'); status.textContent = ''; } });
  }
})();
