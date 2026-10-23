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
      const pairingCanvas = useRef(null);
      const [info, setInfo] = useState(null);
      const [loadError, setLoadError] = useState(false);
      const [qrError, setQrError] = useState(false);
      const [copyStatus, setCopyStatus] = useState('');
      const [devices, setDevices] = useState([]);
      const [pendingRemoval, setPendingRemoval] = useState(null);
      const [removing, setRemoving] = useState(false);
      const [profiles, setProfiles] = useState([]);
      const [selectedProfile, setSelectedProfile] = useState('');
      const [pairing, setPairing] = useState(null);
      const [pairingSeconds, setPairingSeconds] = useState(0);
      const [pairingBusy, setPairingBusy] = useState(false);
      const [pairingError, setPairingError] = useState('');
      const [devicesError, setDevicesError] = useState('');
      const [pairingQrError, setPairingQrError] = useState(false);
      const [paired, setPaired] = useState(null);

      useEffect(() => {
        let active = true;
        sdk.fetchJSON('/api/plugins/beacon/connect-info')
          .then((value) => { if (active) setInfo(value); })
          .catch(() => { if (active) setLoadError(true); });
        return () => { active = false; };
      }, []);

      useEffect(() => {
        let active = true;
        sdk.fetchJSON('/api/plugins/beacon/devices')
          .then((value) => {
            if (!active) return;
            const nextProfiles = Array.isArray(value.profiles) ? value.profiles : [];
            setDevices(Array.isArray(value.devices) ? value.devices : []);
            setProfiles(nextProfiles);
            setSelectedProfile(nextProfiles[0] || '');
          })
          .catch(() => { if (active) setDevicesError('Could not load the paired devices.'); });
        return () => { active = false; };
      }, []);

      const query = info && info.url && info.name ? new URLSearchParams({ url: info.url, name: info.name }) : null;
      const link = query ? `beacon://connect?${query.toString()}` : '';
      const qrPayload = info && info.session
        ? JSON.stringify({ type: 'beacon-connect', url: info.url, session: info.session })
        : link;
      const pairingQrPayload = pairing && pairing.qr_link ? pairing.qr_link : '';
      // One QR at a time: a live pairing code replaces the plain connect QR so nobody scans the wrong one.
      const pairingActive = Boolean(pairing) && pairingSeconds > 0;

      function drawQr(target, payload) {
        try {
          if (typeof window.qrcode !== 'function') throw new Error('QR encoder unavailable');
          const qr = window.qrcode(0, 'M');
          qr.addData(payload);
          qr.make();
          const modules = qr.getModuleCount();
          const border = 4;
          const scale = Math.max(2, Math.floor(256 / (modules + border * 2)));
          const size = (modules + border * 2) * scale;
          const context = target.getContext('2d');
          target.width = size;
          target.height = size;
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
          return true;
        } catch (_) {
          return false;
        }
      }

      useEffect(() => {
        if (!qrPayload || !canvas.current) return;
        setQrError(!drawQr(canvas.current, qrPayload));
      }, [qrPayload, pairingActive]);

      useEffect(() => {
        if (!pairingQrPayload || !pairingCanvas.current) return;
        setPairingQrError(!drawQr(pairingCanvas.current, pairingQrPayload));
      }, [pairingQrPayload]);

      useEffect(() => {
        if (!pairing) return undefined;
        const update = () => {
          const remaining = Math.max(0, Math.ceil((Date.parse(pairing.expires_at) - Date.now()) / 1000));
          setPairingSeconds(remaining);
          if (remaining === 0) clearInterval(timer);
        };
        const timer = setInterval(update, 1000);
        update();
        return () => clearInterval(timer);
      }, [pairing ? pairing.expires_at : null]);

      // While a code is showing, watch for the phone to link (its ticket lasts 15 minutes after the code is used).
      useEffect(() => {
        if (!pairing || !pairing.device_id) return undefined;
        let active = true;
        const stopAt = Date.parse(pairing.expires_at) + 15 * 60 * 1000;
        const timer = setInterval(() => {
          if (Date.now() > stopAt) { clearInterval(timer); return; }
          sdk.fetchJSON('/api/plugins/beacon/devices').then((value) => {
            if (!active || !Array.isArray(value.devices)) return;
            setDevices(value.devices);
            const linked = value.devices.find((device) => device.device_id === pairing.device_id);
            if (linked) { setPaired(linked); setPairing(null); }
          }).catch(() => {});
        }, 3000);
        return () => { active = false; clearInterval(timer); };
      }, [pairing ? pairing.device_id : null]);

      async function copyLink() {
        try {
          await navigator.clipboard.writeText(link);
          setCopyStatus('Copied connection link');
        } catch (_) {
          setCopyStatus('Select the link above to copy it');
        }
      }

      async function pairPhone() {
        if (!selectedProfile) {
          setPairingError('No Hermes profile is available for pairing.');
          return;
        }
        setPairingBusy(true);
        setPairingError('');
        setPaired(null);
        try {
          const value = await sdk.fetchJSON('/api/plugins/beacon/pairing', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ profile: selectedProfile }),
          });
          setPairingSeconds(Math.max(0, Math.ceil((Date.parse(value.expires_at) - Date.now()) / 1000)));
          setPairing(value);
        } catch (_) {
          setPairingError('Could not create a pairing code. Check the secure dashboard address and try again.');
        } finally {
          setPairingBusy(false);
        }
      }

      async function confirmRemoveDevice() {
        if (!pendingRemoval) return;
        setDevicesError('');
        setRemoving(true);
        try {
          await sdk.fetchJSON(`/api/plugins/beacon/devices/${encodeURIComponent(pendingRemoval.device_id)}`, { method: 'DELETE' });
          setDevices(devices.filter((device) => device.device_id !== pendingRemoval.device_id));
          setPendingRemoval(null);
        } catch (_) {
          setDevicesError('Could not remove this phone. Try again.');
        } finally {
          setRemoving(false);
        }
      }

      function renderConnectBody() {
        if (loadError) return h('p', { className: 'mt-3 text-sm text-destructive', role: 'alert' }, 'A secure public dashboard address is not configured.');
        if (!info) return h('p', { className: 'mt-3 text-sm text-muted-foreground', role: 'status' }, 'Loading connection details…');
        return h('div', { className: 'mt-4 space-y-4' },
          h('p', { className: 'text-sm text-muted-foreground' }, info.session ? 'Scan with Beacon to connect instantly' : 'Install Beacon, tap Scan QR, sign in.'),
          !qrError ? h('canvas', {
            ref: canvas,
            className: 'block h-auto max-w-full rounded-md bg-white p-2',
            style: { width: '16rem', imageRendering: 'pixelated' },
            'aria-label': 'Beacon phone connection QR code',
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

      function renderPairing() {
        if (paired && !pairing) return h('p', { className: 'mt-4 text-sm font-medium', role: 'status' }, `${paired.name} is paired.`);
        if (!pairing) return null;
        if (pairingSeconds <= 0) return h('p', { className: 'mt-3 text-sm text-muted-foreground', role: 'status' }, 'Pairing code expired. Select Pair a phone to create another.');
        const minutes = String(Math.floor(pairingSeconds / 60)).padStart(2, '0');
        const seconds = String(pairingSeconds % 60).padStart(2, '0');
        return h('div', { className: 'mt-4 space-y-3', 'aria-live': 'polite' },
          h('p', { className: 'font-mono text-lg font-semibold', 'aria-label': 'Phone pairing code' }, pairing.code),
          pairingQrError ? h('p', { className: 'text-sm text-muted-foreground', role: 'status' }, 'QR unavailable; enter the dashboard address and pairing code.') : h('canvas', {
            ref: pairingCanvas,
            className: 'block h-auto max-w-full rounded-md bg-white p-2',
            style: { width: '16rem', imageRendering: 'pixelated' },
            'aria-label': 'Beacon phone pairing QR code',
          }),
          h('p', { className: 'text-sm text-muted-foreground', role: 'status' }, `expires in ${minutes}:${seconds}`),
        );
      }

      function renderDevices() {
        return h('section', { className: 'max-w-2xl rounded-lg border border-border bg-card p-5 shadow-sm', 'aria-labelledby': 'beacon-devices-title' },
          h('h2', { id: 'beacon-devices-title', className: 'text-lg font-semibold' }, 'Devices'),
          devicesError ? h('p', { className: 'mt-3 text-sm text-destructive', role: 'alert' }, devicesError) : null,
          devices.length ? h('ul', { className: 'mt-3 space-y-3' }, devices.map((device) => h('li', {
            key: device.device_id,
            className: 'flex items-center justify-between gap-3 rounded-md border border-border p-3',
          },
          h('div', { className: 'min-w-0' },
            h('p', { className: 'truncate text-sm font-medium' }, device.name),
            h('p', { className: 'text-xs text-muted-foreground' }, `${device.profile} · Last seen ${new Date(device.last_seen * 1000).toLocaleString()}`),
          ),
          h('button', {
            type: 'button',
            className: 'rounded-md border border-border px-3 py-2 text-sm hover:bg-muted',
            onClick: () => setPendingRemoval(device),
            'aria-label': `Remove ${device.name}`,
          }, 'Remove')))) : h('p', { className: 'mt-3 text-sm text-muted-foreground' }, 'No paired phones.'),
          pendingRemoval ? h('div', { className: 'mt-4 space-y-3 rounded-md border border-destructive/50 bg-destructive/10 p-3', role: 'alertdialog', 'aria-labelledby': 'beacon-remove-title' },
            h('p', { id: 'beacon-remove-title', className: 'text-sm' }, `${pendingRemoval.name} will lose Beacon access and must pair again.`),
            h('div', { className: 'flex justify-end gap-2' },
              h('button', { type: 'button', className: 'rounded-md border border-border px-3 py-2 text-sm', onClick: () => setPendingRemoval(null) }, 'Cancel'),
              h('button', { type: 'button', className: 'rounded-md border border-destructive px-3 py-2 text-sm text-destructive disabled:opacity-50', onClick: confirmRemoveDevice, disabled: removing }, 'Remove device'),
            ),
          ) : null,
        );
      }

      return h('main', { className: 'space-y-4 p-4', 'aria-labelledby': 'beacon-connect-title' },
        h('section', { className: 'max-w-2xl rounded-lg border border-border bg-card p-5 shadow-sm' },
          h('h1', { id: 'beacon-connect-title', className: 'text-xl font-semibold' }, 'Connect phone'),
          pairingActive ? null : renderConnectBody(),
          profiles.length > 1 ? h('label', { className: 'mt-4 block space-y-1 text-sm' },
            h('span', null, 'Profile'),
            h('select', {
              className: 'block w-full rounded-md border border-border bg-background px-3 py-2',
              value: selectedProfile,
              onChange: (event) => setSelectedProfile(event.target.value),
            }, profiles.map((profile) => h('option', { key: profile, value: profile }, profile))),
          ) : null,
          h('button', {
            type: 'button',
            className: 'mt-4 rounded-md border border-border px-3 py-2 text-sm hover:bg-muted disabled:opacity-50',
            onClick: pairPhone,
            disabled: pairingBusy || !selectedProfile,
          }, pairingBusy ? 'Creating code…' : 'Pair a phone'),
          pairingError ? h('p', { className: 'mt-3 text-sm text-destructive', role: 'alert' }, pairingError) : null,
          renderPairing(),
        ),
        renderDevices(),
      );
    }

    registry.register('beacon', ConnectPhone);
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
