#!/usr/bin/env node
/**
 * Machine visual QA: start no servers. Drive a local Chromium/Edge via CDP
 * (Node 22 WebSocket, zero npm deps), capture screenshots, and assert what a
 * machine can actually settle. A pass without a screenshot is refused.
 *
 * Usage:
 *   node visual-qa.mjs --url http://127.0.0.1:4173/ --out DIR \
 *     --scenario "700x400: text=Create visible" \
 *     --scenario "1280x720: css=.settings-panel visible" \
 *     --scenario "1280x720: testid=save click -> css=.saved visible" \
 *     --scenario "1280x720: css=canvas click@0.5,0.86 -> text=Placed visible" \
 *     --scenario "1280x720: no-console-errors"
 *
 * Scenario grammar
 *   WxH: <matcher> <assertion> [-> <matcher> <assertion>]
 *   WxH: no-console-errors
 *
 *   matcher    text=Label | css=SELECTOR | testid=VALUE | role=ROLE
 *              A bare label is read as text=Label, so the older
 *              "700x400:Create visible" still means what it did.
 *   assertion  visible | hidden | click | click@FX,FY
 *              click@0.5,0.86 presses that fraction across and down the element's
 *              own box instead of its centre. For a full-window canvas the centre
 *              is the only point a plain `click` can ever reach.
 *
 * `click` on its own asserts that clicking the element changes the page: the
 * DOM digest before and after must differ. Anything more specific belongs
 * after `->`, where it is written down instead of guessed.
 *
 * Nothing in this file knows the name of any project. An earlier version
 * matched `.mode-switch`, `.mobile-view-note` and the word "growing", which
 * are one application's private DOM — a generic tool that silently only works
 * on the app it was written against is worse than one that admits its scope.
 */
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

const args = process.argv.slice(2);
function opt(name, fallback = null) {
  const i = args.indexOf(name);
  if (i < 0) return fallback;
  return args[i + 1] ?? fallback;
}
function opts(name) {
  const out = [];
  for (let i = 0; i < args.length; i++) if (args[i] === name && args[i + 1]) out.push(args[++i]);
  return out;
}

const url = opt("--url");
const outDir = opt("--out");
const browserBin = opt("--browser");
if (!url || !outDir) {
  console.error("usage: visual-qa.mjs --url URL --out DIR --scenario 'WxH: text=Label visible'");
  process.exit(2);
}

const scenarios = opts("--scenario");
if (scenarios.length === 0) {
  fail("visual_qa_unavailable", "no scenarios given");
}

const browser = browserBin || findBrowser();
if (!browser) fail("visual_qa_unavailable", "no Chrome/Edge executable found");

mkdirSync(outDir, { recursive: true });

function fail(code, message, extra = {}) {
  const report = { ok: false, code, message, ...extra };
  try {
    mkdirSync(outDir, { recursive: true });
    writeFileSync(join(outDir, "visual-qa.json"), JSON.stringify(report, null, "\t") + "\n");
  } catch {
    /* still fail closed */
  }
  console.log(JSON.stringify(report));
  process.exit(code === "visual_qa_failed" ? 1 : 2);
}

// ---------------------------------------------------------------- scenarios

const ASSERTIONS = new Set(["visible", "hidden", "click"]);
/**
 * `click` may name where inside the element to press, as a fraction of its box:
 * `click@0.5,0.86` is halfway across and most of the way down. A canvas is one
 * element the size of the window, so without this the only reachable point is its
 * centre — which for a landscape is whatever happens to be in the middle of the
 * frame, and never the water, the sky or the shore a scenario means to test.
 */
const CLICK_AT = /^click@(-?\d*\.?\d+)\s*,\s*(-?\d*\.?\d+)$/i;

/**
 * Splits "css=.panel > .row visible" into a matcher and an assertion. The
 * assertion is the LAST word, because a CSS selector may contain spaces and
 * a label almost always does.
 */
function parseStep(raw, fallbackAssertion = "visible") {
  const text = String(raw).trim();
  if (!text) return null;
  const words = text.split(/\s+/);
  let assertion = fallbackAssertion;
  let at = null;
  let body = text;
  const last = words.length > 1 ? words[words.length - 1].toLowerCase() : "";
  const point = last.match(CLICK_AT);
  if (point) {
    const fx = Number(point[1]);
    const fy = Number(point[2]);
    if (!(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1)) {
      throw new Error(`scenario "${text}": click@x,y takes fractions of the element box `
        + `between 0 and 1, got ${point[1]},${point[2]}`);
    }
    assertion = "click";
    at = { fx, fy };
    words.pop();
    body = words.join(" ");
  } else if (words.length > 1 && ASSERTIONS.has(last)) {
    assertion = words.pop().toLowerCase();
    body = words.join(" ");
  }
  const typed = body.match(/^(text|css|testid|role)\s*=\s*(.+)$/i);
  const kind = typed ? typed[1].toLowerCase() : "text";
  const value = typed ? typed[2].trim() : body.trim();
  if (!value) return null;
  return { kind, value, assertion, at, raw: text };
}

function parseScenario(raw) {
  const sized = String(raw).match(/^(\d+)\s*[x×]\s*(\d+)\s*(mobile)?\s*:\s*(.+)$/i);
  const width = sized ? Number(sized[1]) : 1280;
  const height = sized ? Number(sized[2]) : 720;
  // Mobile emulation is opt-in. Chrome's mobile mode gives a page with no
  // <meta name="viewport"> a 980px layout viewport, so "700x400" would be
  // rendered at 980 and reported as 700 — a tester lying about what it saw.
  const mobile = Boolean(sized && sized[3]);
  let rest = (sized ? sized[4] : String(raw)).trim();

  if (/^no[-_ ]?console[-_ ]?errors$/i.test(rest)) {
    return { raw, width, height, mobile, consoleOnly: true, steps: [] };
  }

  // Tolerate the older free-form wording: "Create visible", "Create click".
  const arrow = rest.split(/\s*->\s*/);
  const steps = [];
  const first = parseStep(arrow[0]);
  if (first) steps.push(first);
  for (const extra of arrow.slice(1)) {
    const step = parseStep(extra);
    if (step) steps.push(step);
  }
  const noConsoleErrors = /\bno[-_ ]?console[-_ ]?errors\b/i.test(rest);
  return { raw, width, height, mobile, consoleOnly: false, steps, noConsoleErrors };
}

function findBrowser() {
  const candidates = [
    process.env.WARDEN_BROWSER,
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
    "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/usr/bin/microsoft-edge",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  ].filter(Boolean);
  return candidates.find((path) => existsSync(path)) || null;
}

async function freePort() {
  return await new Promise((resolve, reject) => {
    const server = createServer();
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close((error) => (error ? reject(error) : resolve(port)));
    });
    server.on("error", reject);
  });
}

async function waitForCdp(port, ms) {
  const deadline = Date.now() + ms;
  let last = "";
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (response.ok) {
        const body = await response.json();
        if (body.webSocketDebuggerUrl) return body.webSocketDebuggerUrl;
      }
      last = await response.text();
    } catch (error) {
      last = String(error?.message || error);
    }
    await sleep(200);
  }
  throw new Error("CDP did not come up on port " + port + ": " + last);
}

async function pageWebSocket(versionWs) {
  const portMatch = versionWs.match(/:(\d+)/);
  const port = portMatch ? portMatch[1] : "9222";
  const list = await fetch(`http://127.0.0.1:${port}/json/list`).then((r) => r.json());
  const page = list.find((t) => t.type === "page" && t.webSocketDebuggerUrl) || list.find((t) => t.webSocketDebuggerUrl);
  if (!page) throw new Error("browser started but has no page target");
  return page.webSocketDebuggerUrl;
}

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.next = 0;
    this.pending = new Map();
    this.events = [];
    this.consoleErrors = [];
    this.pageErrors = [];
    ws.addEventListener("message", (event) => {
      const message = JSON.parse(String(event.data));
      if (message.id && this.pending.has(message.id)) {
        const { resolve, reject } = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) reject(new Error(message.error.message || JSON.stringify(message.error)));
        else resolve(message.result || {});
      } else {
        this.events.push(message);
        this.collectDiagnostics(message);
      }
    });
  }

  /** A page that throws is a visual defect the screenshot may not show. */
  collectDiagnostics(message) {
    if (message.method === "Runtime.consoleAPICalled" && message.params?.type === "error") {
      const text = (message.params.args || [])
        .map((a) => a.value ?? a.description ?? a.unserializableValue ?? "")
        .join(" ")
        .trim();
      if (text) this.consoleErrors.push(text.slice(0, 400));
    }
    if (message.method === "Runtime.exceptionThrown") {
      const details = message.params?.exceptionDetails;
      const text = details?.exception?.description || details?.text || "";
      if (text) this.pageErrors.push(String(text).slice(0, 400));
    }
  }

  static connect(url) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url);
      const timer = setTimeout(() => reject(new Error("CDP websocket timeout")), 10_000);
      ws.addEventListener("open", () => {
        clearTimeout(timer);
        resolve(new Cdp(ws));
      });
      ws.addEventListener("error", (error) => {
        clearTimeout(timer);
        reject(error);
      });
    });
  }

  send(method, params = {}) {
    const id = ++this.next;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  async waitEvent(name, ms = 15_000) {
    const deadline = Date.now() + ms;
    while (Date.now() < deadline) {
      const hit = this.events.find((e) => e.method === name);
      if (hit) return hit;
      await sleep(50);
    }
    throw new Error("timed out waiting for " + name);
  }

  close() {
    try {
      this.ws.close();
    } catch {
      /* ignore */
    }
  }
}

// ------------------------------------------------------------- page probing

/**
 * Runs inside the page. Returns the geometry and computed style of the element
 * a matcher selects, plus enough context for a human to see why a miss was a
 * miss. Kept as one expression so it can be sent through Runtime.evaluate.
 */
const PROBE = `(matcher) => {
  const visibleEnough = (el) => {
    const style = getComputedStyle(el);
    const rect = el.getBoundingClientRect();
    return {
      tag: el.tagName,
      id: el.id || null,
      className: String(el.className || ""),
      text: (el.innerText || el.getAttribute("aria-label") || el.getAttribute("title") || "").trim().slice(0, 200),
      display: style.display,
      visibility: style.visibility,
      opacity: Number(style.opacity),
      pointerEvents: style.pointerEvents,
      width: rect.width,
      height: rect.height,
      x: rect.x,
      y: rect.y,
    };
  };
  let element = null;
  let considered = [];
  if (matcher.kind === "css") {
    element = document.querySelector(matcher.value);
  } else if (matcher.kind === "testid") {
    element = document.querySelector('[data-testid="' + matcher.value + '"]')
      || document.querySelector('[data-test-id="' + matcher.value + '"]')
      || document.querySelector('[data-test="' + matcher.value + '"]');
  } else if (matcher.kind === "role") {
    element = document.querySelector('[role="' + matcher.value + '"]');
  } else {
    const wanted = matcher.value.toLowerCase();
    const nodes = [...document.querySelectorAll("button, a, input, select, textarea, [role], label, h1, h2, h3, p, span, div")];
    const named = nodes.filter((el) => {
      const own = (el.innerText || el.getAttribute("aria-label") || el.value || "").trim().toLowerCase();
      return own.includes(wanted);
    });
    // Prefer an interactive element, then the smallest match: a label inside a
    // wrapper should not resolve to <body>.
    const interactive = named.filter((el) => /^(BUTTON|A|INPUT|SELECT|TEXTAREA)$/.test(el.tagName) || el.getAttribute("role"));
    const pool = interactive.length ? interactive : named;
    element = pool.sort((a, b) => {
      const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
      return (ra.width * ra.height) - (rb.width * rb.height);
    })[0] || null;
    considered = [...document.querySelectorAll("button, a, [role=button]")]
      .map((el) => (el.innerText || el.getAttribute("aria-label") || "").trim())
      .filter(Boolean).slice(0, 40);
  }
  return {
    title: document.title,
    innerWidth: window.innerWidth,
    innerHeight: window.innerHeight,
    orientation: window.innerWidth >= window.innerHeight ? "landscape" : "portrait",
    found: element ? visibleEnough(element) : null,
    considered,
    digest: document.body ? document.body.innerHTML.length + ":" + (document.body.innerText || "").length : "0:0",
  };
}`;

async function probe(cdp, matcher) {
  const evaluated = await cdp.send("Runtime.callFunctionOn", {
    functionDeclaration: PROBE,
    executionContextId: undefined,
    arguments: [{ value: matcher }],
    returnByValue: true,
  }).catch(async () => cdp.send("Runtime.evaluate", {
    expression: `(${PROBE})(${JSON.stringify(matcher)})`,
    returnByValue: true,
  }));
  return evaluated.result?.value || { error: "probe returned nothing", found: null };
}

/** Visibility as a user would judge it, not as the DOM tree reports it. */
function isVisible(found) {
  if (!found) return false;
  if (found.display === "none" || found.visibility === "hidden") return false;
  if (Number.isFinite(found.opacity) && found.opacity < 0.05) return false;
  if ((Number(found.width) || 0) < 2 || (Number(found.height) || 0) < 2) return false;
  return true;
}

function describeMiss(step, info) {
  if (step.kind === "text" && info?.considered?.length) {
    return `no element whose text contains "${step.value}"; the page offers: ${info.considered.join(" | ")}`;
  }
  return `no element matched ${step.kind}=${step.value}`;
}

async function shoot(cdp, outDir, name) {
  const shot = await cdp.send("Page.captureScreenshot", { format: "png" });
  const file = join(outDir, `${name}.png`);
  writeFileSync(file, Buffer.from(shot.data, "base64"));
  return file;
}

async function runStep(cdp, step, outDir, label) {
  const info = await probe(cdp, { kind: step.kind, value: step.value });
  const found = info.found;
  const visible = isVisible(found);
  const result = {
    matcher: `${step.kind}=${step.value}`,
    assertion: step.assertion,
    visible,
    element: found,
    ok: false,
  };
  if (step.assertion === "hidden") {
    result.ok = !visible;
    if (!result.ok) result.why = `${step.kind}=${step.value} was expected to be hidden but is visible`;
    return { result, info };
  }
  if (!visible) {
    result.why = found
      ? `matched an element that is not visible: display=${found.display} visibility=${found.visibility} `
        + `opacity=${found.opacity} size=${Math.round(found.width)}x${Math.round(found.height)}`
      : describeMiss(step, info);
    return { result, info };
  }
  if (step.assertion !== "click") {
    result.ok = true;
    return { result, info };
  }

  const before = info.digest;
  const fx = step.at ? step.at.fx : 0.5;
  const fy = step.at ? step.at.fy : 0.5;
  const x = Math.round((found.x || 0) + (found.width || 0) * fx);
  const y = Math.round((found.y || 0) + (found.height || 0) * fy);
  await cdp.send("Input.dispatchMouseEvent", { type: "mouseMoved", x, y });
  await cdp.send("Input.dispatchMouseEvent", { type: "mousePressed", x, y, button: "left", clickCount: 1 });
  await cdp.send("Input.dispatchMouseEvent", { type: "mouseReleased", x, y, button: "left", clickCount: 1 });
  await sleep(1200);
  const after = await probe(cdp, { kind: step.kind, value: step.value });
  result.clicked_at = { x, y };
  result.screenshot_after = await shoot(cdp, outDir, `${label}-after-click`);
  result.dom_changed = after.digest !== before;
  result.ok = result.dom_changed;
  if (!result.ok) {
    result.why = `clicking ${step.kind}=${step.value} at ${x},${y} changed nothing in the DOM. `
      + `Either the control is inert, or the effect is not observable here — if it is not, say what to look `
      + `for with "-> css=... visible" instead of relying on the click alone.`;
  }
  return { result, info: after };
}

async function runScenario(cdp, scenario, outDir, targetUrl) {
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: scenario.width,
    height: scenario.height,
    deviceScaleFactor: 1,
    mobile: scenario.mobile,
    screenWidth: scenario.width,
    screenHeight: scenario.height,
  });
  cdp.events = [];
  cdp.consoleErrors = [];
  cdp.pageErrors = [];
  await cdp.send("Page.navigate", { url: targetUrl });
  try {
    await cdp.waitEvent("Page.loadEventFired", 20_000);
  } catch {
    /* a SPA may never fire it; the probe below still decides */
  }
  await sleep(800);

  const label = `${scenario.width}x${scenario.height}${scenario.mobile ? "-mobile" : ""}`;
  const screenshot = await shoot(cdp, outDir, label);

  // What the CSS actually saw. A page without <meta name="viewport"> lays out
  // at 980px under mobile emulation, so a scenario written as 700x400 would be
  // judged at 980 and still reported as 700. Say so instead of guessing.
  const measured = await probe(cdp, { kind: "css", value: "html" });
  const effective = { width: measured.innerWidth, height: measured.innerHeight };
  const drift = Math.abs((effective.width || 0) - scenario.width);
  const viewportHonoured = drift <= 20;

  const steps = [];
  let page = null;
  if (viewportHonoured) {
    for (const step of scenario.steps) {
      const { result, info } = await runStep(cdp, step, outDir, label);
      page = page || info;
      steps.push(result);
      if (!result.ok) break; // a later step's meaning depends on the earlier one holding
    }
  }

  const consoleErrors = [...cdp.pageErrors, ...cdp.consoleErrors];
  const requireQuietConsole = scenario.consoleOnly || scenario.noConsoleErrors;
  const stepsOk = steps.length > 0 && steps.every((s) => s.ok);
  const consoleOk = !requireQuietConsole || consoleErrors.length === 0;
  const ok = viewportHonoured && (scenario.consoleOnly ? true : stepsOk) && consoleOk;

  let why = null;
  if (!viewportHonoured) {
    why = `asked for a ${scenario.width}px viewport but the page laid out at ${effective.width}px, `
      + `so nothing here was tested at the width the scenario names. `
      + (scenario.mobile
        ? `Mobile emulation gives a page without <meta name="viewport"> a 980px layout viewport — `
          + `drop the "mobile" keyword, or add the meta tag to the page.`
        : `Check for a <meta name="viewport"> that pins a fixed width.`);
  } else if (!ok) {
    if (!consoleOk) {
      why = `the page logged ${consoleErrors.length} console error(s): ${consoleErrors[0]}`;
    } else {
      why = steps.find((s) => !s.ok)?.why || "no assertion was made";
    }
  }

  return {
    raw: scenario.raw,
    viewport: { width: scenario.width, height: scenario.height, mobile: scenario.mobile },
    viewport_effective: effective,
    viewport_honoured: viewportHonoured,
    ok,
    screenshot,
    steps,
    console_errors: consoleErrors,
    console_checked: requireQuietConsole,
    page: page ? { title: page.title, innerWidth: page.innerWidth, innerHeight: page.innerHeight, orientation: page.orientation } : null,
    why,
  };
}

async function main() {
  const parsed = scenarios.map(parseScenario);
  const empty = parsed.find((s) => !s.consoleOnly && s.steps.length === 0);
  if (empty) {
    fail("visual_qa_unavailable",
      `scenario "${empty.raw}" states no assertion. Use "WxH: text=Label visible", `
      + `"WxH: css=SELECTOR visible", "WxH: testid=ID click" or "WxH: no-console-errors".`);
  }

  const debugPort = await freePort();
  // Outside --out on purpose: Chrome writes megabytes of its own state into a profile
  // directory, and a run's evidence folder should hold evidence, not an Edge avatar.
  const profileDir = mkdtempSync(join(tmpdir(), "warden-visual-qa-"));

  const child = spawn(
    browser,
    [
      "--headless=new",
      "--disable-gpu",
      "--no-first-run",
      "--no-default-browser-check",
      `--remote-debugging-port=${debugPort}`,
      `--user-data-dir=${profileDir}`,
      "about:blank",
    ],
    { stdio: ["ignore", "pipe", "pipe"] }
  );

  let stderr = "";
  child.stderr.on("data", (chunk) => {
    stderr += chunk.toString();
    if (stderr.length > 8000) stderr = stderr.slice(-4000);
  });

  try {
    const wsUrl = await waitForCdp(debugPort, 20_000);
    const pageWs = await pageWebSocket(wsUrl);
    const cdp = await Cdp.connect(pageWs);
    await cdp.send("Page.enable");
    await cdp.send("Runtime.enable");
    await cdp.send("Log.enable").catch(() => {});
    const results = [];
    for (const scenario of parsed) {
      results.push(await runScenario(cdp, scenario, outDir, url));
    }
    cdp.close();
    const ok = results.every((row) => row.ok);
    const report = {
      ok,
      code: ok ? "passed" : "visual_qa_failed",
      url,
      browser,
      scenarios: results,
    };
    writeFileSync(join(outDir, "visual-qa.json"), JSON.stringify(report, null, "\t") + "\n");
    console.log(JSON.stringify(report));
    process.exit(ok ? 0 : 1);
  } catch (error) {
    fail("visual_qa_unavailable", String(error?.message || error), { stderr_tail: stderr.slice(-1500) });
  } finally {
    child.kill("SIGTERM");
    try {
      rmSync(profileDir, { recursive: true, force: true });
    } catch {
      /* a browser that has not exited yet keeps a lock; the temp dir is disposable */
    }
  }
}

await main();
