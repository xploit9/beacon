"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

const entrySource = fs.readFileSync(path.join(__dirname, "../dashboard/entry.js"), "utf8");

function renderConnectPhone(info, fetchJSON = async () => info) {
  let component;
  let qrPayload;
  let copied;
  const requests = [];
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
        if (stateValues.length <= index) stateValues[index] = initial === null ? info : initial;
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
  return { ...firstRender, render, requests, stateUpdates, get qrPayload() { return qrPayload; }, copy };
}

test("session stays in the scanner QR payload and out of visible or copied links", async () => {
  const session = "loopback-secret-token";
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session });
  assert.ok(page.nodes.some((node) => node.type === "button" && node.children.includes("Copy connection link")));

  const qr = JSON.parse(page.qrPayload);
  const copied = new URL(await page.copy());

  assert.equal(qr.type, "cypher-connect");
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

  assert.deepEqual(buttons.map((button) => button.children[0]), ["Copy connection link"]);
  assert.ok(page.nodes.some((node) => node.type === "canvas"));
  assert.deepEqual(page.requests.map(({ path }) => path), ["/api/plugins/cypher/connect-info"]);
  assert.equal(page.qrPayload, "cypher://connect?url=https%3A%2F%2Fhermes.example&name=Hermes");
  assert.equal(JSON.stringify(page.tree).includes("Use Cypher with this Hermes account"), false);
});

test("an unowned response never exposes a claim action", () => {
  const page = renderConnectPhone({ claim_required: true });

  assert.equal(page.nodes.some((node) => node.type === "button" && node.children.includes("Use Cypher with this Hermes account")), false);
  assert.equal(JSON.stringify(page.tree).includes("Use Cypher with this Hermes account"), false);
  assert.equal(page.requests.some(({ path }) => path.endsWith("/owner/claim")), false);
});
