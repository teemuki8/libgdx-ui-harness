#!/usr/bin/env node

import { createRequire } from "node:module";
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REFERENCE_ROOT_VARIABLE = "PALISADE_REFERENCE_ROOT";
const referenceRootValue = process.env[REFERENCE_ROOT_VARIABLE];
if (!referenceRootValue) {
  throw new Error(`${REFERENCE_ROOT_VARIABLE} must name the live Palisade checkout`);
}

const referenceRoot = resolve(referenceRootValue);
const manifestPath = join(referenceRoot, "package.json");
const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
const fullScript = ["dev:full", "full:dev", "start:full"].find((name) => manifest.scripts?.[name]);
if (!fullScript) {
  throw new Error("The reference checkout does not expose an approved full-edition development script");
}

const requireFromReference = createRequire(manifestPath);
const { chromium } = requireFromReference("playwright");
const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const outputDirectory = join(scriptDirectory, "..", "corpus", "reference");
const host = "127.0.0.1";
const port = 41730;
const origin = `http://${host}:${port}`;
const deterministicSeed = "305419896";

function startReferenceServer() {
  const child = spawn(
    "npm",
    ["run", fullScript, "--", "--host", host, "--port", String(port), "--strictPort"],
    {
      cwd: referenceRoot,
      env: { ...process.env, BROWSER: "none" },
      stdio: ["ignore", "pipe", "pipe"],
      detached: process.platform !== "win32",
    },
  );
  let diagnostics = "";
  for (const stream of [child.stdout, child.stderr]) {
    stream.setEncoding("utf8");
    stream.on("data", (chunk) => {
      diagnostics = (diagnostics + chunk).slice(-8_192);
    });
  }
  return { child, diagnostics: () => diagnostics };
}

async function waitForServer(child, diagnostics) {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`Reference server exited before readiness\n${diagnostics()}`);
    }
    try {
      const response = await fetch(origin);
      if (response.ok) return;
    } catch {
      // The server is still starting.
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 200));
  }
  throw new Error(`Timed out waiting for the reference server\n${diagnostics()}`);
}

async function stopReferenceServer(child) {
  if (child.exitCode !== null) return;
  if (process.platform === "win32") child.kill("SIGTERM");
  else process.kill(-child.pid, "SIGTERM");
  await Promise.race([
    new Promise((resolveExit) => child.once("exit", resolveExit)),
    new Promise((resolveDelay) => setTimeout(resolveDelay, 5_000)),
  ]);
  if (child.exitCode === null) child.kill("SIGKILL");
}

async function openSkirmishConfiguration(page) {
  await page.goto(origin, { waitUntil: "networkidle" });
  const configurationHeading = page.getByRole("heading", { name: "Skirmish Configuration" });
  if (!(await configurationHeading.isVisible().catch(() => false))) {
    const skirmishButton = page.getByRole("button", { name: /skirmish/i }).first();
    await skirmishButton.click();
  }
  await configurationHeading.waitFor({ state: "visible", timeout: 15_000 });
}

async function scrollConfiguration(page, position) {
  const configurationHeading = page.getByRole("heading", { name: "Skirmish Configuration" });
  await configurationHeading.evaluate((heading, requestedPosition) => {
    let surface = heading.parentElement;
    while (surface && surface !== document.body && surface.scrollHeight <= surface.clientHeight) {
      surface = surface.parentElement;
    }
    if (!surface || surface === document.body) {
      throw new Error("Skirmish Configuration has no bounded scroll surface");
    }
    surface.scrollTop = requestedPosition === "bottom" ? surface.scrollHeight : 0;
  }, position);
}

function stripPngMetadata(png) {
  if (!png.subarray(0, 8).equals(Buffer.from("89504e470d0a1a0a", "hex"))) {
    throw new Error("Playwright returned a non-PNG screenshot");
  }
  const chunks = [png.subarray(0, 8)];
  let offset = 8;
  while (offset < png.length) {
    const length = png.readUInt32BE(offset);
    const end = offset + 12 + length;
    if (end > png.length) throw new Error("Playwright returned a truncated PNG screenshot");
    const type = png.toString("ascii", offset + 4, offset + 8);
    if (["IHDR", "PLTE", "IDAT", "IEND"].includes(type)) chunks.push(png.subarray(offset, end));
    offset = end;
    if (type === "IEND") break;
  }
  return Buffer.concat(chunks);
}

async function capture(page, filename, width, height, prepare = async () => {}) {
  await page.setViewportSize({ width, height });
  await prepare();
  await page.evaluate(() => new Promise((resolveFrame) => requestAnimationFrame(() => requestAnimationFrame(resolveFrame))));
  const raw = await page.screenshot({ animations: "disabled", caret: "hide", fullPage: false, type: "png" });
  const png = stripPngMetadata(raw);
  const outputPath = join(outputDirectory, filename);
  await writeFile(outputPath, png);
  return {
    file: basename(outputPath),
    bytes: png.length,
    sha256: createHash("sha256").update(png).digest("hex"),
  };
}

const server = startReferenceServer();
let browser;
try {
  await waitForServer(server.child, server.diagnostics);
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  await openSkirmishConfiguration(page);
  await mkdir(outputDirectory, { recursive: true });


  await page.getByLabel("Seed", { exact: true }).fill(deterministicSeed);
  const results = [];
  results.push(await capture(page, "initial-1920x1080.png", 1920, 1080, async () => scrollConfiguration(page, "top")));
  results.push(await capture(page, "bottom-1920x1080.png", 1920, 1080, async () => scrollConfiguration(page, "bottom")));
  results.push(await capture(page, "initial-1280x720.png", 1280, 720, async () => scrollConfiguration(page, "top")));
  for (const result of results) console.log(`${result.file} ${result.bytes} ${result.sha256}`);
} finally {
  if (browser) await browser.close();
  await stopReferenceServer(server.child);
}
