(function () {
  'use strict';

  document.addEventListener('click', function (e) {
    var trigger = e.target.closest('[data-demo-toggle]');
    if (trigger) {
      var scope = trigger.closest('[data-demo-scope]');
      if (scope) {
        var id = trigger.getAttribute('data-demo-toggle');
        scope.querySelectorAll('[data-demo-panel]').forEach(function (panel) {
          panel.hidden = panel.getAttribute('data-demo-panel') !== id;
        });
        scope.querySelectorAll('[data-demo-toggle]').forEach(function (btn) {
          btn.classList.toggle('is-active', btn.getAttribute('data-demo-toggle') === id);
        });
      }
      return;
    }

    var sw = e.target.closest('.a-switch');
    if (sw) {
      sw.classList.toggle('is-on');
    }
  });
})();
