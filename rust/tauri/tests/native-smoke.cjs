// Read-only verification of the real WebView, assets, and JVM bridge.
const { chromium } = require("playwright");
const { spawn } = require("node:child_process");
const fs = require("node:fs/promises");
const path = require("node:path");
const net = require("node:net");
const assert = require("node:assert/strict");
(async () => {
  const app = path.resolve(process.argv[2]);
  const probe = net.createServer();
  await new Promise((resolve) => probe.listen(0, "127.0.0.1", resolve));
  const port = probe.address().port;
  await new Promise((resolve) => probe.close(resolve));
  const child = spawn(
    path.join(app, "app/AWLauncher-native.exe"),
    ["--backend", path.join(app, "AWLauncher.exe")],
    {
      cwd: app,
      windowsHide: true,
      env: {
        ...process.env,
        WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${port} --remote-debugging-address=127.0.0.1`,
      },
      stdio: "ignore",
    },
  );
  let browser;
  try {
    for (let i = 0; i < 90; i++) {
      try {
        browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`, {
          timeout: 1000,
        });
        break;
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 500));
      }
    }
    assert.ok(browser, "WebView did not expose its test connection");
    const page = browser.contexts()[0].pages()[0];
    assert.ok(page, "No launcher page");
    await page
      .locator("#navigation [data-page=library]")
      .waitFor({ timeout: 90000 });
    await page.locator("#main .home").waitFor({ timeout: 90000 });
    assert.equal(
      await page.locator("#main .error").count(),
      0,
      await page.locator("#main").innerText(),
    );
    assert.equal(await page.locator(".window-controls svg").count(), 3);
    const avatarLoaded = await page.locator("#avatar img").count();
    if (avatarLoaded)
      await page.waitForFunction(
        () => document.querySelector("#avatar img")?.naturalWidth > 0,
      );
    const out = path.resolve(__dirname, "../test-results");
    await fs.mkdir(out, { recursive: true });
    await page.screenshot({
      animations: "disabled",
      path: path.join(out, "native-home.png"),
    });
    await page.locator('#home-activity').click();await page.locator('.activity-view').waitFor({timeout:60000});
    await page.locator('#navigation [data-page=home]').click();await page.locator('#main .home').waitFor();
    await page.locator("[data-window=toggleMaximize]").click();
    await page.waitForFunction(() =>
      document
        .querySelector("[data-window=toggleMaximize]")
        .classList.contains("maximized"),
    );
    await page.locator("[data-window=toggleMaximize]").click();
    await page.locator("#navigation [data-page=library]").click();
    await page
      .getByRole("heading", { name: "Мои сборки", exact: true })
      .waitFor();
    const builds = await page.locator(".open-build").count();
    if (builds) {
      await page.locator(".open-build").first().click();
      await page.locator(".content-table").waitFor({ timeout: 120000 });
      await page.locator("#main [data-tab=screenshots]").click();
      await page.locator("#shot-content").waitFor({ timeout: 30000 });
      assert.equal(await page.locator(".instance-header").count(), 1);
      assert.equal(await page.locator("#shot-target").count(), 0);
      if (await page.locator(".shot").count())
        await page.waitForFunction(
          () => document.querySelector(".shot img")?.naturalWidth > 0,
          {},
          { timeout: 60000 },
        );
      await page.screenshot({
        animations: "disabled",
        path: path.join(out, "native-instance-screenshots.png"),
      });
    }
    await page.locator("#navigation [data-page=screenshots]").click();
    await page.locator("#shot-target").waitFor();
    if (await page.locator(".shot").count()) {
      await page.waitForFunction(
        () => document.querySelector(".shot img")?.naturalWidth > 0,
        {},
        { timeout: 60000 },
      );
      await page
        .locator("#main")
        .evaluate((el) => (el.scrollTop = el.scrollHeight));
      await page.waitForFunction(
        () => {
          const images = document.querySelectorAll(".shot img");
          return images[images.length - 1]?.naturalWidth > 0;
        },
        {},
        { timeout: 60000 },
      );
      await page.locator("#main").evaluate((el) => (el.scrollTop = 0));
      const target=await page.locator('.shot img').evaluateAll(images=>{const counts={};for(const img of images)counts[img.dataset.imageTarget]=(counts[img.dataset.imageTarget]||0)+1;return Object.entries(counts).sort((a,b)=>a[1]-b[1])[0]?.[0];});
      if(target){
        const optionIndex=await page.locator('#shot-target').evaluate((select,key)=>[...select.options].findIndex(option=>option.value===key),target);
        await page.locator('.select-field:has(#shot-target) .select-trigger').click();await page.locator(`.select-options [data-index="${optionIndex}"]`).click();
        const gap=await page.evaluate(()=>document.getElementById('shot-count').getBoundingClientRect().top-document.getElementById('shot-query').getBoundingClientRect().bottom);assert.ok(gap<30,`Screenshot gap: ${gap}`);
        await page.screenshot({animations:'disabled',path:path.join(out,'native-one-screenshot.png')});
      }
    }
    await page.locator("#settings-button").click();
    await page.locator("[data-action=settings-tab][data-tab=game]").click();
    await page.locator("#memory-value").waitFor();
    await page.screenshot({
      animations: "disabled",
      path: path.join(out, "native-settings-game.png"),
    });
    await page.keyboard.press("Escape");
    await page.locator("#account-button").click();
    assert.equal(
      await page.locator("#account-menu [data-action=remove-account]").count(),
      await page.locator("#account-menu [data-action=select-account]").count(),
    );
    await page.screenshot({
      animations: "disabled",
      path: path.join(out, "native-account-menu.png"),
    });
    if (
      await page.locator("#account-menu [data-action=edit-account]").count()
    ) {
      await page
        .locator("#account-menu [data-action=edit-account]")
        .first()
        .click();
      await page.waitForFunction(()=>state.originalWindowReady===true,{},{timeout:90000});
      await page.evaluate(()=>window.__TAURI__.core.invoke('cancel',{id:state.originalWindowTaskId}));
      await page.waitForFunction(()=>state.originalWindow===false,{},{timeout:30000});

    }
    await page.locator("#settings-button").click();
    await page.locator("[data-action=settings-tab][data-tab=language]").click();
    assert.equal(
      await page.locator(".language-radio").count(),
      await page.locator(".language-row").count(),
    );
    await page.screenshot({
      animations: "disabled",
      path: path.join(out, "native-languages.png"),
    });
    console.log(
      `Native WebView: assets, JVM state (${builds} profiles), window controls, gallery and settings passed`,
    );
  } finally {
    await browser?.close().catch(() => {});
    child.kill();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
