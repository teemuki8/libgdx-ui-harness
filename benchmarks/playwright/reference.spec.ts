import { chromium, type AriaRole, type Browser, type Locator, type Page } from '@playwright/test';
import { createHash } from 'node:crypto';
import { mkdir, open, readFile, rename, stat } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import {
  matchesExpectedFailure,
  type FailureExpectation,
} from './failure-contract.ts';

interface PortableLocator { kind: string; value: string; name?: string; exact?: boolean }
interface Step {
  action: string;
  locator?: PortableLocator;
  value?: string;
  amountY?: number;
  expectedFailure?: FailureExpectation;
}
interface Scenario {
  id: string;
  description: string;
  logicalDelayMillis: number;
  steps: Step[];
  expected: string;
}
interface Corpus { schemaVersion: number; scenarios: Scenario[] }

interface RunRecord {
  schemaVersion: number;
  system: 'playwright';
  scenarioId: string;
  run: number;
  completed: boolean;
  timeout: boolean;
  flakyFailure: boolean;
  timeoutOrFlaky: boolean;
  toolCalls: number;
  actionableEvidence: boolean;
  durationMillis: number;
  traceBytes: number;
  screenshotBytes: number;
  repeatabilityKey: string;
  diagnostics: string[];
  error: string | null;
}

const options = parseArguments(process.argv.slice(2));
const corpus = JSON.parse(await readFile(options.corpus, 'utf8')) as Corpus;
validateCorpus(corpus);
await mkdir(options.rawDir, { recursive: true });
await mkdir(options.traceDir, { recursive: true });
await mkdir(options.evidenceDir, { recursive: true });

let browser: Browser | undefined;
try {
  browser = await chromium.launch({ headless: false });
  for (const scenario of corpus.scenarios) {
    for (let run = 1; run <= options.runs; run++) {
      const record = await execute(browser, scenario, run);
      const target = join(options.rawDir, `${scenario.id}-${String(run).padStart(2, '0')}.json`);
      await writeAtomically(target, JSON.stringify(record, null, 2) + '\n');
      process.stdout.write(`PLAYWRIGHT_RECORD ${scenario.id} ${run} ${record.completed}\n`);
    }
  }
} finally {
  await browser?.close();
}

async function execute(browser: Browser, scenario: Scenario, run: number): Promise<RunRecord> {
  const started = process.hrtime.bigint();
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  const tracePath = join(options.traceDir, `${scenario.id}-${String(run).padStart(2, '0')}.zip`);
  const diagnostics: string[] = [];
  let completed = false;
  let timeout = false;
  let toolCalls = 0;
  let screenshotBytes = 0;
  let traceBytes = 0;
  let evidence = '';
  let error: string | null = null;

  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  try {
    await page.setContent(referencePage(scenario.logicalDelayMillis));
    for (const step of scenario.steps) {
      toolCalls++;
      const result = await executeStep(page, step, scenario, run);
      if (result.diagnostic) diagnostics.push(result.diagnostic);
      if (result.screenshotBytes > 0) screenshotBytes += result.screenshotBytes;
    }
    toolCalls++;
    evidence = await verifyExpected(page, scenario.expected, diagnostics, screenshotBytes);
    completed = true;
  } catch (failure) {
    error = diagnostic(failure);
    diagnostics.push(error);
    timeout = /Timeout/i.test(error);
    try {
      const png = await page.screenshot({ path: join(options.evidenceDir,
        `${scenario.id}-${String(run).padStart(2, '0')}-failure.png`) });
      screenshotBytes += png.byteLength;
    } catch (screenshotFailure) {
      diagnostics.push(`failure screenshot: ${diagnostic(screenshotFailure)}`);
    }
  } finally {
    try {
      await context.tracing.stop({ path: tracePath });
      traceBytes = (await stat(tracePath)).size;
    } catch (traceFailure) {
      diagnostics.push(`trace stop: ${diagnostic(traceFailure)}`);
      if (completed) {
        completed = false;
        error = diagnostics.at(-1) ?? 'trace stop failed';
      }
    }
    await context.close();
  }

  const actionableEvidence = completed && traceBytes > 0 && evidence.length > 0
    && (scenario.id !== 'intentional-failure-trace'
      || (diagnostics.length > 0 && screenshotBytes > 0));
  const durationMillis = Number(process.hrtime.bigint() - started) / 1_000_000;
  const repeatabilityKey = hash(JSON.stringify({
    scenario: scenario.id,
    completed,
    timeout,
    actionableEvidence,
    evidence,
    diagnosticKinds: diagnostics.map(normalizeDiagnostic),
    screenshotPresent: screenshotBytes > 0,
    tracePresent: traceBytes > 0,
  }));
  return {
    schemaVersion: 1,
    system: 'playwright',
    scenarioId: scenario.id,
    run,
    completed,
    timeout,
    flakyFailure: !completed && !timeout,
    timeoutOrFlaky: !completed,
    toolCalls,
    actionableEvidence,
    durationMillis,
    traceBytes,
    screenshotBytes,
    repeatabilityKey,
    diagnostics,
    error,
  };
}

async function executeStep(
  page: Page, step: Step, scenario: Scenario, run: number,
): Promise<{ diagnostic?: string; screenshotBytes: number }> {
  if (step.action === 'screenshot') {
    const png = await page.screenshot({ path: join(options.evidenceDir,
      `${scenario.id}-${String(run).padStart(2, '0')}.png`) });
    return { screenshotBytes: png.byteLength };
  }
  const target = locate(page, required(step.locator, `${step.action} locator`));
  switch (step.action) {
    case 'fill':
      await target.fill(required(step.value, 'fill value'), { timeout: 500 });
      break;
    case 'click':
      await target.click({ timeout: 500 });
      break;
    case 'wait-visible':
      await target.waitFor({ state: 'visible', timeout: 500 });
      break;
    case 'scroll':
      await target.hover({ timeout: 500 });
      await page.mouse.wheel(0, required(step.amountY, 'scroll amount') * 100);
      break;
    case 'expect-click-failure': {
      try {
        await target.click({ timeout: 500 });
      } catch (failure) {
        const expected = required(step.expectedFailure, 'expected failure contract');
        if (!matchesExpectedFailure(failure, expected)) {
          throw new Error(`Expected ${expected.category}; received ${diagnostic(failure)}`);
        }
        return {
          diagnostic: `expected ${expected.category}: ${diagnostic(failure)}`,
          screenshotBytes: 0,
        };
      }
      throw new Error('Expected strict click failure but click succeeded');
    }
    default:
      throw new Error(`Unsupported action ${step.action}`);
  }
  return { screenshotBytes: 0 };
}

async function verifyExpected(
  page: Page, expected: string, diagnostics: string[], screenshotBytes: number,
): Promise<string> {
  const separator = expected.indexOf(':');
  const kind = expected.slice(0, separator);
  const value = expected.slice(separator + 1);
  if (kind === 'text') {
    const text = (await page.getByText(value, { exact: true }).textContent({ timeout: 500 }))?.trim();
    if (text !== value) throw new Error(`Expected text ${value}, received ${text}`);
    return `text:${text}`;
  }
  if (kind === 'screenshot') {
    if (value !== '1280x720' || screenshotBytes <= 0) {
      throw new Error(`Expected a 1280x720 screenshot, bytes=${screenshotBytes}`);
    }
    return `screenshot:${value}`;
  }
  if (kind === 'failure') {
    if (diagnostics.length === 0 || screenshotBytes <= 0) {
      throw new Error('Intentional failure omitted its diagnostic or screenshot');
    }
    return `failure:${value}`;
  }
  throw new Error(`Unsupported expected outcome ${expected}`);
}

function locate(page: Page, locator: PortableLocator): Locator {
  const exact = locator.exact ?? true;
  switch (locator.kind) {
    case 'label': return page.getByLabel(locator.value, { exact });
    case 'text': return page.getByText(locator.value, { exact });
    case 'test-id': return page.getByTestId(locator.value);
    case 'role': return page.getByRole(locator.value as AriaRole,
      { name: required(locator.name, 'role name'), exact });
    default: throw new Error(`Unsupported locator kind ${locator.kind}`);
  }
}

function referencePage(delay: number): string {
  return `<!doctype html>
<html><head><meta charset="utf-8"><style>
body{font-family:sans-serif;margin:0;width:1280px;height:720px;background:#172033;color:#f4f7ff}
.panel{position:absolute;background:#26324a;padding:24px;box-sizing:border-box}
#signin{left:64px;top:120px;width:500px;height:300px} label,input{display:block;margin:8px 0}
#settings{left:660px;top:120px;width:540px;height:436px}.bench{position:absolute;left:540px;top:590px;width:650px;height:110px}
#moving-target{position:relative}.target-wrap{position:relative;display:inline-block}.cover{display:none;position:absolute;inset:0;background:#7d70b8;z-index:2}
#selection-scroll{height:80px;width:220px;overflow:auto;display:inline-block}.selection-content{height:480px}.selection-content button{margin-top:400px}
dialog{width:520px}button{margin:4px;padding:8px 14px}
  </style></head><body>
<section id="signin" class="panel"><h1>Sign in</h1>
<label>Username<input aria-label="Username" data-testid="username"></label>
<label>Password<input aria-label="Password" data-testid="password" type="password"></label>
<button data-testid="sign-in" id="sign-in-button">Sign in</button></section>
<section id="settings" class="panel"><h2>Settings</h2><div data-testid="settings-scroll" style="height:244px;overflow:auto"><div style="height:600px">Alpha<br>Beta<br>Gamma<br>Delta<br>Epsilon<br>Zeta<br>Eta<br>Theta<br>Iota<br>Kappa<br>Lambda<br>Mu</div></div><button id="open-dialog" data-testid="open-dialog">Open dialog</button></section>
<section class="bench">
<button data-testid="delay-start" id="delay-start">Start delay</button><button data-testid="delayed-target" id="delayed-target">Delayed target</button>
<button data-testid="movement-start" id="movement-start">Start movement</button><button data-testid="moving-target" id="moving-target">Moving target</button>
<button data-testid="obscure-start" id="obscure-start">Start cover</button><span class="target-wrap"><button data-testid="obscured-target" id="obscured-target">Obscured target</button><span id="cover" class="cover"></span></span>
<div id="selection-scroll" data-testid="selection-scroll"><div class="selection-content"><button id="select-lambda">Select Lambda</button></div></div>
<div id="status" aria-live="polite"></div></section>
<dialog id="dialog"><p>All interactions arrived through MCP.</p><button>Close</button></dialog>
<script>
const delay=${delay}; const status=document.querySelector('#status');
document.querySelector('#sign-in-button').onclick=()=>{const name=document.querySelector('[aria-label="Username"]').value.trim()||'guest';document.querySelector('#signin').innerHTML='<p data-testid="welcome-message">Welcome, '+name+'</p>'};
document.querySelector('#delay-start').onclick=()=>{const target=document.querySelector('#delayed-target');target.disabled=true;setTimeout(()=>target.disabled=false,delay)};
document.querySelector('#delayed-target').onclick=()=>status.textContent='Delayed ready';
document.querySelector('#movement-start').onclick=()=>document.querySelector('#moving-target').animate([{transform:'translateX(0)'},{transform:'translateX(120px)'}],{duration:delay});
document.querySelector('#moving-target').onclick=()=>status.textContent='Moving clicked';
document.querySelector('#obscure-start').onclick=()=>{const cover=document.querySelector('#cover');cover.style.display='block';setTimeout(()=>cover.style.display='none',delay)};
document.querySelector('#obscured-target').onclick=()=>status.textContent='Obscured clicked';
document.querySelector('#select-lambda').onclick=()=>status.textContent='Selected Lambda';
document.querySelector('#open-dialog').onclick=()=>document.querySelector('#dialog').showModal();
</script></body></html>`;
}

function validateCorpus(value: Corpus): void {
  if (value.schemaVersion !== 1 || !Array.isArray(value.scenarios) || value.scenarios.length !== 10) {
    throw new Error('Expected schemaVersion 1 and exactly ten scenarios');
  }
  const ids = new Set(value.scenarios.map(scenario => scenario.id));
  if (ids.size !== value.scenarios.length) throw new Error('Duplicate scenario id');
  for (const scenario of value.scenarios) {
    for (const step of scenario.steps) {
      if ((step.action === 'expect-click-failure') !== Boolean(step.expectedFailure)) {
        throw new Error('Expected-failure steps require an explicit failure contract');
      }
    }
  }
}

function parseArguments(args: string[]) {
  const values = new Map<string, string>();
  for (let index = 0; index < args.length; index += 2) {
    if (!args[index]?.startsWith('--') || args[index + 1] === undefined) {
      throw new Error(`Malformed argument at ${args[index] ?? '<end>'}`);
    }
    values.set(args[index].slice(2), args[index + 1]);
  }
  const runs = Number(required(values.get('runs'), '--runs'));
  if (!Number.isInteger(runs) || runs <= 0) throw new Error('--runs must be positive');
  return {
    runs,
    corpus: resolve(required(values.get('corpus'), '--corpus')),
    rawDir: resolve(required(values.get('raw-dir'), '--raw-dir')),
    traceDir: resolve(required(values.get('trace-dir'), '--trace-dir')),
    evidenceDir: resolve(required(values.get('evidence-dir'), '--evidence-dir')),
  };
}

async function writeAtomically(target: string, content: string): Promise<void> {
  await mkdir(dirname(target), { recursive: true });
  const temporary = join(dirname(target), `.${basename(target)}.${process.pid}.tmp`);
  const handle = await open(temporary, 'wx');
  try {
    await handle.writeFile(content, 'utf8');
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(temporary, target);
}

function diagnostic(failure: unknown): string {
  return failure instanceof Error ? (failure.stack ?? failure.message) : String(failure);
}
function normalizeDiagnostic(value: string): string {
  return value.split('\n')[0]!.replace(/\d+ms/g, '<duration>');
}
function hash(value: string): string {
  return createHash('sha256').update(value).digest('hex');
}
function required<T>(value: T | null | undefined, name: string): T {
  if (value === null || value === undefined || value === '') throw new Error(`Missing ${name}`);
  return value;
}
