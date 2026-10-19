/* Optional browser regression: see SECURITY-RELEASE-NOTES.md for invocation. */
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const origin = process.env.CONSOLE_TEST_ORIGIN || 'http://127.0.0.1:18081';
const base = '/db-scheduler-console';
const csp = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; object-src 'none'; form-action 'self'; frame-ancestors 'none'";
(async () => {
  const browser = await chromium.launch({ headless: true,
    ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
  try {
    const context = await browser.newContext({ httpCredentials: {
      username: process.env.CONSOLE_USERNAME, password: process.env.CONSOLE_PASSWORD } });
    const page = await context.newPage();
    let revoked = false;
    let payloadRequests = 0;
    let historyRequests = 0;
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => {
      window.cspViolations = [];
      document.addEventListener('securitypolicyviolation', e => window.cspViolations.push(e.violatedDirective));
      if (!sessionStorage.getItem('seeded')) {
        localStorage.setItem('htmx-history-cache', JSON.stringify([
          {url:'/db-scheduler-console/history', content:'OLD_SECRET'},
          {url:'/unrelated', content:'HOST_CONTENT'}
        ]));
        sessionStorage.setItem('seeded','true');
      }
    });
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.pathname === '/logout-test') return route.fulfill({contentType:'text/html',body:'<p>Logged out</p>'});
      if (url.pathname.startsWith(base) && revoked)
        return route.fulfill({status:403,contentType:'text/plain',body:'Authorization revoked',headers:{'Cache-Control':'no-store'}});
      if (url.pathname.startsWith(base + '/payload/')) payloadRequests++;
      if (url.pathname === base + '/history') historyRequests++;
      const response = await route.fetch({headers: {...route.request().headers(),
        Authorization: 'Basic ' + Buffer.from(process.env.CONSOLE_USERNAME + ':' + process.env.CONSOLE_PASSWORD).toString('base64')}});
      return route.fulfill({response, headers:{...response.headers(), 'content-security-policy':csp}});
    });
    await page.goto(origin + base + '/history');
    await page.waitForFunction(() => typeof htmx !== 'undefined');
    assert.ok((await page.evaluate(() => htmx.version)).startsWith('4.'));
    assert.equal(await page.evaluate(() => htmx.config.history), 'reload');
    assert.equal(await page.evaluate(() => htmx.config.includeIndicatorCSS), false);
    assert.equal(await page.locator('[onclick], [style], [hx-on\\:\\:before\\:swap]').count(), 0);
    assert.equal(await page.evaluate(async () => {
      const probe = document.createElement('button');
      probe.setAttribute('hx-on:click', 'window.htmxEvaluated = true');
      document.body.append(probe);
      htmx.process(probe);
      probe.click();
      await new Promise(resolve => setTimeout(resolve, 50));
      probe.remove();
      return window.htmxEvaluated === true;
    }), false, 'htmx evaluation must stay disabled');
    assert.deepEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('htmx-history-cache')).map(e=>e.url)), ['/unrelated']);
    assert.equal(payloadRequests, 0);
    const preview = page.locator('details[data-payload-url]').first();
    await preview.locator('summary').click();
    await page.waitForFunction(() => {
      const p = document.querySelector('details[data-payload-url] pre');
      return p && p.textContent !== 'Loading…' && p.textContent !== 'Open to load';
    });
    assert.equal(payloadRequests, 1);
    const before = historyRequests;
    await page.waitForTimeout(5500);
    assert.equal(historyRequests, before, 'polling must pause while a detail is open');
    await preview.locator('summary').click();
    await page.waitForFunction(() => document.querySelector('details[data-payload-url]')?.open === false);
    await page.waitForTimeout(5500);
    assert.ok(historyRequests > before, 'polling resumes after closing detail');
    assert.deepEqual(await page.evaluate(() => window.cspViolations), []);
    await page.goto(origin + base + '/recurring');
    const run = page.locator('form[hx-post$="/actions/run-now"] button').first();
    await run.click();
    await page.locator('[data-dismiss-flash]').waitFor();
    await page.locator('[data-dismiss-flash]').click();
    assert.equal(await page.locator('[data-dismiss-flash]').count(), 0);
    assert.deepEqual(await page.evaluate(() => window.cspViolations), []);
    await page.goto(origin + base + '/history');
    await page.goto(origin + '/logout-test');
    revoked = true;
    await page.goBack();
    await page.getByText('Authorization revoked', {exact:true}).waitFor();
    assert.equal(await page.locator('#history-region').count(), 0);
    assert.ok(!(await page.content()).includes('OLD_SECRET'));
    assert.deepEqual(errors, []);
    console.log('Browser security checks passed: CSP, polling, lazy payloads, actions, snapshots, revoked back navigation');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
