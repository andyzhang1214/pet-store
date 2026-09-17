(function () {
  'use strict';
  mermaid.initialize({
    startOnLoad: false,
    theme: 'base',
    securityLevel: 'strict',
    themeVariables: {
      fontFamily: "'InstrumentSans','PingFang SC','Microsoft YaHei','Noto Sans CJK SC',sans-serif",
      fontSize: '15px',
      primaryColor: '#F9EFE7',
      primaryBorderColor: '#B8551D',
      primaryTextColor: '#2B2118',
      secondaryColor: '#F3EDE6',
      tertiaryColor: '#FAF7F4',
      lineColor: '#776A5E',
      textColor: '#2B2118',
      edgeLabelBackground: '#FFFFFF',
      clusterBkg: '#FAF7F4',
      clusterBorder: '#E3D9CE',
      noteBkgColor: '#FDF3E0',
      noteTextColor: '#2B2118',
      noteBorderColor: '#D97757'
    },
    flowchart: { nodeSpacing: 42, rankSpacing: 48, padding: 14 },
    er: { padding: 16 },
    sequence: { actorMargin: 56, boxMargin: 12, messageMargin: 40, noteMargin: 12 },
    state: { padding: 12 }
  });
  mermaid.run({ querySelector: 'pre.mermaid' }).catch(function (err) {
    console.error('mermaid render failed:', err);
  });
})();
