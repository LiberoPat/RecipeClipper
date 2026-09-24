// "Clip it yourself" (#37): injected into the page the user clips from, on both platforms
// (Android loads it as a Java resource, iOS from the bundled web/ folder). One copy only.
//
// The page reports what happens on it; native code decides everything. Messages to native are
// JSON strings: {type:"selection", text}, {type:"tag", field}, {type:"image", src}.
// Native calls:
//   RC.sync({marks:[{id, field, label}], newId, photo:{src, label} | null})
//     Shows exactly these marks and hides every other. `newId`, if it names a mark not seen
//     yet, is recorded from the last selection first (then the selection is cleared). So
//     replace, undo and clear are all one declarative call from the draft's state.
//   RC.pickImage(on)
//     While on, the next tapped image is posted and every tap is swallowed.
(function () {
  if (window.RC) return;

  var PAPRIKA = '#BF4A2B';

  function post(message) {
    var json = JSON.stringify(message);
    if (window.RCAndroid && window.RCAndroid.post) {
      window.RCAndroid.post(json);
    } else if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.rc) {
      window.webkit.messageHandlers.rc.postMessage(json);
    }
  }

  var style = document.createElement('style');
  style.textContent =
    '.rc-marked{box-shadow:inset 3px 0 0 ' + PAPRIKA + ' !important;' +
    'background-color:rgba(191,74,43,.08) !important}' +
    '.rc-photo{outline:3px solid ' + PAPRIKA + ' !important;outline-offset:-3px}' +
    'html.rc-picking img{outline:2px dashed ' + PAPRIKA + ' !important;outline-offset:-2px;cursor:pointer}' +
    '#rc-layer{position:absolute;left:0;top:0;width:0;height:0;overflow:visible;z-index:2147483647}' +
    '.rc-tag{position:absolute;transform:translateX(-100%);font:600 12px/1 -apple-system,system-ui,sans-serif;' +
    'color:#fff;background:' + PAPRIKA + ';border-radius:10px;padding:5px 9px;white-space:nowrap;' +
    '-webkit-user-select:none;user-select:none;cursor:pointer;box-shadow:0 1px 3px rgba(0,0,0,.25)}';
  (document.head || document.documentElement).appendChild(style);

  // --- Selection ---

  var lastRange = null;
  var selectionTimer = null;

  document.addEventListener('selectionchange', function () {
    clearTimeout(selectionTimer);
    selectionTimer = setTimeout(function () {
      var selection = window.getSelection();
      var text = '';
      if (selection && selection.rangeCount > 0 && !selection.isCollapsed) {
        text = selection.toString();
        lastRange = selection.getRangeAt(0).cloneRange();
      }
      post({ type: 'selection', text: text });
    }, 120);
  });

  function isBlock(el) {
    var display = window.getComputedStyle(el).display;
    return display && display.indexOf('inline') !== 0 && display !== 'contents';
  }

  function blockOf(node) {
    var el = node.nodeType === 1 ? node : node.parentElement;
    while (el && el !== document.body && !isBlock(el)) el = el.parentElement;
    return el;
  }

  // The innermost blocks holding the range's visible text: the items the selection covers.
  function blocksIn(range) {
    var container = range.commonAncestorContainer;
    var texts = [];
    if (container.nodeType === 3) {
      texts.push(container);
    } else {
      var walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
      var node;
      while ((node = walker.nextNode())) if (range.intersectsNode(node)) texts.push(node);
    }
    var blocks = [];
    texts.forEach(function (text) {
      if (!/\S/.test(text.nodeValue)) return;
      var block = blockOf(text);
      if (block && blocks.indexOf(block) < 0) blocks.push(block);
    });
    return blocks.filter(function (b) {
      return !blocks.some(function (other) { return other !== b && b.contains(other); });
    });
  }

  // --- Marks ---

  var recorded = {}; // mark id -> [elements]
  var current = { marks: [], photo: null };
  var layer = null;

  function ensureLayer() {
    if (!layer || !layer.isConnected) {
      layer = document.createElement('div');
      layer.id = 'rc-layer';
      document.body.appendChild(layer);
    }
    return layer;
  }

  function addTag(el, field, label) {
    var host = ensureLayer();
    var origin = host.getBoundingClientRect();
    var rect = el.getBoundingClientRect();
    var tag = document.createElement('span');
    tag.className = 'rc-tag';
    tag.textContent = label;
    tag.setAttribute('data-rc-field', field);
    tag.setAttribute('role', 'button');
    tag.style.top = Math.max(0, rect.top - origin.top + 4) + 'px';
    tag.style.left = (rect.right - origin.left - 4) + 'px';
    tag.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      post({ type: 'tag', field: field });
    }, true);
    host.appendChild(tag);
  }

  function photoElements(src) {
    return Array.prototype.filter.call(document.images, function (img) {
      return imageSource(img) === src;
    });
  }

  function render() {
    var marked = document.querySelectorAll('.rc-marked, .rc-photo');
    for (var i = 0; i < marked.length; i++) marked[i].classList.remove('rc-marked', 'rc-photo');
    ensureLayer().innerHTML = '';
    current.marks.forEach(function (mark) {
      var els = (recorded[mark.id] || []).filter(function (el) { return el.isConnected; });
      els.forEach(function (el) { el.classList.add('rc-marked'); });
      if (els.length > 0) addTag(els[0], mark.field, mark.label);
    });
    if (current.photo) {
      var imgs = photoElements(current.photo.src);
      imgs.forEach(function (img) { img.classList.add('rc-photo'); });
      if (imgs.length > 0) addTag(imgs[0], 'PHOTO', current.photo.label);
    }
  }

  // --- Images ---

  function absolute(url) {
    try { return new URL(url, document.baseURI).href; } catch (e) { return null; }
  }

  // The image's real address: lazy loaders often leave a data: placeholder in src.
  function imageSource(img) {
    var candidates = [img.currentSrc, img.src, img.getAttribute('data-src'),
      img.getAttribute('data-lazy-src'), img.getAttribute('data-original')];
    for (var i = 0; i < candidates.length; i++) {
      var url = candidates[i] && absolute(candidates[i]);
      if (url && /^https?:/i.test(url)) return url;
    }
    return null;
  }

  var picking = false;

  function imageAt(e) {
    var img = e.target && e.target.closest ? e.target.closest('img') : null;
    if (img) return img;
    if (document.elementsFromPoint) {
      var under = document.elementsFromPoint(e.clientX, e.clientY);
      for (var i = 0; i < under.length; i++) if (under[i].tagName === 'IMG') return under[i];
    }
    return null;
  }

  document.addEventListener('click', function (e) {
    if (!picking) return;
    e.preventDefault();
    e.stopPropagation();
    var img = imageAt(e);
    var src = img && imageSource(img);
    if (src) post({ type: 'image', src: src });
  }, true);

  // --- API ---

  window.RC = {
    sync: function (state) {
      if (state.newId && !recorded[state.newId] && lastRange) {
        recorded[state.newId] = blocksIn(lastRange);
        lastRange = null;
        var selection = window.getSelection();
        if (selection) selection.removeAllRanges();
      }
      current = { marks: state.marks || [], photo: state.photo || null };
      render();
    },
    pickImage: function (on) {
      picking = !!on;
      document.documentElement.classList.toggle('rc-picking', picking);
    }
  };

  // Layout moves as images and fonts load: keep the tags beside their blocks.
  var renderTimer = null;
  function rerender() {
    clearTimeout(renderTimer);
    renderTimer = setTimeout(render, 100);
  }
  window.addEventListener('resize', rerender);
  window.addEventListener('load', rerender);
  if (window.ResizeObserver) new ResizeObserver(rerender).observe(document.body);
})();
