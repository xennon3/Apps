// Injected into every page on the car screen (WebSurface adds it at document start).
// A window on Android Auto's virtual display can't show the phone's keyboard, so when the
// player taps a text box we ask Dashplay to open the car's own keyboard instead, then type
// what comes back into that box (see Dashplay.fill below).
(function () {
  if (window.__dashplayKeys || !window.DashplayKeys) return;
  window.__dashplayKeys = true;

  var TEXT_TYPES = ['', 'text', 'search', 'email', 'url', 'tel', 'number', 'password'];
  var lastTap = 0, lastAsk = 0, field = null;

  function editable(el) {
    if (!el || el.disabled || el.readOnly) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    return el.tagName === 'INPUT' && TEXT_TYPES.indexOf((el.getAttribute('type') || '').toLowerCase()) >= 0;
  }

  function label(el) {
    var l = el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('title') || '';
    if (!l && el.labels && el.labels[0]) l = el.labels[0].textContent;
    return (l || '').replace(/\s+/g, ' ').trim().slice(0, 60);
  }

  function ask(el) {
    var now = Date.now();
    if (now - lastAsk < 700) return;
    lastAsk = now;
    field = el;
    window.DashplayKeys.open(JSON.stringify({
      label: label(el),
      value: (el.isContentEditable ? el.textContent : el.value) || '',
      password: el.type === 'password',
      enter: el.enterKeyHint || (el.type === 'search' ? 'search' : ''),
    }));
  }

  // Only boxes the player tapped: pages that focus a box by themselves don't open a keyboard.
  document.addEventListener('pointerdown', function () { lastTap = Date.now(); }, true);
  document.addEventListener('focusin', function (e) {
    if (editable(e.target) && Date.now() - lastTap < 1500) ask(e.target);
  }, true);
  // Tapping a box that already has focus (after closing the keyboard) opens it again.
  document.addEventListener('click', function (e) {
    var el = e.target;
    if (editable(el) && el === document.activeElement) ask(el);
  }, true);

  window.__dashplayFill = function (text, submit) {
    var el = field && field.isConnected ? field : document.activeElement;
    if (!el || !editable(el)) return;
    el.focus();
    // Replace the box's text the way a real keyboard would, so sites built on frameworks
    // like React (YouTube included) notice the change.
    try { if (el.isContentEditable) document.execCommand('selectAll'); else el.select(); } catch (e) {}
    var typed = false;
    try { typed = document.execCommand('insertText', false, text); } catch (e) {}
    if (!typed || (!el.isContentEditable && el.value !== text)) {
      if (el.isContentEditable) el.textContent = text;
      else {
        var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text);
      }
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    if (!submit) return;
    el.dispatchEvent(new Event('change', { bubbles: true }));
    // Press Enter. If the page doesn't handle that itself, submit the box's form.
    var init = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    var handled = !el.dispatchEvent(new KeyboardEvent('keydown', init));
    el.dispatchEvent(new KeyboardEvent('keypress', init));
    el.dispatchEvent(new KeyboardEvent('keyup', init));
    if (!handled && el.form) {
      if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
    }
  };
})();
