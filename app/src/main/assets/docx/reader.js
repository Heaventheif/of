/* OpenFileManager DOCX reader page. Runs fully offline inside a locked-down WebView:
 *  - /doc.docx is served by the app (the one document being read), converted here by the bundled Mammoth;
 *  - the ZIP container is audited BEFORE conversion (entry count, inflated sizes, ratios, media total,
 *    unsafe names) so a small-on-disk DOCX cannot inflate into unbounded memory (F-12);
 *  - the HTML Mammoth produces is sanitized before it touches the DOM;
 *  - talks to the app only through OfmBridge (string in / string out), and the app drives it through window.OFM.
 */
(function () {
  'use strict';

  var bridge = window.OfmBridge || {};
  function send(fn, obj) {
    try { if (bridge[fn]) bridge[fn](JSON.stringify(obj)); } catch (e) { /* bridge gone: nothing to do */ }
  }

  var doc = document.getElementById('doc');
  var statusEl = document.getElementById('status');
  var blocks = [];      // block-level elements in document order: stable location index
  var headings = [];    // {el, id, level, text}
  var marks = [];       // <mark> elements of the current search
  var cur = -1;
  var MAX_MATCHES = 3000;
  var loaded = false;

  // ---- startup parameters (theme, font size) so the first paint is already right ----
  (function initFromQuery() {
    var q = new URLSearchParams(location.search);
    setTheme(q.get('theme') || 'light');
    var fs = parseFloat(q.get('fs'));
    if (fs > 0) document.documentElement.style.setProperty('--fs', fs + 'px');
  })();

  function setTheme(name) {
    document.documentElement.setAttribute('data-theme', name === 'dark' ? 'dark' : 'light');
  }

  // ---- sanitizing ----
  var DROP = 'script,style,link,meta,base,iframe,frame,frameset,object,embed,form,input,button,textarea,select,svg,math,audio,video,source,template,noscript';
  function sanitize(html) {
    var parsed = new DOMParser().parseFromString('<!doctype html><body>' + html, 'text/html');
    var body = parsed.body;
    var bad = body.querySelectorAll(DROP);
    for (var i = 0; i < bad.length; i++) if (bad[i].parentNode) bad[i].parentNode.removeChild(bad[i]);
    var all = body.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) {
      var el = all[j];
      var attrs = Array.prototype.slice.call(el.attributes);
      for (var k = 0; k < attrs.length; k++) {
        var n = attrs[k].name.toLowerCase();
        var v = attrs[k].value;
        if (n.indexOf('on') === 0 || n === 'style' || n === 'srcset' || n === 'formaction') { el.removeAttribute(attrs[k].name); continue; }
        if (n === 'href') {
          if (!/^(#|https?:|mailto:)/i.test(v.trim())) el.removeAttribute(attrs[k].name);
        } else if (n === 'src') {
          if (!/^data:image\/(png|jpe?g|gif|bmp|webp|tiff?|x-emf|x-wmf|emf|wmf);base64,/i.test(v.trim())) el.removeAttribute(attrs[k].name);
        }
      }
      if (el.tagName === 'IMG' && !el.getAttribute('src')) el.parentNode && el.parentNode.removeChild(el);
    }
    return body;
  }

  // ---- load ----
  function fail(code, message) {
    statusEl.textContent = '';
    send('onLoaded', { ok: false, error: code, message: String(message || '') });
  }

  function render(html, messages) {
    var body = sanitize(html);
    while (doc.firstChild) doc.removeChild(doc.firstChild);
    while (body.firstChild) doc.appendChild(body.firstChild);

    var tables = doc.querySelectorAll('table');
    for (var t = 0; t < tables.length; t++) {
      var wrap = document.createElement('div');
      wrap.className = 'table-wrap';
      tables[t].parentNode.insertBefore(wrap, tables[t]);
      wrap.appendChild(tables[t]);
    }

    var text = (doc.textContent || '').replace(/\s+/g, '');
    if (!text.length && !doc.querySelector('img')) { fail('empty', ''); return; }

    blocks = Array.prototype.slice.call(doc.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,tr,blockquote,pre'));
    var hs = doc.querySelectorAll('h1,h2,h3,h4,h5,h6');
    headings = [];
    for (var i = 0; i < hs.length; i++) {
      var label = (hs[i].textContent || '').replace(/\s+/g, ' ').trim();
      if (!label) continue;
      var id = 'ofm-h-' + headings.length;
      hs[i].id = id;
      headings.push({ el: hs[i], id: id, level: parseInt(hs[i].tagName.charAt(1), 10), text: label.slice(0, 200) });
    }
    statusEl.textContent = '';
    loaded = true;
    send('onLoaded', {
      ok: true,
      headings: headings.map(function (h) { return { id: h.id, level: h.level, text: h.text }; }),
      blocks: blocks.length,
      chars: text.length,
      warnings: messages ? messages.length : 0
    });
  }

  // ---- ZIP budget audit (F-12) ----
  // ميزانية فك الضغط قبل تمرير أي بايت إلى Mammoth: ملف DOCX مضغوط صغير على
  // القرص قد يحمل محتوى/وسائط منفوخة ضخمة، والحدّ المطبق سابقاً (80MB) كان
  // يقيس الملف المضغوط فقط. الفحص مركزي هنا: عدد المداخل، مجموع الأحجام
  // بعد الفك، نسبة الضغط لكل مدخل كبير، حجم الوسائط، وأسماء غير آمنة.
  var DOCX_BUDGET = {
    MAX_ENTRIES: 4096,
    MAX_TOTAL_UNCOMPRESSED: 256 * 1024 * 1024,
    MAX_ENTRY_UNCOMPRESSED: 64 * 1024 * 1024,
    MAX_MEDIA_TOTAL: 192 * 1024 * 1024,
    MAX_RATIO: 200,
    RATIO_MIN_COMPRESSED: 4096
  };

  function zipAudit(buf) {
    var dv = new DataView(buf);
    var len = buf.byteLength;
    if (len < 22) return { ok: false, reason: 'truncated' };
    // End of Central Directory: signature 0x06054b50، تُمسح من نهاية الملف.
    var scanStart = Math.max(0, len - 22 - 65535);
    var eocd = -1;
    for (var i = len - 22; i >= scanStart; i--) {
      if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) return { ok: false, reason: 'truncated' };
    var totalEntries = dv.getUint16(eocd + 10, true);
    var cdSize = dv.getUint32(eocd + 12, true);
    var cdOffset = dv.getUint32(eocd + 16, true);
    if (totalEntries === 0xffff || cdOffset === 0xffffffff || cdSize === 0xffffffff) {
      return { ok: false, reason: 'zip64' }; // ZIP64 خارج الميزانية: رفض حذر لا تحليل كامل
    }
    if (cdOffset + cdSize > len) return { ok: false, reason: 'truncated' };
    if (totalEntries > DOCX_BUDGET.MAX_ENTRIES) return { ok: false, reason: 'entries' };
    var p = cdOffset;
    var totalUn = 0;
    var mediaUn = 0;
    for (var e = 0; e < totalEntries; e++) {
      if (p + 46 > len || dv.getUint32(p, true) !== 0x02014b50) return { ok: false, reason: 'truncated' };
      var cSize = dv.getUint32(p + 20, true);
      var uSize = dv.getUint32(p + 24, true);
      var nameLen = dv.getUint16(p + 28, true);
      var extraLen = dv.getUint16(p + 30, true);
      var commentLen = dv.getUint16(p + 32, true);
      var name = '';
      for (var k = 0; k < nameLen; k++) name += String.fromCharCode(dv.getUint8(p + 46 + k));
      if (name.charAt(0) === '/' || name.indexOf('\\') >= 0 || name.indexOf('..') >= 0) {
        return { ok: false, reason: 'unsafe' };
      }
      if (uSize > DOCX_BUDGET.MAX_ENTRY_UNCOMPRESSED) return { ok: false, reason: 'entry' };
      totalUn += uSize;
      if (totalUn > DOCX_BUDGET.MAX_TOTAL_UNCOMPRESSED) return { ok: false, reason: 'total' };
      if (/^word\/media\//i.test(name)) {
        mediaUn += uSize;
        if (mediaUn > DOCX_BUDGET.MAX_MEDIA_TOTAL) return { ok: false, reason: 'media' };
      }
      if (cSize >= DOCX_BUDGET.RATIO_MIN_COMPRESSED && uSize > cSize * DOCX_BUDGET.MAX_RATIO) {
        return { ok: false, reason: 'ratio' };
      }
      p += 46 + nameLen + extraLen + commentLen;
    }
    return { ok: true };
  }

  function start() {
    if (typeof mammoth === 'undefined') { fail('convert', 'converter missing'); return; }
    fetch('doc.docx', { cache: 'no-store' })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); })
      .then(function (buf) {
        // (F-12) بوابة الميزانية قبل أي تحويل.
        var audit = zipAudit(buf);
        if (!audit.ok) {
          var err = new Error('budget: ' + audit.reason);
          err.budget = true;
          throw err;
        }
        return mammoth.convertToHtml({ arrayBuffer: buf }, {
          convertImage: mammoth.images.dataUri,
          styleMap: [
            "p[style-name='Title'] => h1.doc-title:fresh",
            "p[style-name='Subtitle'] => p.doc-subtitle:fresh"
          ]
        });
      })
      .then(function (res) { render(res.value, res.messages); })
      .catch(function (e) { fail(e && e.budget ? 'budget' : 'convert', e && e.message); });
  }

  // ---- location (stable across font size / theme / reflow) ----
  function topOf(el) { return el.getBoundingClientRect().top + window.scrollY; }

  function location_() {
    if (!blocks.length) return { b: 0, f: 0 };
    var y = window.scrollY, lo = 0, hi = blocks.length - 1, found = 0;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (topOf(blocks[mid]) <= y + 1) { found = mid; lo = mid + 1; } else hi = mid - 1;
    }
    var r = blocks[found].getBoundingClientRect();
    var h = Math.max(r.height, 1);
    var f = Math.min(1, Math.max(0, (y - (r.top + window.scrollY)) / h));
    return { b: found, f: f };
  }

  function headingAbove(y) {
    var lo = 0, hi = headings.length - 1, found = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (topOf(headings[mid].el) <= y + 2) { found = mid; lo = mid + 1; } else hi = mid - 1;
    }
    return found < 0 ? null : headings[found];
  }

  function describe() {
    var loc = location_();
    var max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
    var h = headingAbove(window.scrollY);
    var snippet = blocks[loc.b] ? (blocks[loc.b].textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80) : '';
    return {
      b: loc.b, f: loc.f,
      p: max > 0 ? Math.min(1, window.scrollY / max) : 1,
      h: h ? h.id : '', ht: h ? h.text : '', s: snippet
    };
  }

  function restore(loc) {
    if (!loc || !blocks.length) return false;
    var el = blocks[Math.min(Math.max(loc.b | 0, 0), blocks.length - 1)];
    var r = el.getBoundingClientRect();
    window.scrollTo(0, Math.max(0, r.top + window.scrollY + (loc.f || 0) * r.height));
    return true;
  }

  // Images decode after layout and shift everything below them: keep re-applying a restored position
  // until the reader moves themselves (or a couple of seconds pass).
  var pending = null, pendingTimer = 0;
  function holdRestore(loc) {
    pending = loc;
    clearTimeout(pendingTimer);
    pendingTimer = setTimeout(function () { pending = null; }, 2500);
  }
  function releaseRestore() { pending = null; }
  ['touchstart', 'wheel', 'keydown', 'mousedown'].forEach(function (ev) {
    window.addEventListener(ev, releaseRestore, { passive: true, capture: true });
  });
  document.addEventListener('load', function (e) {
    if (pending && e.target && e.target.tagName === 'IMG') restore(pending);
  }, true);

  var scrollTimer = 0;
  window.addEventListener('scroll', function () {
    if (!loaded || scrollTimer) return;
    scrollTimer = setTimeout(function () { scrollTimer = 0; send('onScroll', describe()); }, 150);
  }, { passive: true });

  // ---- search ----
  function clearMarks() {
    var parents = [];
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i], p = m.parentNode;
      if (!p) continue;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
      if (parents.indexOf(p) < 0) parents.push(p);
    }
    for (var j = 0; j < parents.length; j++) parents[j].normalize();
    marks = []; cur = -1;
  }

  function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

  function search(q) {
    clearMarks();
    q = (q || '').trim();
    if (!q) { send('onSearch', { count: 0, index: -1, q: '' }); return; }
    var re = new RegExp(escapeRe(q), 'gi');
    var walker = document.createTreeWalker(doc, NodeFilter.SHOW_TEXT, null);
    var nodes = [], n;
    while ((n = walker.nextNode())) { if (n.nodeValue && n.nodeValue.length >= q.length) nodes.push(n); }
    var total = 0, truncated = false;
    for (var i = 0; i < nodes.length && !truncated; i++) {
      var node = nodes[i], text = node.nodeValue, last = 0, m, frag = null;
      re.lastIndex = 0;
      while ((m = re.exec(text)) !== null) {
        if (m[0].length === 0) { re.lastIndex++; continue; }
        if (total >= MAX_MATCHES) { truncated = true; break; }
        if (!frag) frag = document.createDocumentFragment();
        if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
        var mk = document.createElement('mark');
        mk.className = 'm';
        mk.textContent = m[0];
        frag.appendChild(mk);
        marks.push(mk);
        total++;
        last = m.index + m[0].length;
      }
      if (frag) {
        if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
        node.parentNode.replaceChild(frag, node);
      }
    }
    if (!marks.length) { send('onSearch', { count: 0, index: -1, q: q }); return; }
    // First match at or after the top of the viewport.
    var idx = 0, y = window.scrollY;
    for (var k = 0; k < marks.length; k++) { if (topOf(marks[k]) >= y - 1) { idx = k; break; } }
    setCurrent(idx, true, q, truncated);
  }

  var lastQuery = '', lastTruncated = false;
  function setCurrent(i, scroll, q, truncated) {
    if (!marks.length) return;
    if (q !== undefined) lastQuery = q;
    if (truncated !== undefined) lastTruncated = truncated;
    if (cur >= 0 && marks[cur]) marks[cur].classList.remove('cur');
    cur = (i + marks.length) % marks.length;
    marks[cur].classList.add('cur');
    // سلوك smooth: انتقال سلس بين نتائج البحث بدل القفزة الفورية
    if (scroll) marks[cur].scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
    send('onSearch', { count: marks.length, index: cur, q: lastQuery, truncated: lastTruncated });
  }

  // ---- links ----
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    e.preventDefault();
    if (href.charAt(0) === '#') {
      var id = href.slice(1);
      try { id = decodeURIComponent(id); } catch (err) { /* keep raw */ }
      var target = document.getElementById(id);
      if (target) window.scrollTo(0, Math.max(0, topOf(target) - 8));
    } else if (href) {
      send('onLink', { url: a.href });
    }
  }, true);

  // ---- API for the app ----
  window.OFM = {
    setTheme: setTheme,
    setFontSize: function (px) {
      var loc = loaded ? location_() : null;
      document.documentElement.style.setProperty('--fs', px + 'px');
      if (loc) requestAnimationFrame(function () { restore(loc); send('onScroll', describe()); });
    },
    restore: function (b, f) { var loc = { b: b, f: f }; var ok = restore(loc); holdRestore(loc); if (loaded) send('onScroll', describe()); return ok; },
    scrollToHeading: function (id) {
      var el = document.getElementById(id);
      if (el) window.scrollTo(0, Math.max(0, topOf(el) - 8));
    },
    scrollToFraction: function (p) {
      var max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
      window.scrollTo(0, max * Math.min(1, Math.max(0, p)));
    },
    search: search,
    next: function () { setCurrent(cur + 1, true); },
    prev: function () { setCurrent(cur - 1, true); },
    clearSearch: function () { clearMarks(); send('onSearch', { count: 0, index: -1, q: '' }); },
    describe: function () { return JSON.stringify(describe()); }
  };

  start();
})();
