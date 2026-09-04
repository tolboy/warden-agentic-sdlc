#!/usr/bin/env node
/**
 * Machine visual QA: start no servers. Drive a local Chromium/Edge via CDP
 * (Node 22 WebSocket, zero npm deps), capture screenshots, and assert what a
 * machine can actually settle. A pass without a screenshot is refused.
 *
 * Usage:
 *   node visual-qa.mjs --validate-only --scenario "1280x720: testid=save visible"
 *   node visual-qa.mjs --rank-a11y --tree ax.json --scenario "1280x720: testid=save visible"
 *   node visual-qa.mjs --url http://127.0.0.1:4173/ --out DIR \
 *     --scenario "700x400: text=Create visible" \
 *     --scenario "1280x720: css=.settings-panel visible" \
 *     --scenario "1280x720: testid=save click -> css=.saved visible" \
 *     --scenario "1280x720: css=canvas click@0.5,0.86 -> text=Placed visible" \
 *     --scenario "1280x720: testid=stage click -> wait 30 -> css=.fire visible" \
 *     --scenario "1280x720: testid=stage click -> wait-for=css=.hearth" \
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
 *   wait N     a step of its own: hold for N seconds, then photograph the page.
 *              Everything else here judges a page 1.2 s after a click, which is
 *              blind to any UI that runs on its own clock — an animation, a
 *              staged scene, a countdown. A wait cannot fail and cannot be a
 *              scenario's only step: it produces evidence, it does not assert.
 *   wait-for=MATCHER
 *              poll until that matcher is visible, up to 15 s, then photograph.
 *              Unlike wait N it can fail: the control never appeared. A typed
 *              matcher is required (css= / testid= / role= / text=). A trailing
 *              `visible` is allowed and dropped, being what a wait-for already
 *              means; `hidden` and `click` are refused rather than swallowed.
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
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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

/**
 * Answer whether these scenarios are sayable at all, and start no browser.
 *
 * The grammar lives here, so the check that a contract is writable has to live here too:
 * a second copy in Warden would be a second grammar, and the two would drift. Warden runs
 * this at preflight, before the first vendor is paid — a typo in a scenario used to be
 * discovered by the browser stage, which is after an implementer and an independent
 * reviewer have both been billed for work nobody could look at.
 */
const validateOnly = args.includes("--validate-only");
/**
 * Rank an accessibility tree with no browser, the same shape as `--validate-only`.
 * The snapshot is only observable with a live page, so ranking — which is what
 * decides whether the control the scenario named is in the 60 nodes a human sees —
 * has to be askable of the adapter on its own. `--tree` is the AX nodes (and any
 * probe boxes) to rank.
 */
const rankOnly = args.includes("--rank-a11y");
const offline = validateOnly || rankOnly;

const scenarios = opts("--scenario");

if (!offline) {
  if (!url || !outDir) {
    console.error("usage: visual-qa.mjs --url URL --out DIR --scenario 'WxH: text=Label visible'");
    process.exit(2);
  }
  if (scenarios.length === 0) {
    fail("visual_qa_unavailable", "no scenarios given");
  }
}

const browser = offline ? null : (browserBin || findBrowser());
if (!offline && !browser) fail("visual_qa_unavailable", "no Chrome/Edge executable found");

if (!offline) mkdirSync(outDir, { recursive: true });

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
 * `wait 30` / `wait 30s`: hold, then photograph. Bounded because a gate that can
 * sleep forever is a hang with a friendly name; 120 s is longer than any UI ought
 * to make a person wait for the thing it is about.
 */
const WAIT_STEP = /^wait\s*=?\s*(\d+(?:\.\d+)?)\s*s(?:ec(?:onds?)?)?$|^wait\s*=?\s*(\d+(?:\.\d+)?)$/i;
const WAIT_MAX_SECONDS = 120;
/** Poll until a matcher is visible. Not a sleep: this one can fail. */
const WAIT_FOR_STEP = /^wait-for\s*=?\s*(.+)$/i;
const WAIT_FOR_SECONDS = 15;
const NO_CONSOLE_ERRORS = /^no[-_ ]?console[-_ ]?errors$/i;
const NO_CONSOLE_ERRORS_ANYWHERE = /\bno[-_ ]?console[-_ ]?errors\b/i;

/**
 * The matchers that survive a rename, and so can carry a required contract.
 *
 * `text=` reads the copy on the control, which is the one part of a control that a later
 * task is free to change: a contract written that way goes red for a reason that has
 * nothing to do with the task under test, and an implementer is then paid to chase it.
 */
const STRONG_MATCHERS = new Set(["testid", "role", "css"]);
const locatorKind = (step) => (step.kind === "wait-for" ? step.matcherKind : step.kind);

/**
 * Splits "css=.panel > .row visible" into a matcher and an assertion. The
 * assertion is the LAST word, because a CSS selector may contain spaces and
 * a label almost always does.
 */
function parseStep(raw, fallbackAssertion = "visible") {
  const text = String(raw).trim();
  if (!text) return null;
  // `-> no-console-errors` is not a step, it is the scenario asking for a quiet
  // console; parseScenario reads it off the whole line. Without this it parsed as a
  // text matcher and the scenario failed looking for a page that says
  // "no-console-errors" — so the console could be watched on an idle page, or a chain
  // of clicks could be run, never both. That is backwards: a console is most worth
  // watching while something is actually happening.
  if (NO_CONSOLE_ERRORS.test(text)) return null;
  const held = text.match(WAIT_STEP);
  if (held) {
    const seconds = Number(held[1] ?? held[2]);
    if (!(seconds > 0 && seconds <= WAIT_MAX_SECONDS)) {
      throw new Error(`scenario "${text}": wait takes 0 to ${WAIT_MAX_SECONDS} seconds, got ${seconds}`);
    }
    return { kind: "wait", value: String(seconds), assertion: "wait", seconds, at: null, raw: text };
  }
  const until = text.match(WAIT_FOR_STEP);
  if (until) {
    let body = String(until[1] || "").trim();
    // Everywhere else in this grammar the last word is the assertion, so an operator writes
    // `wait-for=css=.hearth visible` without thinking about it — and this module's own usage
    // block documented exactly that. Left inside the value it becomes the selector
    // ".hearth visible", a descendant of a <visible> element, which nothing on any page
    // matches: the step then spends its whole poll failing to find a control that is right
    // there, and reports the control as missing. Read the word the same way here.
    const tail = body.split(/\s+/);
    const last = tail.length > 1 ? tail[tail.length - 1].toLowerCase() : "";
    if (ASSERTIONS.has(last)) {
      if (last !== "visible") {
        throw new Error(`scenario "${text}": wait-for polls until something appears, so it `
          + `cannot end in "${last}". Wait for the element, then say what to do with it in `
          + `its own step: "wait-for=css=.panel -> css=.panel ${last}".`);
      }
      tail.pop();
      body = tail.join(" ");
    }
    const typed = body.match(/^(text|css|testid|role)\s*=\s*(.+)$/i);
    if (!typed) {
      throw new Error(`scenario "${text}": wait-for needs a typed matcher `
        + `(wait-for=css=.ready, wait-for=testid=hearth), not "${body}"`);
    }
    return {
      kind: "wait-for",
      matcherKind: typed[1].toLowerCase(),
      value: typed[2].trim(),
      assertion: "wait-for",
      seconds: WAIT_FOR_SECONDS,
      at: null,
      raw: text,
    };
  }
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

  if (NO_CONSOLE_ERRORS.test(rest)) {
    return { raw, width, height, mobile, consoleOnly: true, steps: [] };
  }

  // Tolerate the older free-form wording: "Create visible", "Create click". It parses as a
  // `text=` matcher, so a required contract written this way is refused by contractProblem
  // for naming its control by copy — this only keeps an old scenario readable, not runnable.
  const arrow = rest.split(/\s*->\s*/);
  const steps = [];
  const first = parseStep(arrow[0]);
  if (first) steps.push(first);
  for (const extra of arrow.slice(1)) {
    const step = parseStep(extra);
    if (step) steps.push(step);
  }
  const noConsoleErrors = NO_CONSOLE_ERRORS_ANYWHERE.test(rest);
  return { raw, width, height, mobile, consoleOnly: false, steps, noConsoleErrors };
}

/** The URL the top of a stack came from, or "" when the browser did not say. */
function originOf(stackTrace) {
  const frame = stackTrace?.callFrames?.[0];
  return frame?.url || "";
}

/**
 * Whether an error belongs to something other than the page under test.
 *
 * A browser ships components that talk to each other over the extension message bus, and
 * when the other end is not listening they log to the page's console like anything else.
 * Measured: a run of a passing feature failed `no-console-errors` on
 * "Could not establish connection. Receiving end does not exist." and spent two fix rounds
 * on it — an error no change to the application could ever remove. The launch flags below
 * are the real fix; this is what keeps one that slipped through from being reported as the
 * application's. Nothing is dropped silently: what was ignored, and where it came from,
 * goes into the report.
 */
const FOREIGN_ORIGINS = ["chrome-extension://", "moz-extension://", "edge://", "chrome://",
  "devtools://"];
const FOREIGN_TEXT = [
  "could not establish connection. receiving end does not exist",
  "the message port closed before a response was received",
  "extension context invalidated",
];
function isForeign(origin, text) {
  const where = String(origin || "").toLowerCase();
  if (FOREIGN_ORIGINS.some((prefix) => where.startsWith(prefix))) return true;
  const body = String(text || "").toLowerCase();
  return FOREIGN_TEXT.some((phrase) => body.includes(phrase));
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
    /** Errors that were not the page's, kept rather than dropped silently. */
    this.foreignErrors = [];
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
      if (text) {
        const where = originOf(message.params.stackTrace);
        if (isForeign(where, text)) this.foreignErrors.push({ text: text.slice(0, 400), from: where });
        else this.consoleErrors.push(text.slice(0, 400));
      }
    }
    if (message.method === "Runtime.exceptionThrown") {
      const details = message.params?.exceptionDetails;
      const text = details?.exception?.description || details?.text || "";
      if (text) {
        const where = details?.url || originOf(details?.stackTrace);
        if (isForeign(where, text)) {
          this.foreignErrors.push({ text: String(text).slice(0, 400), from: where });
        } else {
          this.pageErrors.push(String(text).slice(0, 400));
        }
      }
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

/**
 * Hold, then photograph.
 *
 * A wait never fails: there is nothing here for a page to get wrong. What it
 * produces is a screenshot taken at a moment the scenario names, so a step
 * after it can assert on a state the page reached by itself, and the role with
 * eyes is shown the frame the chapter is actually about rather than the one
 * 1.2 s after a click.
 */
async function runWait(cdp, step, outDir, label) {
  const before = await probe(cdp, { kind: "css", value: "html" });
  await sleep(Math.round(step.seconds * 1000));
  const info = await probe(cdp, { kind: "css", value: "html" });
  return {
    result: {
      matcher: `wait ${step.seconds}s`,
      assertion: "wait",
      waited_seconds: step.seconds,
      // Information, not a verdict: a page that is meant to be still after the
      // wait is not a failure, and a wait that asserted would be a hidden check.
      dom_changed: info.digest !== before.digest,
      screenshot_after: await shoot(cdp, outDir, `${label}-after-wait-${step.index}`),
      ok: true,
    },
    info,
  };
}

async function runWaitFor(cdp, step, outDir, label) {
  const matcher = { kind: step.matcherKind, value: step.value };
  const deadline = Date.now() + Math.round(step.seconds * 1000);
  let info = await probe(cdp, matcher);
  while (!isVisible(info.found) && Date.now() < deadline) {
    await sleep(200);
    info = await probe(cdp, matcher);
  }
  const visible = isVisible(info.found);
  return {
    result: {
      matcher: `wait-for ${step.matcherKind}=${step.value}`,
      assertion: "wait-for",
      waited_seconds: step.seconds,
      visible,
      element: info.found,
      screenshot_after: await shoot(cdp, outDir, `${label}-after-wait-for-${step.index}`),
      ok: visible,
      why: visible ? null : describeMiss(
        { kind: step.matcherKind, value: step.value }, info)
        + ` after waiting ${step.seconds}s`,
    },
    info,
  };
}

/**
 * The snapshot stays bounded: the whole of it is written into the context the
 * visual_qa role is given. Sixty nodes is enough for the control, its neighbours
 * and a sample of the rest; two hundred of chrome is not.
 *
 * The cap is applied after ranking, not in document order. Tree order on a real
 * page is the skip link, the banner and the navigation — never the button the
 * scenario named.
 */
const A11Y_CAP = 60;
const INTERACTIVE_ROLES = new Set(["button", "link", "textbox", "checkbox", "radio",
  "combobox", "menuitem", "tab", "switch", "slider"]);

function axString(value) {
  if (value == null) return "";
  if (typeof value === "string") return value;
  if (typeof value === "object" && value.value != null) return String(value.value);
  return "";
}

function axFlag(node, name) {
  if (node == null) return false;
  if (node[name] === true) return true;
  const props = node.properties;
  if (!Array.isArray(props)) return false;
  const wanted = String(name).toLowerCase();
  for (const prop of props) {
    if (!prop || String(prop.name || "").toLowerCase() !== wanted) continue;
    const v = prop.value;
    if (v === true) return true;
    if (v && typeof v === "object" && (v.value === true || v.value === "true")) return true;
  }
  return false;
}

function emptyBox() {
  return { x: 0, y: 0, width: 0, height: 0 };
}

function normalizeBox(raw) {
  if (!raw || typeof raw !== "object") return emptyBox();
  const x = Number(raw.x);
  const y = Number(raw.y);
  const width = Number(raw.width);
  const height = Number(raw.height);
  return {
    x: Number.isFinite(x) ? x : 0,
    y: Number.isFinite(y) ? y : 0,
    width: Number.isFinite(width) ? width : 0,
    height: Number.isFinite(height) ? height : 0,
  };
}

function boxFromQuad(quad) {
  if (!Array.isArray(quad) || quad.length < 8) return emptyBox();
  const xs = [Number(quad[0]), Number(quad[2]), Number(quad[4]), Number(quad[6])];
  const ys = [Number(quad[1]), Number(quad[3]), Number(quad[5]), Number(quad[7])];
  if (xs.some((n) => !Number.isFinite(n)) || ys.some((n) => !Number.isFinite(n))) return emptyBox();
  const x = Math.min(...xs);
  const y = Math.min(...ys);
  return { x, y, width: Math.max(...xs) - x, height: Math.max(...ys) - y };
}

function boxesOverlap(a, b) {
  if (!a || !b) return false;
  if (!(a.width > 0 && a.height > 0 && b.width > 0 && b.height > 0)) return false;
  return a.x < b.x + b.width && a.x + a.width > b.x
    && a.y < b.y + b.height && a.y + a.height > b.y;
}

function normalizeAxNode(node, index) {
  return {
    role: axString(node?.role).trim(),
    name: axString(node?.name).trim().slice(0, 80),
    focused: axFlag(node, "focused"),
    ignored: Boolean(node?.ignored),
    box: normalizeBox(node?.box),
    index,
  };
}

function publishAxNode(record) {
  return {
    role: record.role,
    name: record.name,
    focused: record.focused,
    ignored: record.ignored,
    box: record.box,
  };
}

/**
 * What the scenario is about: the matchers it named, and the box `probe` already
 * measured for each step that hit. Ranking uses these so a control two hundred
 * nodes down the AX tree still lands in the snapshot, and lands first.
 */
function targetsFrom(steps, probes) {
  const targets = [];
  for (const step of steps || []) {
    if (!step || step.kind === "wait") continue;
    const kind = step.kind === "wait-for" ? step.matcherKind : step.kind;
    const value = step.value;
    if (!kind || value == null || String(value).trim() === "") continue;
    targets.push({
      kind,
      value: String(value),
      text: kind === "text" ? String(value) : "",
      box: null,
    });
  }
  for (const probe of probes || []) {
    if (!probe) continue;
    const kind = probe.kind || probe.matcherKind;
    if (!kind || kind === "wait") continue;
    const found = probe.found && typeof probe.found === "object" ? probe.found : null;
    const box = found ? normalizeBox(found) : normalizeBox(probe.box);
    const text = found && found.text != null ? String(found.text).trim()
      : (probe.text != null ? String(probe.text).trim() : "");
    targets.push({
      kind,
      value: probe.value != null ? String(probe.value) : "",
      text,
      box: box.width > 0 && box.height > 0 ? box : null,
    });
  }
  return targets;
}

function nodeHitsTarget(record, target) {
  const hit = { box: false, name: false, role: false };
  if (!target) return hit;
  if (target.box && boxesOverlap(record.box, target.box)) hit.box = true;
  if (target.kind === "role"
      && record.role.toLowerCase() === String(target.value || "").toLowerCase()) {
    hit.role = true;
  }
  const needle = String(target.text || "").trim().toLowerCase();
  if (needle && record.name.toLowerCase().includes(needle)) hit.name = true;
  return hit;
}

/**
 * Rank, then cap. Pure: `--rank-a11y` calls this with a fixture tree and no CDP.
 *
 * Order: nodes the scenario named (box, name, or role), then interactive nodes
 * whether or not they have a name, then whatever else is named. Ignored nodes
 * stay only when they are the thing the scenario named — an aria-hidden control
 * is a finding, a hidden skip link is not. An unnamed non-interactive node is
 * dropped. Every kept node carries a box, even if it is empty.
 */
function rankA11yNodes(nodes, targets, cap = A11Y_CAP) {
  if (!Array.isArray(nodes)) return [];
  const scored = [];
  for (let i = 0; i < nodes.length; i++) {
    const record = normalizeAxNode(nodes[i], i);
    if (!record.role) continue;
    let hitsBox = false;
    let hitsName = false;
    let hitsRole = false;
    for (const target of targets || []) {
      const hit = nodeHitsTarget(record, target);
      hitsBox = hitsBox || hit.box;
      hitsName = hitsName || hit.name;
      hitsRole = hitsRole || hit.role;
    }
    const targeted = hitsBox || hitsName || hitsRole;
    if (record.ignored && !targeted) continue;
    const interactive = INTERACTIVE_ROLES.has(record.role.toLowerCase());
    if (!targeted && !interactive && !record.name) continue;
    let rank;
    if (hitsBox) rank = 0;
    else if (hitsName || hitsRole) rank = 1;
    else if (interactive) rank = 2;
    else rank = 3;
    scored.push({ record, rank, index: i });
  }
  scored.sort((a, b) => a.rank - b.rank || a.index - b.index);
  return scored.slice(0, cap).map((row) => publishAxNode(row.record));
}

async function attachBoxes(cdp, nodes) {
  const out = new Array(nodes.length);
  const batch = 25;
  for (let start = 0; start < nodes.length; start += batch) {
    const slice = nodes.slice(start, start + batch);
    const boxes = await Promise.all(slice.map(async (node) => {
      if (node?.box && typeof node.box === "object") return normalizeBox(node.box);
      const id = node?.backendDOMNodeId;
      if (id == null || id === 0) return emptyBox();
      try {
        const result = await cdp.send("DOM.getContentQuads", { backendNodeId: id });
        return boxFromQuad(result?.quads?.[0]);
      } catch {
        return emptyBox();
      }
    }));
    for (let i = 0; i < slice.length; i++) {
      const node = slice[i] || {};
      out[start + i] = {
        role: node.role,
        name: node.name,
        focused: node.focused,
        ignored: node.ignored,
        properties: node.properties,
        box: boxes[i],
      };
    }
  }
  return out;
}

async function a11ySnapshot(cdp, steps, probes) {
  await cdp.send("Accessibility.enable").catch(() => {});
  await cdp.send("DOM.enable").catch(() => {});
  await cdp.send("DOM.getDocument", { depth: 0 }).catch(() => {});
  const tree = await cdp.send("Accessibility.getFullAXTree").catch(() => null);
  const nodes = tree?.nodes;
  if (!Array.isArray(nodes)) return [];
  const withBoxes = await attachBoxes(cdp, nodes);
  return rankA11yNodes(withBoxes, targetsFrom(steps, probes));
}

function rankA11yOffline() {
  const treePath = opt("--tree");
  if (!treePath) {
    console.log(JSON.stringify({
      ok: false,
      code: "a11y_rank_invalid",
      message: "--rank-a11y needs --tree FILE (the AX nodes to rank, no browser)",
    }));
    process.exit(2);
  }
  let raw;
  try {
    raw = JSON.parse(readFileSync(treePath, "utf8"));
  } catch (error) {
    console.log(JSON.stringify({
      ok: false,
      code: "a11y_rank_invalid",
      message: String(error?.message || error),
    }));
    process.exit(2);
  }
  const loaded = Array.isArray(raw)
    ? { nodes: raw, probes: [] }
    : {
      nodes: Array.isArray(raw?.nodes) ? raw.nodes : [],
      probes: Array.isArray(raw?.probes) ? raw.probes : [],
    };
  let steps = [];
  try {
    steps = scenarios.map(parseScenario).flatMap((s) => s.steps || []);
  } catch (error) {
    console.log(JSON.stringify({
      ok: false,
      code: "a11y_rank_invalid",
      message: String(error?.message || error),
    }));
    process.exit(2);
  }
  const a11y = rankA11yNodes(loaded.nodes, targetsFrom(steps, loaded.probes));
  console.log(JSON.stringify({ ok: true, code: "a11y_ranked", a11y, cap: A11Y_CAP }));
  process.exit(0);
}

async function runStep(cdp, step, outDir, label) {
  if (step.kind === "wait") return await runWait(cdp, step, outDir, label);
  if (step.kind === "wait-for") return await runWaitFor(cdp, step, outDir, label);
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
  // Per step, not per scenario. A chain of clicks used to write every one of them to the
  // same `-after-click` file, so only the last survived — and the model with eyes was then
  // shown images that did not contain what it was asked to judge. It said so, twice, and the
  // run went green anyway. A screenshot that silently replaces another is worse than none.
  result.screenshot_after = await shoot(cdp, outDir, `${label}-after-click-${step.index}`);
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
  cdp.foreignErrors = [];
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
  const probes = [];
  let page = null;
  if (viewportHonoured) {
    for (const [position, step] of scenario.steps.entries()) {
      const { result, info } = await runStep(cdp, { ...step, index: position + 1 }, outDir, label);
      page = page || info;
      steps.push(result);
      if (info && step.kind !== "wait") {
        const kind = step.kind === "wait-for" ? step.matcherKind : step.kind;
        probes.push({ kind, value: step.value, found: info.found || null });
      }
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
    ignored_console_errors: cdp.foreignErrors,
    console_checked: requireQuietConsole,
    a11y: await a11ySnapshot(cdp, scenario.steps, probes),
    page: page ? { title: page.title, innerWidth: page.innerWidth, innerHeight: page.innerHeight, orientation: page.orientation } : null,
    why,
  };
}

/**
 * What is wrong with these scenarios, or null when they are sayable.
 *
 * Pure, and deliberately separate from running them: this is the whole of what preflight
 * can know without a browser, and it is exactly what `--validate-only` returns.
 */
function contractProblem(list) {
  if (list.length === 0) return "no scenarios given";
  let parsed;
  try {
    parsed = list.map(parseScenario);
  } catch (badGrammar) {
    // A scenario the grammar refuses used to throw out of main() before the report
    // existed, and Warden then reported "adapter wrote no visual-qa.json (is node and
    // Edge/Chrome installed?)" — sending an operator to check their browser over a typo.
    return String(badGrammar?.message || badGrammar);
  }
  // `no-console-errors` is an assertion wherever it appears, not only when it is the whole
  // scenario. Both guards below used to test `consoleOnly`, which parseScenario sets only for
  // a scenario that is *nothing but* the console check — so `wait 8 -> no-console-errors` was
  // refused while the bare `no-console-errors` was accepted, and adding a pause turned a valid
  // scenario invalid. It was never a grammar problem: `requireQuietConsole` below already
  // honours `noConsoleErrors`, so the runtime would have judged it correctly all along.
  // Measured on run torch-1, which paid for a green implementer and a green independent review
  // and then threw both away over this.
  const asserts = (s) => s.consoleOnly || s.noConsoleErrors;
  const empty = parsed.find((s) => !asserts(s) && s.steps.length === 0);
  if (empty) {
    return `scenario "${empty.raw}" states no assertion. Use "WxH: text=Label visible", `
      + `"WxH: css=SELECTOR visible", "WxH: testid=ID click" or "WxH: no-console-errors".`;
  }
  const onlyWaiting = parsed.find((s) => !asserts(s) && s.steps.length > 0
    && s.steps.every((step) => step.kind === "wait"));
  if (onlyWaiting) {
    return `scenario "${onlyWaiting.raw}" only waits. A wait produces a screenshot; it does not `
      + `assert anything, so a scenario made of waits passes without testing the page. `
      + `Say what should be true once the wait is over: "... -> css=SELECTOR visible".`;
  }
  // Asked per scenario, not per contract. A contract whose first line carries a `testid=`
  // says nothing about the fragility of its second, and the interesting case is exactly the
  // mixed one: the strong scenarios make the file look rigorous while the weak neighbour is
  // the one that will go red after somebody renames a label.
  const byCopy = parsed.find((s) => s.steps.some((step) => step.kind !== "wait")
    && !s.steps.some((step) => STRONG_MATCHERS.has(locatorKind(step))));
  if (byCopy) {
    return `scenario "${byCopy.raw}" names what it looks at only by the copy on it. `
      + `The words on a control are the part of it a later task is free to change, so this `
      + `check will go red for a reason that has nothing to do with the task. Put `
      + `data-testid on the control and say "testid=save-button", or name it with "role=" `
      + `or "css=". A scenario that is only "no-console-errors" needs no locator, and a `
      + `"text=" step is fine alongside one that is anchored.`;
  }
  return null;
}

async function main() {
  const problem = contractProblem(scenarios);
  if (problem) fail("visual_qa_unavailable", problem);
  const parsed = scenarios.map(parseScenario);

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
      // A browser's own bundled components log to the page's console when their message
      // bus has no listener, and `no-console-errors` then fails a page that did nothing
      // wrong. Turning them off is the fix; isForeign() is the net under it.
      "--disable-extensions",
      "--disable-component-extensions-with-background-pages",
      "--disable-background-networking",
      "--disable-sync",
      "--disable-default-apps",
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
    await cdp.send("Accessibility.enable").catch(() => {});
    await cdp.send("DOM.enable").catch(() => {});
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

if (validateOnly) {
  // No browser, no output directory, no page. A distinct code, because "your contract does
  // not say anything" and "this machine has no browser" send an operator to different places.
  const problem = contractProblem(scenarios);
  console.log(JSON.stringify(problem
    ? { ok: false, code: "visual_qa_contract_invalid", message: problem }
    : { ok: true, code: "scenarios_valid", scenarios: scenarios.length }));
  process.exit(problem ? 2 : 0);
}

if (rankOnly) rankA11yOffline();

await main();
