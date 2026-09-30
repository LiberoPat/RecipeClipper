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
    'shreddit-async-loader[bundlename*="bottom_sheet"]' +
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

  window.RCReddit = {
    text: function () {
      var post = document.querySelector('shreddit-post');
      var tree = document.querySelector('shreddit-comment-tree');
      if (!post && !tree) return document.documentElement.outerHTML;
      return (post ? post.outerHTML : '') + (tree ? tree.outerHTML : '');
    }
  };
})();
