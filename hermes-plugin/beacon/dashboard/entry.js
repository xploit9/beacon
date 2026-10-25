"use strict";

(function () {
  const registry = window.__HERMES_PLUGINS__;
  const sdk = window.__HERMES_PLUGIN_SDK__;
  if (!registry || typeof registry.register !== 'function' || !sdk || !sdk.React || !sdk.hooks) return;

  const React = sdk.React;
  const h = React.createElement;
  const { useEffect, useState } = sdk.hooks;

  function ConnectPhone() {
    const [info, setInfo] = useState(null);
    const [loadError, setLoadError] = useState(false);
    const [copyStatus, setCopyStatus] = useState('');
    const [devices, setDevices] = useState([]);
    const [pendingRemoval, setPendingRemoval] = useState(null);
    const [removing, setRemoving] = useState(false);
    const [devicesError, setDevicesError] = useState('');

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
        .then((value) => { if (active) setDevices(Array.isArray(value.devices) ? value.devices : []); })
        .catch(() => { if (active) setDevicesError('Could not load your phones.'); });
      return () => { active = false; };
    }, []);

    const link = info && info.url ? info.url : '';

    async function copyLink() {
      try {
        await navigator.clipboard.writeText(link);
        setCopyStatus('Copied link');
      } catch (_) {
        setCopyStatus('Select the link above to copy it');
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
      if (!link) return h('p', { className: 'mt-3 text-sm text-muted-foreground', role: 'status' }, 'Loading connection details…');
      return h('div', { className: 'mt-4 space-y-4' },
        h('p', { className: 'text-sm text-muted-foreground' }, 'Paste this link into Beacon on your phone.'),
        h('div', { className: 'space-y-2' },
          h('div', { className: 'break-all rounded-md bg-muted/40 p-3 font-mono text-xs', tabIndex: 0 }, link),
          h('button', {
            type: 'button',
            className: 'rounded-md border border-border px-3 py-2 text-sm hover:bg-muted',
            onClick: copyLink,
          }, 'Copy link'),
          copyStatus ? h('span', { className: 'ml-3 text-xs text-muted-foreground', role: 'status' }, copyStatus) : null,
        ),
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
          h('p', { className: 'text-xs text-muted-foreground' }, [device.profile, `Last seen ${new Date(device.last_seen * 1000).toLocaleString()}`].filter(Boolean).join(' · ')),
        ),
        h('button', {
          type: 'button',
          className: 'rounded-md border border-border px-3 py-2 text-sm hover:bg-muted',
          onClick: () => setPendingRemoval(device),
          'aria-label': `Remove ${device.name}`,
        }, 'Remove')))) : h('p', { className: 'mt-3 text-sm text-muted-foreground' }, 'No phones yet.'),
        pendingRemoval ? h('div', { className: 'mt-4 space-y-3 rounded-md border border-destructive/50 bg-destructive/10 p-3', role: 'alertdialog', 'aria-labelledby': 'beacon-remove-title' },
          h('p', { id: 'beacon-remove-title', className: 'text-sm' }, `${pendingRemoval.name} will stop getting Beacon notifications and calls. It stays signed in to Hermes.`),
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
        renderConnectBody(),
      ),
      renderDevices(),
    );
  }

  registry.register('beacon', ConnectPhone);
}());
