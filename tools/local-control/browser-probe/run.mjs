import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import http from 'node:http';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { chromium, firefox, webkit } from 'playwright';

const browserName = process.env.MUXTV_BROWSER || 'chromium';
const reportRoot = process.env.MUXTV_REPORT_DIR || '.work/evidence/local-control-browser-origin';
assert.ok(['chromium', 'firefox', 'webkit'].includes(browserName), 'Unknown browser');
const supported = { chromium, firefox, webkit };
const browserType = supported[browserName];

function privateV4(value) {
  if (typeof value !== 'string') return false;
  const fields = value.split('.');
  if (fields.length !== 4) return false;
  const v = fields.map(x => Number(x));
  if (v.some((x, i) => !Number.isInteger(x) || x < 0 || x > 255 || String(x) !== fields[i])) return false;
  return v[0] === 10 || (v[0] === 172 && v[1] >= 16 && v[1] <= 31) ||
    (v[0] === 192 && v[1] === 168);
}

function listen(server, hostname) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, hostname, () => {
      server.off('error', reject);
      resolve(server.address().port);
    });
  });
}

function closeServer(server) {
  return new Promise(resolve => server.close(() => resolve()));
}

function response(res, status, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(status, {
    'Content-Type': type,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(body);
}

async function directRequest(url, headers = {}) {
  const result = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ kind: 'capability-check' }),
    redirect: 'error',
    signal: AbortSignal.timeout(4_000),
  });
  return result.status;
}

/** A standards-compliant fetch client may ignore/normalize Host.
 * Use a raw Node HTTP request for the negative Host authority test. */
async function spoofedHostRequest(url, origin) {
  const target = new URL(url);
  const body = '{"kind":"capability-check"}';
  return new Promise((resolve, reject) => {
    const req = http.request({
      hostname: target.hostname,
      port: Number(target.port),
      path: target.pathname,
      method: 'POST',
      headers: {
        Host: 'attacker.example',
        Origin: origin,
        'X-MuxTV-Lab-Client': '1',
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
      },
    }, res => {
      res.resume();
      res.on('end', () => resolve(res.statusCode));
    });
    req.setTimeout(4_000, () => req.destroy(new Error('host-probe-timeout')));
    req.on('error', reject);
    req.end(body);
  });
}

async function main() {
  const interfaces = Object.values(os.networkInterfaces()).flat()
    .filter(x => x && x.family === 'IPv4' && !x.internal && privateV4(x.address));
  assert.ok(interfaces.length > 0, 'No RFC1918 interface available on hosted runner; cannot qualify LAN origin.');
  const lan = interfaces[0].address;
  const temp = await mkdtemp(path.join(tmpdir(), 'muxtv-browser-origin-'));
  const cert = path.join(temp, 'fixture.crt');
  const key = path.join(temp, 'fixture.key');
  const openssl = spawnSync('openssl', [
    'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-keyout', key, '-out', cert, '-subj', '/CN=localhost',
    '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
  ], { encoding: 'utf8', stdio: 'pipe', timeout: 20_000 });
  assert.equal(openssl.status, 0, 'Failed to generate TEST-ONLY development certificate');
  const [certBytes, keyBytes] = await Promise.all([readFile(cert), readFile(key)]);

  let secureServer, lanServer, browser, context;
  let frontend, target, requestCount = 0;
  try {
    secureServer = https.createServer({ cert: certBytes, key: keyBytes }, (req, res) => {
      if (req.url !== '/' || req.method !== 'GET') return response(res, 404, 'not found');
      return response(res, 200,
        '<!doctype html><meta charset="utf-8"><title>MuxTV origin capability probe</title>' +
        '<main>NON-PRODUCTION capability probe. No provider data accepted.</main>',
        'text/html; charset=utf-8');
    });
    const securePort = await listen(secureServer, '127.0.0.1');
    frontend = `https://127.0.0.1:${securePort}`;

    lanServer = http.createServer(async (req, res) => {
      const expectedHost = `${lan}:${lanServer.address().port}`;
      const origin = req.headers.origin;
      if (req.headers.host !== expectedHost) return response(res, 403, 'host-denied');
      if (req.method === 'GET' && req.url === '/insecure-context') {
        return response(res, 200, '<!doctype html><title>Private LAN HTTP test</title>', 'text/html; charset=utf-8');
      }
      if (req.url !== '/capability') return response(res, 404, 'not-found');
      if (origin !== frontend) return response(res, 403, 'origin-denied');
      const cors = {
        'Access-Control-Allow-Origin': frontend,
        'Access-Control-Allow-Methods': 'POST',
        'Access-Control-Allow-Headers': 'content-type,x-muxtv-lab-client',
        'Access-Control-Max-Age': '0',
        Vary: 'Origin',
      };
      if (req.method === 'OPTIONS') {
        if (req.headers['access-control-request-method'] !== 'POST') {
          return response(res, 403, 'method-denied');
        }
        res.writeHead(204, cors);
        return res.end();
      }
      if (req.method !== 'POST' || req.headers['x-muxtv-lab-client'] !== '1' ||
          !String(req.headers['content-type'] || '').startsWith('application/json')) {
        return response(res, 403, 'request-denied');
      }
      let size = 0;
      let body = '';
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 128) return response(res, 413, 'body-too-large');
        body += chunk.toString('utf8');
      }
      if (body !== '{"kind":"capability-check"}') return response(res, 403, 'body-denied');
      requestCount++;
      res.writeHead(200, { ...cors, 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end('{"kind":"public-nonsecret-fixture-only"}');
    });
    const lanPort = await listen(lanServer, lan);
    target = `http://${lan}:${lanPort}`;

    // Negative server-side checks do not depend on browser security implementation.
    assert.equal(await directRequest(target + '/capability', {
      Origin: 'https://untrusted.example', 'X-MuxTV-Lab-Client': '1',
    }), 403);
    assert.equal(await directRequest(target + '/capability', { Origin: frontend }), 403);
    assert.equal(await spoofedHostRequest(target + '/capability', frontend), 403);
    assert.equal(await directRequest(target + '/capability', {
      Origin: frontend, 'X-MuxTV-Lab-Client': '1',
    }), 200);

    browser = await browserType.launch({
      headless: true,
      ...(browserName === 'chromium' ? { args: ['--no-proxy-server'] } : {}),
    });
    context = await browser.newContext({
      ignoreHTTPSErrors: true, // TEST-ONLY generated TLS. Never a product TLS trust decision.
      viewport: { width: 1024, height: 768 },
      serviceWorkers: 'block',
    });
    let permissionStatus = 'not-emulated-on-this-engine';
    // A failed grantPermissions can poison the WebKit BrowserContext and defer
    // "Unknown permission" until newPage(); only exercise Chrome's supported API.
    if (browserName === 'chromium') {
      try {
        await context.grantPermissions(['local-network-access'], { origin: frontend });
        permissionStatus = 'automation-granted';
      } catch {
        permissionStatus = 'chromium-grant-rejected';
      }
    }

    const page = await context.newPage();
    await page.goto(frontend, { waitUntil: 'load', timeout: 20_000 });
    const secure = await page.evaluate(() => ({
      isSecureContext,
      subtle: !!globalThis.crypto?.subtle,
      origin: location.protocol,
    }));
    assert.equal(secure.isSecureContext, true, 'HTTPS test page was not a secure context');
    assert.equal(secure.subtle, true, 'Web Crypto unavailable in HTTPS test context');
    assert.equal(secure.origin, 'https:');

    await page.goto(target + '/insecure-context', { waitUntil: 'load', timeout: 20_000 });
    const untrusted = await page.evaluate(() => ({
      isSecureContext,
      subtle: !!globalThis.crypto?.subtle,
      scheme: location.protocol,
    }));
    assert.equal(untrusted.scheme, 'http:');
    assert.equal(untrusted.isSecureContext, false,
      'Private LAN HTTP unexpectedly reported secure browser context; review test assumptions');

    await page.goto(frontend, { waitUntil: 'load', timeout: 20_000 });
    const observation = await page.evaluate(async url => {
      let addressSpace = 'not-exposed';
      try {
        addressSpace = new Request(url, { method: 'POST', targetAddressSpace: 'local' })
          .targetAddressSpace || 'not-exposed';
      } catch {
        addressSpace = 'unsupported';
      }
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 8_000);
      try {
        const reply = await fetch(url, {
          method: 'POST',
          mode: 'cors',
          redirect: 'error',
          cache: 'no-store',
          credentials: 'omit',
          targetAddressSpace: 'local',
          headers: { 'Content-Type': 'application/json', 'X-MuxTV-Lab-Client': '1' },
          body: '{"kind":"capability-check"}',
          signal: controller.signal,
        });
        const body = await reply.text();
        return {
          targetAddressSpace: addressSpace,
          outcome: reply.ok && body === '{"kind":"public-nonsecret-fixture-only"}'
            ? 'readable' : 'bad-response',
          status: reply.status,
        };
      } catch (error) {
        return {
          targetAddressSpace: addressSpace,
          outcome: 'blocked-or-unsupported',
          errorName: error?.name || 'Error',
        };
      } finally {
        clearTimeout(timeout);
      }
    }, target + '/capability');

    const report = {
      protocol: 'MuxTV-LC04-S2-browser-origin-lab-v1',
      browser: browserName,
      browserVersion: browser.version(),
      platform: 'github-hosted-linux-headless',
      frontendTrust: 'temporary-self-signed-development-TLS-with-automation-bypass',
      frontendNetworkClassification: 'loopback-not-public-https',
      privateHttpSourceSecureContext: untrusted.isSecureContext,
      trustedTestHttpsSecureContext: secure.isSecureContext,
      trustedTestHttpsWebCrypto: secure.subtle,
      lnaPermission: permissionStatus,
      targetAddressSpace: observation.targetAddressSpace,
      localRequestOutcome: observation.outcome,
      localResponseStatus: observation.status || null,
      localErrorClass: observation.errorName || null,
      negativeOriginHostMarkerChecks: 'PASS',
      localNonsecretFixtureAccepted: requestCount >= 1,
      noProviderCredentialsTransmitted: true,
      productionPhoneCompatibility: 'NOT_QUALIFIED',
      productionCredentialTransport: 'NOT_IMPLEMENTED',
      conclusions: [
        'A TEST-ONLY TLS exception never proves deployable trusted HTTPS origin.',
        'Linux desktop headless browser results cannot substitute for Android/iOS mobile checks.',
        'Permission granted by Playwright automation is not evidence of normal user-granted LNA.',
        'HTTPS-to-LAN HTTP reachability is not confidentiality or TV identity authentication.',
      ],
    };
    const reportDir = path.resolve(reportRoot);
    await mkdir(reportDir, { recursive: true });
    await writeFile(path.join(reportDir, `${browserName}.json`), JSON.stringify(report, null, 2) + '\n', 'utf8');
    console.log(JSON.stringify(report, null, 2));
  } finally {
    await context?.close().catch(() => {});
    await browser?.close().catch(() => {});
    if (secureServer) await closeServer(secureServer);
    if (lanServer) await closeServer(lanServer);
    await rm(temp, { recursive: true, force: true });
  }
}

main().catch(async error => {
  // Preserve RED / inconclusive exact-head evidence even when the fixture fails early.
  // Do not include exception stacks, private IPs, HTTP payloads or certificates.
  const reportDirectory = path.resolve(reportRoot);
  await mkdir(reportDirectory, { recursive: true });
  await writeFile(path.join(reportDirectory, `${browserName}.json`), JSON.stringify({
    protocol: 'MuxTV-LC04-S2-browser-origin-lab-v1',
    browser: browserName,
    outcome: 'FIXTURE_FAILED',
    failureKind: error?.name || 'Error',
    productionPhoneCompatibility: 'NOT_QUALIFIED',
    productionCredentialTransport: 'NOT_IMPLEMENTED',
    noProviderCredentialsTransmitted: true,
  }, null, 2) + '\n');
  console.error('Browser origin fixture failed:', String(error?.message || error).slice(0, 180));
  process.exitCode = 1;
});
