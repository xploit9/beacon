"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

const entrySource = fs.readFileSync(path.join(__dirname, "../dashboard/entry.js"), "utf8");

function renderConnectPhone(info, fetchJSON = async (request) => request.endsWith("/devices") ? { devices: [], profiles: ["default"] } : info) {
  let component;
  let qrPayload;
  let copied;
  const requests = [];
  const intervals = [];
  const stateUpdates = [];
  const stateValues = [];
  const effectDependencies = [];
  let stateIndex = 0;
  let effectIndex = 0;
  const context = {
    fillRect() {},
    set fillStyle(_) {},
  };
  const canvas = {
    width: 0,
    height: 0,
    getContext: () => context,
  };
  const React = {
    createElement: (type, props, ...children) => ({ type, props: props || {}, children }),
  };
  const sdk = {
    React,
    hooks: {
      useEffect: (effect, dependencies) => {
        const index = effectIndex++;
        const previous = effectDependencies[index];
        if (!dependencies || !previous || dependencies.length !== previous.length || dependencies.some((value, item) => !Object.is(value, previous[item]))) {
          effectDependencies[index] = dependencies;
          effect();
        }
      },
      useRef: () => ({ current: canvas }),
      useState: (initial) => {
        const index = stateIndex++;
        if (stateValues.length <= index) stateValues[index] = index === 0 && initial === null ? info : initial;
        return [stateValues[index], (value) => { stateValues[index] = value; stateUpdates.push({ index, value }); }];
      },
    },
    fetchJSON: async (path, options) => {
      requests.push({ path, options });
      return fetchJSON(path, options);
    },
  };
  const window = {
    __HERMES_PLUGINS__: { register: (_name, registered) => { component = registered; } },
    __HERMES_PLUGIN_SDK__: sdk,
    qrcode: () => ({
      addData: (value) => { qrPayload = value; },
      make() {},
      getModuleCount: () => 1,
      isDark: () => false,
    }),
  };
  const navigator = { clipboard: { writeText: async (value) => { copied = value; } } };
  vm.runInNewContext(entrySource, {
    document: { currentScript: null, head: { appendChild() {} } },
    navigator,
    URLSearchParams,
    setInterval: (callback) => intervals.push(callback),
    clearInterval() {},
    window,
  });
  const render = () => {
    stateIndex = 0;
    effectIndex = 0;
    const tree = component();
    const nodes = [];
    const visit = (node) => {
      if (!node || typeof node !== "object") return;
      if (Array.isArray(node)) return node.forEach(visit);
      nodes.push(node);
      visit(node.children);
    };
    visit(tree);
    return { tree, nodes };
  };
  const firstRender = render();
  const copy = () => firstRender.nodes.find((node) => node.type === "button" && node.children.includes("Copy connection link")).props.onClick().then(() => copied);
  const tick = async () => { for (const callback of [...intervals]) callback(); await new Promise((resolve) => setTimeout(resolve, 0)); };
  return { ...firstRender, render, requests, stateUpdates, get qrPayload() { return qrPayload; }, copy, tick };
}

test("session stays in the scanner QR payload and out of visible or copied links", async () => {
  const session = "loopback-secret-token";
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session });
  assert.ok(page.nodes.some((node) => node.type === "button" && node.children.includes("Copy connection link")));

  const qr = JSON.parse(page.qrPayload);
  const copied = new URL(await page.copy());

  assert.equal(qr.type, "beacon-connect");
  assert.equal(qr.session, session);
  assert.equal(qr.url, "https://hermes.example");
  assert.throws(() => new URL(page.qrPayload));
  assert.equal(copied.searchParams.has("session"), false);
  assert.equal(copied.searchParams.get("url"), "https://hermes.example");
  assert.equal(JSON.stringify(page.tree).includes(session), false);
});

test("token-free dashboard links keep the ordinary QR and copy flow", async () => {
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session: "" });
  assert.ok(page.nodes.some((node) => node.type === "button" && node.children.includes("Copy connection link")));
  const copied = await page.copy();

  assert.equal(page.qrPayload, copied);
  assert.equal(new URL(copied).searchParams.has("session"), false);
});

test("a fresh verified dashboard response goes straight to QR and link", () => {
  const info = { url: "https://hermes.example", name: "Hermes", session: "" };
  const page = renderConnectPhone(info);
  const buttons = page.nodes.filter((node) => node.type === "button");

  assert.deepEqual(buttons.map((button) => button.children[0]), ["Copy connection link", "Pair a phone"]);
  assert.ok(page.nodes.some((node) => node.type === "canvas"));
  assert.deepEqual(page.requests.map(({ path }) => path), [
    "/api/plugins/beacon/connect-info",
    "/api/plugins/beacon/devices",
  ]);
  assert.equal(page.qrPayload, "beacon://connect?url=https%3A%2F%2Fhermes.example&name=Hermes");
  assert.equal(JSON.stringify(page.tree).includes("Use Beacon with this Hermes account"), false);
  assert.equal(JSON.stringify(page.tree).includes("Use Beacon with this Hermes account"), false);
});

test("pairing button mints a code-bearing QR and shows its expiry", async () => {
  const pairing = {
    device_id: "device-1",
    code: "ABC-723",
    expires_at: new Date(Date.now() + 600000).toISOString(),
    dashboard_url: "https://hermes.example",
    qr_link: "beacon://connect?url=https%3A%2F%2Fhermes.example&name=Hermes&code=ABC-723",
  };
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session: "" }, async (path, options) => {
    if (path.endsWith("/devices")) return { devices: [], profiles: ["default"] };
    if (path.endsWith("/pairing") && options?.method === "POST") return pairing;
    return { url: "https://hermes.example", name: "Hermes", session: "" };
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  const pair = page.render().nodes.find((node) => node.type === "button" && node.children.includes("Pair a phone"));

  assert.ok(pair);
  await pair.props.onClick();
  const rendered = page.render();

  const options = page.requests.find(({ path }) => path.endsWith("/pairing")).options;
  assert.equal(options.method, "POST");
  assert.equal(options.headers["Content-Type"], "application/json");
  assert.equal(options.body, JSON.stringify({ profile: "default" }));
  assert.ok(JSON.stringify(rendered.tree).includes("ABC-723"), JSON.stringify(page.stateUpdates));
  assert.match(JSON.stringify(rendered.tree), /\d{1,2}:\d{2}/);
  assert.equal(page.qrPayload, pairing.qr_link);
  assert.ok(!JSON.stringify(rendered.tree).includes("Beacon phone connection QR code"), "only the pairing QR shows while a code is live");
});

test("the code turns into a paired confirmation once the phone links", async () => {
  const pairing = {
    device_id: "device-1",
    code: "ABC-723",
    expires_at: new Date(Date.now() + 600000).toISOString(),
    dashboard_url: "https://hermes.example",
    qr_link: "beacon://connect?url=https%3A%2F%2Fhermes.example&code=ABC-723",
  };
  let linked = false;
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session: "" }, async (path, options) => {
    if (path.endsWith("/devices")) return { devices: linked ? [{ device_id: "device-1", name: "Pixel", profile: "default", created_at: 1, last_seen: 1 }] : [], profiles: ["default"] };
    if (path.endsWith("/pairing") && options?.method === "POST") return pairing;
    return { url: "https://hermes.example", name: "Hermes", session: "" };
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  await page.render().nodes.find((node) => node.type === "button" && node.children.includes("Pair a phone")).props.onClick();
  page.render();
  linked = true;
  await page.tick();
  const text = JSON.stringify(page.render().tree);
  assert.ok(text.includes("Pixel is paired."), text);
  assert.ok(!text.includes("ABC-723"));
});

test("pairing uses the selected Hermes profile when several are available", async () => {
  let pairingOptions;
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session: "" }, async (path, options) => {
    if (path.endsWith("/devices")) return { devices: [], profiles: ["default", "work"] };
    if (path.endsWith("/pairing") && options?.method === "POST") {
      pairingOptions = options;
      return {
        device_id: "device-1",
        code: "ABC-723",
        expires_at: new Date(Date.now() + 600000).toISOString(),
        dashboard_url: "https://hermes.example",
        qr_link: "beacon://connect?code=ABC-723",
      };
    }
    return { url: "https://hermes.example", name: "Hermes", session: "" };
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  const select = page.render().nodes.find((node) => node.type === "select");

  assert.ok(select);
  select.props.onChange({ target: { value: "work" } });
  const pair = page.render().nodes.find((node) => node.type === "button" && node.children.includes("Pair a phone"));
  await pair.props.onClick();

  assert.equal(JSON.parse(pairingOptions.body).profile, "work");
});

test("devices can be removed from the Beacon dashboard tab", async () => {
  const requests = [];
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session: "" }, async (path, options) => {
    requests.push({ path, options });
    if (path.endsWith("/devices")) return { devices: [{ device_id: "device-1", name: "Pixel", profile: "default", last_seen: 1 }], profiles: ["default"] };
    return { removed: true, device_id: "device-1" };
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  const rendered = page.render();
  const remove = rendered.nodes.find((node) => node.type === "button" && node.children.includes("Remove"));

  assert.ok(remove);
  await remove.props.onClick();
  assert.equal(requests.some(({ path, options }) => path.endsWith("/devices/device-1") && options?.method === "DELETE"), false);
  const confirmation = page.render();
  assert.ok(JSON.stringify(confirmation.tree).includes("will lose Beacon access and must pair again"));
  const confirm = confirmation.nodes.find((node) => node.type === "button" && node.children.includes("Remove device"));
  assert.ok(confirm);
  await confirm.props.onClick();

  assert.ok(requests.some(({ path, options }) => path.endsWith("/devices/device-1") && options?.method === "DELETE"));
});

test("an unowned response never exposes a claim action", () => {
  const page = renderConnectPhone({ claim_required: true });

  assert.equal(page.nodes.some((node) => node.type === "button" && node.children.includes("Use Beacon with this Hermes account")), false);
  assert.equal(JSON.stringify(page.tree).includes("Use Beacon with this Hermes account"), false);
  assert.equal(JSON.stringify(page.tree).includes("Use Beacon with this Hermes account"), false);
  assert.equal(page.requests.some(({ path }) => path.endsWith("/owner/claim")), false);
});
