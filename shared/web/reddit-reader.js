// Reddit in "Clip it yourself" (#213): injected by both apps (Android as a Java resource, iOS
// from the bundled web/ folder) into a page on reddit.com only, beside clipper.js. One copy only.
//
// 1. Reading mode, like a content blocker. A signed-out reader of a post gets the text body cut
//    off under a fade to white ("Read more", `.read-more-overflow-cover`), the app and sign-in
//    prompts (the Open App button, Google's One Tap sign-in, bottom sheets), and scrolling locked
//    while a prompt is open. The style below shows the whole body and hides the prompts; the
//    observer puts the style back and unlocks scrolling whenever the page changes, since Reddit
//    renders as it goes. Nothing is clicked and nothing is removed from the page, so Reddit's own
//    check page (#223) and clipper.js's selection and marks work as before.
//    Reddit moves to another post inside the page (its own router, which the apps' navigation
//    rule never sees): a tap on a link, or on the related posts under the comments, which on the
//    emulator swapped the post being clipped for another. So the related posts are hidden and a
//    tapped link to another page does nothing, as a link does in the clip view anyway.
// 2. RCReddit.text(): the post and its loaded comments as markup, for the clip's Text view. Only
//    the post (`shreddit-post`) and the comment tree, or the whole page when neither is there
//    yet; the apps' RedditPageText reads it, so what counts as the text is decided (and tested)
//    natively.
(function () {
  if (window.RCReddit) return;
  if (!/(^|\.)reddit\.com$/i.test(location.hostname)) return;

  var CSS =
    // The post's text body in full: no height cap, no fade, no Read more.
    'shreddit-post-text-body, shreddit-post-text-body [class*="max-h-"],' +
    'shreddit-post-text-body [id$="-post-rtjson-content"]' +
    '{max-height:none !important;overflow:visible !important}' +
    '.read-more-overflow-cover, [id$="-overflow-cover"], [id$="-read-more-button"],' +
    // The app and sign-in prompts.
    '#open-app-header-cta, #shreddit-skip-link, auth-flow-google-one-tap-prompt,' +
    '#credential_picker_container, #credential_picker_iframe, iframe[src*="accounts.google.com/gsi"],' +
    'xpromo-app-selector, xpromo-bottom-sheet, xpromo-nsfw-blocking-container,' +
    'xpromo-untagged-content-blocking-modal, shreddit-async-loader[bundlename*="xpromo"],' +
    'shreddit-async-loader[bundlename*="bottom_sheet"],' +
    // The related posts under the comments.
    '[id$="_related"], reddit-pdp-right-rail-post' +
    '{display:none !important}' +
    // Scrolling, whatever a prompt locked.
    'html, body{overflow-y:auto !important;position:static !important;height:auto !important}';

  var style = document.createElement('style');
  style.id = 'rc-reddit';
  style.textContent = CSS;

  function apply() {
    if (!style.isConnected) (document.head || document.documentElement).appendChild(style);
    [document.documentElement, document.body].forEach(function (el) {
      if (!el) return;
      if (el.style.overflow || el.style.overflowY) { el.style.overflow = ''; el.style.overflowY = ''; }
      if (el.style.position === 'fixed') el.style.position = '';
    });
  }

  apply();
  var timer = null;
  new MutationObserver(function () {
    if (timer) return;
    timer = setTimeout(function () { timer = null; apply(); }, 200);
  }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });

  // A tapped link to another page does nothing (in the capture phase, before Reddit's router;
  // clipper.js's own listener, on the same node, still hears a tap while picking a photo).
  document.addEventListener('click', function (e) {
    var path = e.composedPath ? e.composedPath() : [e.target];
    for (var i = 0; i < path.length; i++) {
      var el = path[i];
      if (el === document) return;
      if (el.tagName !== 'A' || !el.href) continue;
      var to;
      try { to = new URL(el.href, location.href); } catch (x) { return; }
      if (to.origin === location.origin && to.pathname === location.pathname) return;
      e.preventDefault();
      e.stopPropagation();
      return;
    }
  }, true);

  window.RCReddit = {
    text: function () {
      var post = document.querySelector('shreddit-post');
      var tree = document.querySelector('shreddit-comment-tree');
      if (!post && !tree) return document.documentElement.outerHTML;
      return (post ? post.outerHTML : '') + (tree ? tree.outerHTML : '');
    }
  };
})();
