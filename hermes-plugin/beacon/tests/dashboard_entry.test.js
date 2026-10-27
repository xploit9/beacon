"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

const entrySource = fs.readFileSync(path.join(__dirname, "../dashboard/entry.js"), "utf8");

function renderConnectPhone(info, fetchJSON = async (request) => request.endsWith("/devices") ? { devices: [], profiles: ["default"] } : info) {
  let component;
  let copied;
  const requests = [];
  const stateValues = [];
  const effectDependencies = [];
  let stateIndex = 0;
  let effectIndex = 0;
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
      useState: (initial) => {
        const index = stateIndex++;
        if (stateValues.length <= index) stateValues[index] = index === 0 && initial === null ? info : initial;
        return [stateValues[index], (value) => { stateValues[index] = value; }];
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
  };
  const navigator = { clipboard: { writeText: async (value) => { copied = value; } } };
  vm.runInNewContext(entrySource, { navigator, window });
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
    return { tree, nodes, text: nodes.flatMap((node) => node.children).filter((child) => typeof child === "string").join("\n") };
  };
  const firstRender = render();
  const copy = () => firstRender.nodes.find((node) => node.type === "button" && node.children.includes("Copy link")).props.onClick().then(() => copied);
  return { ...firstRender, render, requests, copy };
}

test("the page shows the Hermes link to paste and copies exactly it", async () => {
  const session = "loopback-secret-token";
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session });

  assert.ok(page.nodes.some((node) => node.children.includes("https://hermes.example")));
  assert.equal(await page.copy(), "https://hermes.example");
  assert.equal(JSON.stringify(page.tree).includes(session), false);
});

test("a fresh verified dashboard response has only the link and the device list", () => {
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session: "" });
  const buttons = page.nodes.filter((node) => node.type === "button");

  assert.deepEqual(buttons.map((button) => button.children[0]), ["Copy link"]);
  assert.equal(page.nodes.some((node) => node.type === "canvas" || node.type === "select"), false);
  assert.equal(/pair|qr|code/i.test(page.text), false, page.text);
  assert.deepEqual(page.requests.map(({ path }) => path), [
    "/api/plugins/beacon/connect-info",
    "/api/plugins/beacon/devices",
  ]);
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
  assert.ok(confirmation.text.includes("Pixel will stop getting Beacon notifications and calls. It stays signed in to Hermes."));
  const confirm = confirmation.nodes.find((node) => node.type === "button" && node.children.includes("Remove device"));
  assert.ok(confirm);
  await confirm.props.onClick();

  assert.ok(requests.some(({ path, options }) => path.endsWith("/devices/device-1") && options?.method === "DELETE"));
  assert.ok(page.render().text.includes("No phones yet."));
});

test("a linked phone shows its profile only when it has one", async () => {
  const page = renderConnectPhone({ url: "https://hermes.example", name: "Hermes", session: "" }, async (path) => {
    if (path.endsWith("/devices")) return { devices: [
      { device_id: "device-1", name: "Old phone", profile: "work", last_seen: 1 },
      { device_id: "device-2", name: "New phone", profile: "", last_seen: 1 },
    ], profiles: ["default", "work"] };
    return { url: "https://hermes.example", name: "Hermes", session: "" };
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  const lines = page.render().text.split("\n").filter((line) => line.includes("Last seen"));

  assert.equal(lines.length, 2);
  assert.ok(lines[0].startsWith("work · Last seen"), lines[0]);
  assert.ok(lines[1].startsWith("Last seen"), lines[1]);
});

test("an unowned response never exposes a claim action", () => {
  const page = renderConnectPhone({ claim_required: true });

  assert.equal(page.nodes.some((node) => node.type === "button" && node.children.includes("Use Beacon with this Hermes account")), false);
  assert.equal(JSON.stringify(page.tree).includes("Use Beacon with this Hermes account"), false);
});
