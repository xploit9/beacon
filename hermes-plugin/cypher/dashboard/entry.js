"use strict";

(function () {
  const entry = document.currentScript;
  const registry = window.__HERMES_PLUGINS__;
  const sdk = window.__HERMES_PLUGIN_SDK__;
  if (!registry || typeof registry.register !== 'function' || !sdk || !sdk.React || !sdk.hooks) return;

  function register() {
    const React = sdk.React;
    const h = React.createElement;
    const { useEffect, useRef, useState } = sdk.hooks;

    function ConnectPhone() {
      const canvas = useRef(null);
      const [info, setInfo] = useState(null);
      const [loadError, setLoadError] = useState(false);
      const [qrError, setQrError] = useState(false);
      const [copyStatus, setCopyStatus] = useState('');
      const [claiming, setClaiming] = useState(false);
      const [claimError, setClaimError] = useState(false);

      useEffect(() => {
        let active = true;
        sdk.fetchJSON('/api/plugins/cypher/connect-info')
          .then((value) => { if (active) setInfo(value); })
          .catch(() => { if (active) setLoadError(true); });
        return () => { active = false; };
      }, []);

      const query = info && info.url && info.name ? new URLSearchParams({ url: info.url, name: info.name }) : null;
      const link = query ? `cypher://connect?${query.toString()}` : '';
      const qrPayload = info && info.session
        ? JSON.stringify({ type: 'cypher-connect', url: info.url, session: info.session })
        : link;

      useEffect(() => {
        if (!qrPayload || !canvas.current) return;
        try {
          if (typeof window.qrcode !== 'function') throw new Error('QR encoder unavailable');
          const qr = window.qrcode(0, 'M');
          qr.addData(qrPayload);
          qr.make();
          const modules = qr.getModuleCount();
          const border = 4;
          const scale = Math.max(2, Math.floor(256 / (modules + border * 2)));
          const size = (modules + border * 2) * scale;
          const context = canvas.current.getContext('2d');
          canvas.current.width = size;
          canvas.current.height = size;
          context.fillStyle = '#fff';
          context.fillRect(0, 0, size, size);
          context.fillStyle = '#111';
          for (let row = 0; row < modules; row += 1) {
            for (let column = 0; column < modules; column += 1) {
              if (qr.isDark(row, column)) {
                context.fillRect((column + border) * scale, (row + border) * scale, scale, scale);
              }
            }
          }
          setQrError(false);
        } catch (_) {
          setQrError(true);
        }
      }, [qrPayload]);

      async function copyLink() {
        try {
          await navigator.clipboard.writeText(link);
          setCopyStatus('Copied connection link');
        } catch (_) {
          setCopyStatus('Select the link above to copy it');
        }
      }

      async function claimOwner() {
        if (claiming) return;
        setClaiming(true);
        setClaimError(false);
        try {
          await sdk.fetchJSON('/api/plugins/cypher/owner/claim', { method: 'POST' });
          setInfo(null);
          setLoadError(false);
          try {
            setInfo(await sdk.fetchJSON('/api/plugins/cypher/connect-info'));
          } catch (_) {
            setLoadError(true);
          }
        } catch (_) {
          setClaimError(true);
        } finally {
          setClaiming(false);
        }
      }

      function renderBody() {
        if (loadError) return h('p', { className: 'mt-3 text-sm text-destructive', role: 'alert' }, 'A secure public dashboard address is not configured.');
        if (!info) return h('p', { className: 'mt-3 text-sm text-muted-foreground', role: 'status' }, 'Loading connection details…');
        if (info.claim_required) return h('div', { className: 'mt-3 space-y-2' },
          h('button', {
            type: 'button',
            className: 'rounded-md border border-border px-3 py-2 text-sm hover:bg-muted',
            disabled: claiming,
            onClick: claimOwner,
          }, claiming ? 'Setting up…' : 'Use Cypher with this Hermes account'),
          claimError ? h('p', { className: 'text-sm text-destructive', role: 'alert' }, 'Could not finish setup. Try again.') : null,
        );
        return h('div', { className: 'mt-4 space-y-4' },
          h('p', { className: 'text-sm text-muted-foreground' }, info.session ? 'Scan with Cypher to connect instantly' : 'Install Cypher, tap Scan QR, sign in.'),
          !qrError ? h('canvas', {
            ref: canvas,
            className: 'block h-auto max-w-full rounded-md bg-white p-2',
            style: { width: '16rem', imageRendering: 'pixelated' },
            'aria-label': 'Cypher phone connection QR code',
          }) : h('p', { className: 'text-sm text-muted-foreground', role: 'status' }, 'QR unavailable; copy the connection link below.'),
          h('div', { className: 'space-y-2' },
            h('div', { className: 'break-all rounded-md bg-muted/40 p-3 font-mono text-xs', tabIndex: 0 }, link),
            h('button', {
              type: 'button',
              className: 'rounded-md border border-border px-3 py-2 text-sm hover:bg-muted',
              onClick: copyLink,
            }, 'Copy connection link'),
            copyStatus ? h('span', { className: 'ml-3 text-xs text-muted-foreground', role: 'status' }, copyStatus) : null,
          ),
        );
      }

      return h('main', { className: 'space-y-4 p-4', 'aria-labelledby': 'cypher-connect-title' },
        h('section', { className: 'max-w-2xl rounded-lg border border-border bg-card p-5 shadow-sm' },
          h('h1', { id: 'cypher-connect-title', className: 'text-xl font-semibold' }, 'Connect phone'),
          renderBody(),
        ),
      );
    }

    registry.register('cypher', ConnectPhone);
  }

  if (typeof window.qrcode === 'function') {
    register();
    return;
  }
  if (!entry || !entry.src) {
    register();
    return;
  }
  const qrScript = document.createElement('script');
  qrScript.src = new URL('qrcode.js', entry.src).href;
  qrScript.onload = register;
  qrScript.onerror = register;
  document.head.appendChild(qrScript);
}());
