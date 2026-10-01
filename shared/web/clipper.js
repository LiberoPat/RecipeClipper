// "Clip it yourself" (#37): injected into the page the user clips from, on both platforms
// (Android loads it as a Java resource, iOS from the bundled web/ folder). One copy only.
//
// The page reports what happens on it; native code decides everything. Messages to native are
// JSON strings: {type:"selection", text}, {type:"tag", field, id}, {type:"image", src},
// {type:"noImage"}.
// Native calls:
//   RC.sync({marks:[{id, field, label}], newId, photo:{src, label} | null, armed, clear})
//     Shows exactly these marks and hides every other. `newId`, if it names a mark not seen
//     yet, is recorded from the last selection first (then the selection is cleared). So
//     add, undo and removal are all one declarative call from the draft's state.
//     `armed` (#237) is the text field the cook tapped ("NAME", "INGREDIENTS", "STEPS") or null:
//     while one is armed, a tap on a block of text selects it (see "Field first" below).
//     `clear` is a count: when it changes, the page drops its selection.
//   RC.pickImage(on)
//     While on, the next tap on the page is swallowed and ends picking: it posts the tapped
//     image's address, or noImage when there is no image there with one to read. A tap on a
//     tag goes to the tag. Picking never outlasts one tap, so the page can't be left inert.
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
    '-webkit-user-select:none;user-select:none;cursor:pointer;box-shadow:0 1px 3px rgba(0,0,0,.25)}' +
    // Field first (#237): the blocks a tap selected, and the page's own drag handles for them.
    '.rc-pending{background-color:rgba(191,74,43,.14) !important;outline:2px solid rgba(191,74,43,.6) !important;' +
    'outline-offset:2px;border-radius:2px}' +
    '#rc-handles{position:absolute;left:0;top:0;width:0;height:0;overflow:visible;z-index:2147483647}' +
    '.rc-handle{position:absolute;width:44px;height:44px;margin-left:-22px;touch-action:none;' +
    '-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}' +
    '.rc-handle::after{content:"";position:absolute;left:11px;width:22px;height:22px;background:' + PAPRIKA + ';' +
    'box-shadow:0 1px 3px rgba(0,0,0,.3)}' +
    '.rc-handle-start::after{bottom:4px;border-radius:50% 50% 0 50%}' +
    '.rc-handle-end::after{top:4px;border-radius:0 50% 50% 50%}' +
    'html.rc-armed{-webkit-tap-highlight-color:rgba(191,74,43,.18)}';
  (document.head || document.documentElement).appendChild(style);

  // --- Selection ---

  var lastRange = null;
  var selectionTimer = null;

  document.addEventListener('selectionchange', function () {
    clearTimeout(selectionTimer);
    selectionTimer = setTimeout(function () {
      var selection = window.getSelection();
      var text = '';
      var range = null;
      if (selection && selection.rangeCount > 0 && !selection.isCollapsed) {
        text = selection.toString();
        range = selection.getRangeAt(0);
        lastRange = range.cloneRange();
      }
      // A selection the page didn't make (a long press, the system's handles) is the
      // browser's own: the page's handles and highlight step aside for it.
      if (own && !(range && sameRange(range, own))) clearOwn();
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

  // --- Field first (#237) ---
  //
  // While a text field is armed, a tap selects a block of text, so nobody has to know about a
  // long press: a paragraph, a list item or a heading; for Ingredients or Steps, the whole list
  // the tap landed in. Another tap outside the selection, for Ingredients or Steps, stretches it
  // to cover that block too (tap the first line, then the last). The page draws its own two
  // handles on a selection it made (WebViews don't show theirs for a script's selection), and
  // dragging one moves that end a block at a time. A plain drag anywhere else still scrolls,
  // and a long press still selects as the browser does. The selection reaches native code as any
  // other does, through `selectionchange`; native code decides what it becomes.

  var armed = null;
  var clearSeen = null;
  var own = null; // the range the page selected itself, while it is still the selection
  var pending = [];
  var handles = null;
  var dragging = null;

  function sameRange(a, b) {
    return a.startContainer === b.startContainer && a.startOffset === b.startOffset &&
      a.endContainer === b.endContainer && a.endOffset === b.endOffset;
  }

  function ours(node) {
    var el = node && (node.nodeType === 1 ? node : node.parentElement);
    return !!(el && el.closest && el.closest('#rc-layer, #rc-handles'));
  }

  function unselectable(el) {
    var style = window.getComputedStyle(el);
    return style.userSelect === 'none' || style.webkitUserSelect === 'none';
  }

  function holdsBlocks(el) {
    return Array.prototype.some.call(el.children, function (child) {
      return isBlock(child) && /\S/.test(child.textContent);
    });
  }

  // The block a tap on `node` selects for `field`, or null: nothing with text, a label the page
  // keeps out of selections, or the gap between blocks (a container of other blocks).
  function blockFor(node, field) {
    if (!node || ours(node)) return null;
    var el = blockOf(node);
    if (!el || el === document.body || el === document.documentElement) return null;
    if (field !== 'NAME') {
      var list = el.closest('ul, ol');
      if (list) el = list;
    }
    if (!/\S/.test(el.textContent) || unselectable(el)) return null;
    if (el.tagName !== 'UL' && el.tagName !== 'OL' && holdsBlocks(el)) return null;
    return el;
  }

  function rangeOf(el) {
    var range = document.createRange();
    range.selectNodeContents(el);
    return range;
  }

  function before(a, b) { return a.compareBoundaryPoints(Range.START_TO_START, b) < 0; }
  function after(a, b) { return a.compareBoundaryPoints(Range.END_TO_END, b) > 0; }

  function setOwn(range) {
    own = range.cloneRange();
    var selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    drawOwn();
  }

  function clearOwn() {
    own = null;
    drawOwn();
  }

  function dropSelection() {
    clearOwn();
    var selection = window.getSelection();
    if (selection) selection.removeAllRanges();
  }

  function drawOwn() {
    pending.forEach(function (el) { el.classList.remove('rc-pending'); });
    pending = own ? blocksIn(own) : [];
    pending.forEach(function (el) { el.classList.add('rc-pending'); });
    drawHandles();
  }

  function ensureHandles() {
    if (!handles || !handles.isConnected) {
      handles = document.createElement('div');
      handles.id = 'rc-handles';
      ['start', 'end'].forEach(function (end) {
        var handle = document.createElement('div');
        handle.className = 'rc-handle rc-handle-' + end;
        handle.setAttribute('data-rc-end', end);
        handle.addEventListener('pointerdown', function (e) {
          e.preventDefault();
          e.stopPropagation();
          dragging = end;
          if (handle.setPointerCapture) handle.setPointerCapture(e.pointerId);
        });
        handle.addEventListener('pointermove', function (e) {
          if (dragging !== end) return;
          e.preventDefault();
          drag(end, e.clientX, e.clientY);
        });
        var stop = function () { dragging = null; };
        handle.addEventListener('pointerup', stop);
        handle.addEventListener('pointercancel', stop);
        handles.appendChild(handle);
      });
      document.body.appendChild(handles);
    }
    return handles;
  }

  function drawHandles() {
    if (!own) {
      if (handles) handles.style.display = 'none';
      return;
    }
    var host = ensureHandles();
    host.style.display = '';
    var rects = own.getClientRects();
    if (!rects.length) return;
    var origin = host.getBoundingClientRect();
    var first = rects[0];
    var last = rects[rects.length - 1];
    var start = host.querySelector('.rc-handle-start');
    var end = host.querySelector('.rc-handle-end');
    start.style.left = (first.left - origin.left) + 'px';
    start.style.top = (first.top - origin.top - 44) + 'px';
    end.style.left = (last.right - origin.left) + 'px';
    end.style.top = (last.bottom - origin.top) + 'px';
  }

  // A handle dragged to (x, y): that end of the selection moves to the start (or end) of the
  // block under the finger, just past the handle's knob. Dragged past the other end, the
  // selection is that one block. Near the top or bottom, the page scrolls along.
  function drag(end, x, y) {
    if (!own) return;
    var probe = end === 'start' ? y + 30 : y - 30;
    var under = document.elementsFromPoint ? document.elementsFromPoint(x, probe) : [];
    var block = null;
    for (var i = 0; i < under.length && !block; i++) {
      if (!ours(under[i])) block = blockFor(under[i], 'NAME');
    }
    if (block) {
      var target = rangeOf(block);
      var range = own.cloneRange();
      if (end === 'start') {
        if (after(target, own)) range = target;
        else range.setStart(target.startContainer, target.startOffset);
      } else {
        if (before(target, own)) range = target;
        else range.setEnd(target.endContainer, target.endOffset);
      }
      if (!sameRange(range, own)) setOwn(range);
    }
    if (y < 60) window.scrollBy(0, -12);
    else if (y > window.innerHeight - 60) window.scrollBy(0, 12);
  }

  function setArmed(field) {
    armed = field || null;
    document.documentElement.classList.toggle('rc-armed', !!armed);
  }

  // A tap while a text field is armed selects (in the capture phase, before the page's own
  // handlers: a tapped link or button does nothing else).
  document.addEventListener('click', function (e) {
    if (!armed || picking || ours(e.target)) return;
    var block = blockFor(e.target, armed);
    if (!block) return;
    e.preventDefault();
    e.stopPropagation();
    var range = rangeOf(block);
    if (armed !== 'NAME' && own) {
      if (!before(range, own) && !after(range, own)) return; // already inside the selection
      var stretched = own.cloneRange();
      if (before(range, own)) stretched.setStart(range.startContainer, range.startOffset);
      if (after(range, own)) stretched.setEnd(range.endContainer, range.endOffset);
      range = stretched;
    }
    setOwn(range);
  }, true);

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

  function addTag(el, field, label, id) {
    var host = ensureLayer();
    var origin = host.getBoundingClientRect();
    var rect = el.getBoundingClientRect();
    var tag = document.createElement('span');
    tag.className = 'rc-tag';
    tag.textContent = label;
    tag.setAttribute('data-rc-field', field);
    tag.setAttribute('role', 'button');
    tag.style.top = Math.max(0, rect.top - origin.top + 4) + 'px';
    // Right-aligned to the block, but never past the page's width: an image drawn wider than
    // the page (Reddit's blurred backdrop is scaled 1.2) would put the tag outside, widen the
    // page, and push the page's own right-hand controls off the screen.
    var width = document.documentElement.clientWidth;
    var right = width ? Math.min(rect.right, width - window.scrollX) : rect.right;
    tag.style.left = (right - origin.left - 4) + 'px';
    tag.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      post({ type: 'tag', field: field, id: id });
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
      if (els.length > 0) addTag(els[0], mark.field, mark.label, mark.id);
    });
    if (current.photo) {
      var imgs = photoElements(current.photo.src);
      imgs.forEach(function (img) { img.classList.add('rc-photo'); });
      if (imgs.length > 0) addTag(imgs[0], 'PHOTO', current.photo.label, current.photo.id);
    }
    drawHandles();
  }

  // --- Images ---

  function absolute(url) {
    try { return new URL(url, document.baseURI).href; } catch (e) { return null; }
  }

  // The first address in a srcset ("a.jpg 640w, b.jpg 1080w"): every entry is the same picture.
  function firstInSrcset(srcset) {
    var first = srcset && srcset.trim().split(/\s*,\s+/)[0];
    return first ? first.split(/\s+/)[0] : null;
  }

  // The image's real address: lazy loaders often leave a data: placeholder in src, with the
  // address in a data- attribute or a srcset (on the image or its <picture>'s sources).
  function imageSource(img) {
    var candidates = [img.currentSrc, img.src, img.getAttribute('data-src'),
      img.getAttribute('data-lazy-src'), img.getAttribute('data-original'),
      firstInSrcset(img.getAttribute('srcset')), firstInSrcset(img.getAttribute('data-srcset')),
      firstInSrcset(img.getAttribute('data-lazy-srcset'))];
    var picture = img.parentElement && img.parentElement.tagName === 'PICTURE' ? img.parentElement : null;
    if (picture) {
      Array.prototype.forEach.call(picture.querySelectorAll('source'), function (source) {
        candidates.push(firstInSrcset(source.getAttribute('srcset')), firstInSrcset(source.getAttribute('data-srcset')));
      });
    }
    for (var i = 0; i < candidates.length; i++) {
      var url = candidates[i] && absolute(candidates[i]);
      if (url && /^https?:/i.test(url)) return url;
    }
    return null;
  }

  var picking = false;

  function setPicking(on) {
    picking = !!on;
    document.documentElement.classList.toggle('rc-picking', picking);
  }

  // The image tapped: the target itself, even inside a web component's shadow root (the event
  // path reaches into open ones), else one under the finger, beneath an overlay or a link.
  function imageAt(e) {
    var path = e.composedPath ? e.composedPath() : [];
    for (var i = 0; i < path.length; i++) {
      if (path[i].tagName === 'IMG') return path[i];
      if (path[i] === document) break;
    }
    var img = e.target && e.target.closest ? e.target.closest('img') : null;
    if (img) return img;
    if (document.elementsFromPoint) {
      var under = document.elementsFromPoint(e.clientX, e.clientY);
      for (var j = 0; j < under.length; j++) if (under[j].tagName === 'IMG') return under[j];
    }
    return null;
  }

  // One tap, whatever it lands on, ends picking: an image the app can read becomes the photo;
  // anything else is reported, so native can say so and the page answers taps again.
  document.addEventListener('click', function (e) {
    if (!picking) return;
    setPicking(false);
    var onTag = e.target && e.target.closest && e.target.closest('.rc-tag');
    if (onTag) return; // the tag's own handler clears its field
    e.preventDefault();
    e.stopPropagation();
    var img = imageAt(e);
    var src = img && imageSource(img);
    post(src ? { type: 'image', src: src } : { type: 'noImage' });
  }, true);

  // --- API ---

  window.RC = {
    sync: function (state) {
      var source = own || lastRange;
      if (state.newId && !recorded[state.newId] && source) {
        recorded[state.newId] = blocksIn(source);
        lastRange = null;
        dropSelection();
      }
      if (state.clear !== undefined && state.clear !== clearSeen) {
        if (clearSeen !== null) dropSelection();
        clearSeen = state.clear;
      }
      setArmed(state.armed);
      current = { marks: state.marks || [], photo: state.photo || null };
      render();
    },
    pickImage: setPicking
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
