"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

const entrySource = fs.readFileSync(path.join(__dirname, "../dashboard/entry.js"), "utf8");

function renderConnectPhone(info) {
  let component;
  let qrPayload;
  let copied;
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
      useEffect: (effect) => effect(),
      useRef: () => ({ current: canvas }),
      useState: (initial) => [initial === null ? info : initial, () => {}],
    },
    fetchJSON: async () => info,
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
  const tree = component();
  const nodes = [];
  const visit = (node) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) return node.forEach(visit);
    nodes.push(node);
    visit(node.children);
  };
  visit(tree);
  const copyButton = nodes.find((node) => node.type === "button" && node.children.includes("Copy connection link"));
  assert.ok(copyButton, "copy button should render");
  return { tree, qrPayload, copy: () => copyButton.props.onClick().then(() => copied) };
}

test("session stays in the scanner QR payload and out of visible or copied links", async () => {
  const session = "loopback-secret-token";
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session });

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
  const copied = await page.copy();

  assert.equal(page.qrPayload, copied);
  assert.equal(new URL(copied).searchParams.has("session"), false);
});
