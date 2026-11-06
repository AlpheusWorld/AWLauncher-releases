const { test, before, after, beforeEach, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { chromium } = require("playwright");
const http = require("node:http");
const fs = require("node:fs/promises");
const path = require("node:path");
let server, browser, page, url;
const root = path.resolve(__dirname, "../dist");
const csp = require("../tauri.conf.json").app.security.csp;
before(async () => {
  server = http.createServer(async (req, res) => {
    try {
      const file = path.resolve(
        root,
        "." +
          decodeURIComponent(
            req.url.split("?")[0] === "/"
              ? "/index.html"
              : req.url.split("?")[0],
          ),
      );
      if (!file.startsWith(root + path.sep)) {
        res.writeHead(403).end();
        return;
      }
      const content = await fs.readFile(file);
      res.setHeader("Content-Security-Policy", csp);
      res.setHeader(
        "Content-Type",
        {
          ".html": "text/html",
          ".js": "text/javascript",
          ".css": "text/css",
          ".json": "application/json",
          ".png": "image/png",
          ".ttf": "font/ttf",
        }[path.extname(file)] || "application/octet-stream",
      );
      res.end(content);
    } catch {
      res.writeHead(404).end();
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  url = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({
    headless: true,
    ...(process.env.AW_TEST_CHROMIUM
      ? { executablePath: process.env.AW_TEST_CHROMIUM }
      : {}),
  });
});
after(async () => {
  await browser?.close();
  await new Promise((resolve) => server?.close(resolve));
});
beforeEach(async () => {
  page = await browser.newPage({ viewport: { width: 1920, height: 1040 } });
  const imageData =
    "data:image/png;base64," +
    (await fs.readFile(path.join(root, "assets/icons/cube.png"))).toString(
      "base64",
    );
  await page.addInitScript(
    ({ imageData }) => {
      const snapshot = {
        version: "1.0.50",
        totalMemory: 16384,
        recommendedMemory: 4096,
        accounts: [
          {
            id: "one",
            name: "AlexPlayer",
            type: "OFFLINE",
            selected: true,
            face: imageData,
          },
          {
            id: "two",
            name: "MicrosoftPlayer",
            type: "MICROSOFT",
            selected: false,
            face: imageData,
            skinModel: "CLASSIC",
            capes: [{ id: "cape-1", alias: "Доступный плащ" }],
            capeId: null,
          },
        ],
        settings: {
          themeMode: "DARK",
          language: "RU",
          memoryMb: 4096,
          libraryGroups: [],
        },
        builds: [
          {
            key: "build:one",
            name: "Первая сборка",
            version: "1.21",
            loader: "Fabric",
            ready: true,
            options: { iconPreset: "fox" },
          },
          {
            key: "build:two",
            name: "Вторая сборка",
            version: "1.21",
            loader: "Fabric",
            ready: true,
            options: {},
          },
        ],
        notices: { items: [] },
      };
      const shots = [
        {
          target: "build:one",
          title: "Первая сборка",
          name: "one-a.png",
          file: "screenshots/one-a.png",
          modified: 1000,
        },
        {
          target: "build:one",
          title: "Первая сборка",
          name: "one-b.png",
          file: "screenshots/one-b.png",
          modified: 900,
        },
        {
          target: "build:two",
          title: "Вторая сборка",
          name: "two.png",
          file: "screenshots/two.png",
          modified: 800,
        },
      ];
      const skinCanvas=document.createElement('canvas');skinCanvas.width=skinCanvas.height=64;
      const skinContext=skinCanvas.getContext('2d');skinContext.fillStyle='#e6463c';skinContext.fillRect(0,0,32,16);skinContext.fillStyle='#3c8cd2';skinContext.fillRect(0,16,64,16);skinContext.fillRect(16,48,32,16);skinContext.fillStyle='#32d250';skinContext.fillRect(40,8,8,8);
      const skinPng=skinCanvas.toDataURL('image/png');window.testSkinPng=skinPng;
      skinCanvas.height=32;skinContext.fillStyle='#2850f5';skinContext.fillRect(0,0,64,32);const capePng=skinCanvas.toDataURL('image/png');
      const skinProfile={id:'two',name:'MicrosoftPlayer',model:'CLASSIC',png:skinPng,capes:[{id:'cape-1',alias:'Доступный плащ'}],capeId:null};
      let skinSaved=[{id:'saved-one',name:'Красный скин',model:'CLASSIC'},{id:'saved-two',name:'Синий скин',model:'SLIM'}];
      const skinPresets=[{id:'preset:default',name:'Steve',section:'Стандартные скины',model:'CLASSIC'},{id:'preset:event',name:'Праздничный',section:'Праздничный набор',model:'SLIM'}];
      window.calls = [];
      window.deferScreenshots = false;
      window.rejectToggle = false;
      const invoke = async (command, args) => {
        window.calls.push({ command, args: JSON.parse(JSON.stringify(args)) });
        if (command === "request") {
          const r = args.request;
          if(r.op === 'checkUpdate') return {available:'1.0.53',message:'Доступна версия 1.0.53'};
          if(r.op === 'installUpdate') {
            if(window.rejectUpdate) throw 'Ошибка загрузки обновления';
            args.events.onmessage({type:'progress',data:{fraction:0.42,bytesPerSecond:2048}});
            await new Promise(resolve => window.finishUpdate = resolve);
            return {installing:true};
          }
          if (r.op === "launcherState") return structuredClone(snapshot);
          if (r.op === "accountMicrosoft") {
            args.events.onmessage({type: "login", data: {code: "AW-TEST", url: "https://microsoft.com/devicelogin"}});
            await new Promise(resolve => window.finishMicrosoftLogin = resolve);
            return "Аккаунт добавлен";
          }
          const build=snapshot.builds.find(b=>b.key===r.target);
          if(r.op==='buildEditor')return {options:structuredClone(build.options),loader:'FABRIC',game:build.version,canRename:true,groups:structuredClone(snapshot.settings.libraryGroups),memoryLimit:15298,recommendedMemory:4096,memoryPresets:[2048,4096,8192],downloadAllowed:true};
          if(r.op==='buildLoaderVersions')return [{version:'0.16.14',label:'0.16.14 · стабильная',stable:true},{version:'0.16.10',label:'0.16.10',stable:true}];
          if(r.op==='buildVersions')return ['1.22','1.21.4'];
          if(r.op==='saveOptions'){Object.assign(build.options,r.options);return 'Настройки сборки сохранены';}
          if(r.op==='rename'){build.name=r.title;return 'Название сохранено';}
          if(r.op==='buildPickIcon')return {image:'aw-icon-0123456789abcdef01234567.img',preview:imageData};
          if(r.op==='buildSaveIcon'){Object.assign(build.options,{iconPreset:r.options.reset?null:r.options.symbol,iconBackground:r.options.reset?null:r.options.background,iconImage:r.options.image||null});return structuredClone(build.options);}
          if(r.op==='buildExportChoices')return ['mods','config','saves'];
          if(r.op==='buildPickPath')return {path:r.kind==='export'?'C:\\Exports\\test.mrpack':'C:\\Java\\bin\\java.exe',exists:!!window.exportExists};
          if(r.op==='buildDeleteInfo')return {bytes:1234567,worlds:true,trash:true};
          if(r.op==='buildDelete'){snapshot.builds=snapshot.builds.filter(b=>b.key!==r.target);return 'Сборка удалена';}
          if(r.op==='buildGroups'){
            let groups=snapshot.settings.libraryGroups;const action=r.options.action;
            if(action==='create')groups.push({id:'group-new',name:r.title});
            if(action==='rename')groups.find(g=>g.id===r.project).name=r.title;
            if(action==='remove'){groups=snapshot.settings.libraryGroups=groups.filter(g=>g.id!==r.project);for(const b of snapshot.builds)if(b.options.groupId===r.project)b.options.groupId=null;}
            return structuredClone(groups);
          }
          if(r.op==='buildMigrationAnalyze'){window.testMigration={id:'plan-one',source:build.version,target:r.version,mods:[{file:'sodium.jar',title:'Sodium',version:'0.5',replacement:'0.6',enabled:true,compatible:true},{file:'unknown.jar',title:'Unknown mod',reason:'Совместимость не подтверждена',enabled:true,compatible:false}]};return structuredClone(window.testMigration);}
          if(r.op==='buildMigrationApply'){if(window.failMigration){window.failMigration=false;window.testMigration.failure={file:'sodium.jar',title:'Sodium',message:'Ошибка загрузки'};return {failed:true,message:'Ошибка загрузки',plan:structuredClone(window.testMigration)};}build.version=window.testMigration.target;return 'Версия обновлена';}
          if(r.op==='accountSkinState'){if(window.failSkinStateOnce){window.failSkinStateOnce=false;throw Error('Не удалось открыть библиотеку скинов');}return {profile:structuredClone(skinProfile),saved:structuredClone(skinSaved),presets:skinPresets};}
          if(r.op==='accountSkinProfile')return {profile:structuredClone(skinProfile),accounts:structuredClone(snapshot.accounts)};
          if(r.op==='accountSkinTextures')return r.options.ids.map(id=>({id,png:id.startsWith('cape:')?capePng:skinPng}));
          if(r.op==='accountSkinSave') {const id=r.project||'saved-new',item={id,name:r.title,model:r.kind,capeId:r.options.capeId};const old=skinSaved.findIndex(s=>s.id===id);if(old<0)skinSaved.push(item);else skinSaved[old]=item;return {saved:structuredClone(skinSaved),selected:id};}
          if(r.op==='accountSkinMove'){const index=skinSaved.findIndex(s=>s.id===r.project),item=skinSaved.splice(index,1)[0];skinSaved.splice(r.options.position,0,item);return structuredClone(skinSaved);}
          if(r.op==='accountSkinRemove'){skinSaved=skinSaved.filter(s=>s.id!==r.project);return structuredClone(skinSaved);}
          if(r.op==='accountSkinApply'){if(r.options.changeSkin){skinProfile.png=r.options.png||skinPng;skinProfile.model=r.kind;}if(r.options.changeCape)skinProfile.capeId=r.options.capeId;return {profile:structuredClone(skinProfile),accounts:structuredClone(snapshot.accounts)};}
          if (r.op === "accountSelect") {
            if (window.deferAccount)
              await new Promise((resolve) => (window.commitAccount = resolve));
            if (window.rejectAccount)
              throw "Не удалось сохранить выбранный аккаунт";
            snapshot.accounts.forEach((a) => (a.selected = a.id === r.file));
            return "Аккаунт выбран";
          }
          if (r.op === "accountRemove") {
            snapshot.accounts = snapshot.accounts.filter(
              (a) => a.id !== r.file,
            );
            if (
              !snapshot.accounts.some((a) => a.selected) &&
              snapshot.accounts[0]
            )
              snapshot.accounts[0].selected = true;
            return structuredClone(snapshot.accounts);
          }
          if (r.op === "launch") { if(window.rejectLaunch) throw "Не удалось запустить игру"; if(window.deferLaunch) await new Promise(resolve => window.finishLaunch = resolve); return { pid: 123, message: "Игра запущена" }; }
          if (r.op === "content") {
            if (window.deferContent)
              await new Promise((resolve) => (window.finishContent = resolve));
            return [
              {
                title: "Sodium",
                file: "mods/sodium.jar",
                kind: "mod",
                enabled: true,
                version: "0.6",
              },
            ].concat(window.extraContent || []);
          }
          if (r.op === "toggle" && window.rejectToggle)
            throw "Не удалось сохранить файл";
          if (r.op === "toggle")
            return {
              enabled: r.enabled,
              file: r.enabled ? "mods/sodium.jar" : "mods/sodium.jar.disabled",
            };
          if (r.op === "history") return [];
          return "Готово";
        }
        if (command === "local_data") {
          if (args.kind === "activity")
            return {
              days: {},
              recent: [],
              versions: [],
              servers: [],
              total_ms: 0,
              week_ms: 0,
              streak: 0,
              longest_ms: 0,
            };
          if (args.kind === "screenshots") {
            if (window.deferScreenshots)
              await new Promise(
                (resolve) => (window.finishScreenshots = resolve),
              );
            return shots
              .concat(window.extraShots || [])
              .filter((s) => !args.target || args.target === s.target);
          }
          if (args.kind === "log")
            return [
              "[13:21:43] [main/INFO]: Starting Minecraft",
              "[13:21:44] [main/WARN]: Optional dependency is missing",
              "[13:21:45] [main/ERROR]: Example error",
            ];
          return [];
        }
        if (command === "image_data") return imageData;
        if (command === "translate_text")
          return args.values.map((v) =>
            args.language === "ES" ? "ES: " + v : v,
          );
        if (command === "search")
          return {
            hits: [
              {
                project_id: "sodium",
                title:
                  args.sort === "updated"
                    ? "Обновлённый проект"
                    : "Проект каталога",
                slug: "sodium",
                description: "Описание проекта",
                categories: ["fabric"],
                author: "Author",
                downloads: 1234,
              },
            ],
            total_hits: 1,
          };
        if (command === "running_games") return [];
      };
      window.windowMaximized = false;
      window.__TAURI__ = {
        core: {
          invoke,
          Channel: class {
            onmessage() {}
          },
        },
        window: {
          getCurrentWindow: () => ({
            minimize: async () => {},
            isMaximized: async () => window.windowMaximized,
            toggleMaximize: async () => {
              window.windowMaximized = !window.windowMaximized;
            },
            close: async () => { window.closedForUpdate = true; },
          }),
        },
      };
    },
    { imageData },
  );
  await page.goto(url);
  await page.getByRole("heading", { name: "Играть", exact: true }).waitFor();
});
afterEach(async () => {
  await page?.close();
});
test("кнопки окна используют контуры и меняют значок восстановления", async () => {
  const button = page.locator("[data-window=toggleMaximize]");
  assert.equal(await page.locator(".window-controls svg").count(), 3);
  assert.equal((await page.locator(".window-controls").innerText()).trim(), "");
  await button.click();
  await page.waitForFunction(() =>
    document
      .querySelector("[data-window=toggleMaximize]")
      .classList.contains("maximized"),
  );
  assert.equal(await button.getAttribute("aria-label"), "Восстановить окно");
  await button.click();
  await page.waitForFunction(
    () =>
      !document
        .querySelector("[data-window=toggleMaximize]")
        .classList.contains("maximized"),
  );
});
async function openBuild() {
  await page.locator("#navigation [data-page=library]").click();
  await page
    .locator(".open-build")
    .filter({ hasText: "Первая сборка" })
    .click();
  try {
    await page.locator(".content-table").waitFor({ timeout: 5000 });
  } catch (error) {
    throw new Error(await page.locator("#main").innerText(), { cause: error });
  }
}
test("скриншоты сборки остаются во вкладке сборки, общая галерея открывается отдельно", async () => {
  await openBuild();
  await page
    .locator("#main")
    .getByRole("button", { name: "Скриншоты", exact: true })
    .click();
  await page.locator(".shot").first().waitFor();
  assert.equal(
    await page.locator(".instance-header h1").textContent(),
    "Первая сборка",
  );
  assert.equal(
    await page.locator("#navigation [data-page=library]").getAttribute("class"),
    "active",
  );
  assert.equal(await page.locator("#shot-target").count(), 0);
  assert.equal(await page.locator(".shot").count(), 2);
  await page.locator(".shot").first().click();
  await page.getByRole("button", { name: "Следующее →" }).click();
  assert.match(await page.locator(".viewer").textContent(), /one-b.png/);
  await page.keyboard.press("Escape");
  assert.equal(await page.locator(".shot").count(), 2);
  assert.equal(await page.locator(".instance-header").count(), 1);
  await page.locator("#navigation [data-page=screenshots]").click();
  await page.locator("#shot-target").waitFor();
  assert.equal(await page.locator(".shot").count(), 3);
  await page.locator("#shot-target").selectOption("build:two");
  assert.equal(await page.locator(".shot").count(), 1);
});
test("отказ сохранения переключателя оставляет подтверждённое состояние мода", async () => {
  await openBuild();
  await page.evaluate(() => (window.rejectToggle = true));
  await page.locator("[data-action=toggle-content]").click();
  await page.locator("#toast.error").waitFor();
  assert.equal(
    await page
      .locator("[data-action=toggle-content]")
      .getAttribute("aria-checked"),
    "true",
  );
  assert.equal(
    await page.locator("[data-action=toggle-content]").isEnabled(),
    true,
  );
});
test("повторное переключение использует новое имя отключённого файла", async () => {
  await openBuild();
  await page.locator("[data-action=toggle-content]").click();
  await page.waitForFunction(
    () =>
      document
        .querySelector("[data-action=toggle-content]")
        ?.getAttribute("aria-checked") === "false",
  );
  await page.locator("[data-action=toggle-content]").click();
  await page.waitForFunction(
    () =>
      document
        .querySelector("[data-action=toggle-content]")
        ?.getAttribute("aria-checked") === "true",
  );
  const files = await page.evaluate(() =>
    window.calls
      .filter((x) => x.command === "request" && x.args.request.op === "toggle")
      .map((x) => x.args.request.file),
  );
  assert.deepEqual(files, ["mods/sodium.jar", "mods/sodium.jar.disabled"]);
});
test("запоздалое чтение галереи не заменяет экран после перехода", async () => {
  await openBuild();
  await page.evaluate(() => (window.deferScreenshots = true));
  await page
    .locator("#main")
    .getByRole("button", { name: "Скриншоты", exact: true })
    .click();
  await page.waitForFunction(() => !!window.finishScreenshots);
  await page.locator("#navigation [data-page=downloads]").click();
  await page.getByRole("heading", { name: "Загрузки", exact: true }).waitFor();
  await page.evaluate(() => window.finishScreenshots());
  await page
    .getByRole("heading", { name: "Загрузок пока нет", exact: true })
    .waitFor();
  assert.equal(await page.locator(".gallery").count(), 0);
});
test("галерея и настройки укладываются в широкое и минимальное окно", async () => {
  for (const viewport of [
    { width: 1920, height: 1040 },
    { width: 815, height: 630 },
    { width: 600, height: 420 },
  ]) {
    await page.setViewportSize(viewport);
    await page.locator("#navigation [data-page=screenshots]").click();
    await page.locator(".shot").first().waitFor();
    assert.ok(
      await page
        .locator("#main")
        .evaluate((el) => el.scrollWidth <= el.clientWidth + 1),
    );
    await page.locator("#settings-button").click();
    await page.locator("#settings").waitFor({ state: "visible" });
    await page.locator("[data-action=settings-tab][data-tab=game]").click();
    assert.ok(
      await page
        .locator(".settings-body")
        .evaluate((el) => el.scrollWidth <= el.clientWidth + 1),
    );
    await page.keyboard.press("Escape");
  }
});
test("основные экраны и настройки создают визуальные эталоны", async () => {
  const out = path.resolve(__dirname, "../test-results");
  await fs.mkdir(out, { recursive: true });
  await page.screenshot({
    animations: "disabled",
    path: path.join(out, "home.png"),
  });
  await page.locator("#settings-button").click();
  await page.locator("[data-action=settings-tab][data-tab=game]").click();
  await page.screenshot({
    animations: "disabled",
    path: path.join(out, "settings-game.png"),
  });
  await page.keyboard.press("Escape");
  await openBuild();
  await page
    .locator("#main")
    .getByRole("button", { name: "Скриншоты", exact: true })
    .click();
  await page.locator(".shot").first().waitFor();
  await page.screenshot({
    animations: "disabled",
    path: path.join(out, "instance-screenshots.png"),
  });
  await page.locator("#main [data-tab=log]").click();
  await page.locator("#log-scroll").waitFor();
  await page.screenshot({
    animations: "disabled",
    path: path.join(out, "journal.png"),
  });
  await page.locator("#main [data-tab=statistics]").click();
  await page.locator(".activity-view").waitFor();
  await page.screenshot({
    animations: "disabled",
    path: path.join(out, "statistics.png"),
  });
  await page.locator(".instance-header [data-action=build-menu]").click();
  await page.screenshot({
    animations: "disabled",
    path: path.join(out, "build-menu.png"),
  });
 await page.keyboard.press("Escape");
});

test("фильтры контента сохраняют строки и не запускают повторное сканирование", async () => {
  await page.evaluate(
    () =>
      (window.extraContent = [
        {
          title: "Шейдер",
          file: "shaderpacks/a.zip",
          kind: "shader",
          enabled: false,
          version: "1",
        },
      ]),
  );
  await openBuild();
  await page
    .locator(".instance-header")
    .evaluate((el) => (el.dataset.sentinel = "header"));
  await page
    .locator('[data-content-index="0"]')
    .evaluate((el) => (el.dataset.sentinel = "row"));
  const calls = await page.evaluate(
    () =>
      window.calls.filter(
        (x) => x.command === "request" && x.args.request.op === "content",
      ).length,
  );
  await page.locator("[data-action=content-kind][data-kind=shader]").click();
  assert.equal(
    await page.locator('[data-content-index="0"]').isVisible(),
    false,
  );
  assert.equal(
    await page.locator('[data-content-index="1"]').isVisible(),
    true,
  );
  await page.locator('[data-action=content-kind][data-kind=""]').click();
  assert.equal(
    await page
      .locator('[data-content-index="0"]')
      .getAttribute("data-sentinel"),
    "row",
  );
  assert.equal(
    await page.locator(".instance-header").getAttribute("data-sentinel"),
    "header",
  );
  assert.equal(
    await page.evaluate(
      () =>
        window.calls.filter(
          (x) => x.command === "request" && x.args.request.op === "content",
        ).length,
    ),
    calls,
  );
});
test("переход назад возвращает готовый экран и положение фильтра", async () => {
  await openBuild();
  await page.locator("#content-query").fill("Sodium");
  await page
    .locator(".content-table")
    .evaluate((el) => (el.dataset.cached = "yes"));
  await page.locator("#main [data-tab=screenshots]").click();
  await page.locator("#shot-content").waitFor();
  await page.locator("#main [data-tab=content]").click();
  assert.equal(
    await page.locator(".content-table").getAttribute("data-cached"),
    "yes",
  );
  assert.equal(await page.locator("#content-query").inputValue(), "Sodium");
  assert.equal(
    await page.evaluate(
      () =>
        window.calls.filter(
          (x) => x.command === "request" && x.args.request.op === "content",
        ).length,
    ),
    1,
  );
});
test("списки используют оформленное меню с клавиатурным управлением", async () => {
  await page.locator("#navigation [data-page=screenshots]").click();
  await page.locator("#shot-target").waitFor();
  await page.locator("#shot-target").evaluate((el) => el._trigger.click());
  const popup = page.getByRole("listbox");
  await popup.waitFor();
  assert.equal(await popup.locator("[role=option]").count(), 3);
  await popup
    .getByRole("option", { name: "Вторая сборка", exact: true })
    .click();
  assert.equal(await page.locator(".shot").count(), 1);
  assert.equal(await page.locator("#shot-target").inputValue(), "build:two");
  assert.equal(await page.getByRole("listbox").count(), 0);
});
test("смена сортировки каталога обновляет только результаты", async () => {
  await page.locator("#navigation [data-page=catalog]").click();
  await page.locator(".project-card").waitFor();
  await page
    .locator("#catalog-query")
    .evaluate((el) => (el.dataset.same = "yes"));
  await page.locator("[data-action=catalog-sort][data-sort=updated]").click();
  await page
    .locator(".project-title")
    .filter({ hasText: "Обновлённый проект" })
    .waitFor();
  assert.equal(
    await page.locator("#catalog-query").getAttribute("data-same"),
    "yes",
  );
  await page.locator("#catalog-query").fill("test");
  await page.locator("#catalog-search").press("Enter");
  await page.waitForFunction(() =>
    window.calls.some((x) => x.command === "search" && x.args.query === "test"),
  );
  assert.equal(
    await page.locator("#catalog-query").getAttribute("data-same"),
    "yes",
  );
});
test("миниатюры продолжают загружаться после минуты и прокрутки", async () => {
  await page.clock.install();
  await page.evaluate(
    () =>
      (window.extraShots = Array.from({ length: 90 }, (_, i) => ({
        target: "build:one",
        title: "Сборка",
        name: `extra-${i}.png`,
        file: `screenshots/extra-${i}.png`,
        modified: 500 - i,
      }))),
  );
  await page.locator("#navigation [data-page=screenshots]").click();
  await page.waitForFunction(
    () => document.querySelector(".shot img")?.naturalWidth > 0,
  );
  await page.clock.fastForward(61000);
  await page
    .locator("#main")
    .evaluate((el) => (el.scrollTop = el.scrollHeight));
  await page.waitForFunction(() => {
    const images = document.querySelectorAll(".shot img");
    return images[images.length - 1]?.naturalWidth > 0;
  });
  assert.equal(await page.locator(".shot").count(), 93);
});
test("действия во всех строках имеют одинаковое положение", async () => {
  await page.evaluate(
    () =>
      (window.extraContent = [
        {
          title: "With update",
          file: "mods/update.jar",
          kind: "mod",
          enabled: true,
          update: "new",
          version: "1",
        },
        {
          title: "Disabled",
          file: "mods/off.jar.disabled",
          kind: "mod",
          enabled: false,
          version: "1",
        },
      ]),
  );
  await openBuild();
  const boxes = await page
    .locator("[data-action=toggle-content]")
    .evaluateAll((els) =>
      els.map((el) => ({
        x: el.getBoundingClientRect().x,
        width: el.getBoundingClientRect().width,
      })),
    );
  assert.equal(boxes.length, 3);
  assert.ok(
    boxes.every(
      (b) =>
        Math.abs(b.x - boxes[0].x) < 1 &&
        Math.abs(b.width - boxes[0].width) < 1,
    ),
  );
});
test("журнал начинается под панелью поиска и фильтруется без перезагрузки", async () => {
  await openBuild();
  await page.locator("#main [data-tab=log]").click();
  await page.locator(".log-line").first().waitFor();
  const top = await page
    .locator("#log-scroll")
    .evaluate((el) => el.getBoundingClientRect().top);
  assert.ok(top < 420, `Log starts at ${top}`);
  await page.locator("#log-query").fill("WARN");
  assert.equal(await page.locator(".log-line").count(), 1);
  assert.equal(await page.locator(".log-warn").count(), 1);
  await page
    .getByRole("button", { name: "Перенос строк", exact: true })
    .click();
  assert.ok(
    await page
      .locator("#log-scroll")
      .evaluate((el) => el.classList.contains("wrap-lines")),
  );
});
test("меню сборки сохраняет все действия Kotlin",async()=>{await openBuild();await page.locator('.instance-header [data-action=build-menu]').click();const menu=page.locator('.build-actions');await menu.waitFor();for(const label of ['Настройки','Изменить иконку','Изменить группу','Экспортировать в .mrpack','Дублировать','Изменить версию Minecraft','Играть','Логи','Скачать файлы','Переустановить','Удалить…'])assert.equal(await menu.getByRole('button',{name:label,exact:true}).count(),1);await menu.getByRole('button',{name:'Настройки',exact:true}).click();await page.locator('.build-settings-window form').waitFor();assert.equal(page.context().pages().length,1);assert.equal(await page.evaluate(()=>window.calls.some(x=>x.args?.request?.op==='instanceWindow')),false);});
test("статистика показывает подписи календаря и оформленные подсказки", async () => {
  await page.locator("#navigation [data-page=activity]").click();
  await page.locator(".activity-view").waitFor();
  assert.equal(await page.locator(".stat-icon").count(), 7);
  assert.ok((await page.locator(".calendar-months span").count()) > 6);
  assert.equal(await page.locator(".calendar-legend i").count(), 6);
  await page.locator(".bar-day").first().hover();
  await page.getByRole("tooltip").waitFor();
  assert.equal(await page.locator(".bar-day[title]").count(), 0);
});
async function buildDialog(kind) {
  await openBuild();
  if(kind==='settings')await page.locator('.instance-header [data-action=build-settings]').click();
  else {await page.locator('.instance-header [data-action=build-menu]').click();await page.locator(`.build-actions [data-kind=${kind}]`).click();}
  await page.locator('.build-dialog').waitFor();
}
test("избранное сохраняется сразу при остановленной очереди без перезагрузки экрана",async()=>{
  await openBuild();await page.evaluate(()=>{state.paused=true;window.oldTable=document.querySelector('.content-table');});
  await page.locator('.instance-header [data-action=build-menu]').click();await page.getByRole('button',{name:'В избранное',exact:true}).click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='saveOptions'&&x.args.request.options.favorite===true));
  assert.equal(await page.evaluate(()=>state.queue.length),0);
  assert.equal(await page.evaluate(()=>document.querySelector('.content-table')===window.oldTable),true);
  assert.doesNotMatch(await page.locator('#toast').innerText(),/очеред/);
});
test("ошибка немедленного сохранения не добавляет запись в загрузки",async()=>{
  await openBuild();await page.evaluate(()=>{const original=window.__TAURI__.core.invoke;window.__TAURI__.core.invoke=(command,args)=>args?.request?.op==='saveOptions'?Promise.reject(Error('Ошибка записи')):original(command,args);});
  await page.locator('.instance-header [data-action=build-menu]').click();await page.getByRole('button',{name:'В избранное',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#toast').textContent==='Ошибка записи');
  assert.equal(await page.evaluate(()=>state.queue.length),0);
  assert.equal(await page.evaluate(()=>state.data.builds[0].options.favorite||false),false);
});
test("настройки сборки сохраняют память JVM и окно в Tauri",async()=>{
  await buildDialog('settings');const d=page.locator('.build-settings-window');await d.locator('[data-build-tab="1"]').click();
  await d.locator('[data-build-toggle=memory]').click();await d.locator('[name=memoryMb]').fill('6144');
  await d.locator('[data-build-toggle=args]').click();await d.locator('[name=jvmArgs]').fill('-Dtitle="My game"');
  await d.locator('[data-build-tab="2"]').click();await d.locator('[data-build-toggle=window]').click();
  await d.locator('[name=windowWidth]').fill('1920');await d.locator('[name=windowHeight]').fill('200');await d.locator('[data-build-fullscreen=true]').click();
  await d.getByRole('button',{name:'Применить настройки',exact:true}).click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='saveOptions'&&x.args.request.options.memoryMb===6144));
  const options=await page.evaluate(()=>window.calls.find(x=>x.args?.request?.options?.memoryMb===6144).args.request.options);
  assert.equal(options.jvmArgs,'-Dtitle="My game"');assert.equal(options.windowHeight,200);assert.equal(options.fullscreen,true);assert.equal(options.javaPath,null);
  assert.equal(await d.isVisible(),true);assert.equal(page.context().pages().length,1);
  await d.getByRole('button',{name:'Использовать общие настройки',exact:true}).click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='saveOptions'&&x.args.request.options.memoryMb===null));
  assert.equal(await d.locator('[name=memoryMb]').inputValue(),'4096');
  assert.equal(await page.evaluate(()=>window.calls.filter(x=>x.args?.request?.op==='launcherState').length),1);
  const out=path.resolve(__dirname,'../test-results');await fs.mkdir(out,{recursive:true});await page.screenshot({path:path.join(out,'tauri-build-settings.png')});
});
test("версия Loader и имя изменяются в настройках без нового окна",async()=>{
  await buildDialog('settings');const d=page.locator('.build-settings-window');await d.locator('[name=title]').fill('Моя сборка');await d.locator('[data-build-command=rename]').click();
  await page.waitForFunction(()=>document.querySelector('.instance-header h1').textContent==='Моя сборка');
  await d.locator('[data-build-command=latest-loader]').click();await d.locator('[data-build-command=save-loader]').click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.options?.loaderVersion==='0.16.14'));
  assert.equal(await page.locator('#main').isVisible(),true);assert.equal(page.context().pages().length,1);
});
test("редактор иконок содержит фон случайный выбор импорт и сброс",async()=>{
  await buildDialog('icon');const d=page.locator('.build-icon-editor');assert.equal(await d.locator('[data-symbol]').count(),8);assert.equal(await d.locator('[data-background]').count(),7);
  await d.locator('[data-build-command=random]').click();assert.equal(await d.locator('[data-symbol].selected').count(),1);assert.equal(await d.locator('[data-background].selected').count(),1);
  await page.evaluate(()=>window.savedContent=document.querySelector('.content-table'));
  await d.locator('[data-build-command=pick]').click();await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='buildPickIcon'));
  await d.locator('[data-symbol=fox]').click();await d.locator('[data-background=amber]').click();await d.locator('[data-build-command=save]').click();
  await d.waitFor({state:'detached'});const request=await page.evaluate(()=>window.calls.find(x=>x.args?.request?.op==='buildSaveIcon').args.request);
  assert.equal(request.options.symbol,'fox');assert.equal(request.options.background,'amber');assert.equal(request.options.image,null);
  assert.equal(await page.evaluate(()=>document.querySelector('.content-table')===window.savedContent),true);
  await page.locator('.instance-header [data-action=build-menu]').click();await page.locator('.build-actions [data-kind=icon]').click();await page.locator('.build-icon-editor [data-build-command=reset]').click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='buildSaveIcon'&&x.args.request.options.reset===true));
});
test("группы создаются переименовываются назначаются и удаляются в Tauri",async()=>{
  await buildDialog('groups');const d=page.locator('.build-dialog');await d.locator('.build-group-create input').fill('Тестовая');await d.locator('.build-group-create button').click();
  await d.locator('[data-group-name=group-new]').waitFor();await d.locator('[data-group-name=group-new]').fill('Переименована');await d.locator('[data-build-command=rename]').click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='buildGroups'&&x.args.request.options.action==='rename'));
  await d.locator('[data-build-command=assign]').click();await d.waitFor({state:'detached'});
  assert.equal(await page.evaluate(()=>window.calls.some(x=>x.args?.request?.options?.groupId==='group-new')),true);
  await page.locator('.instance-header [data-action=build-menu]').click();await page.locator('.build-actions [data-kind=groups]').click();await page.locator('.build-dialog [data-build-command=remove]').click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='buildGroups'&&x.args.request.options.action==='remove'));
});
test("экспорт предлагает выбор миров и файлов до сохранения",async()=>{
  await buildDialog('export');const d=page.locator('.build-dialog');await d.locator('[data-export-index="2"]').uncheck();await d.locator('[data-build-command=save]').click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='buildExport'));
  const request=await page.evaluate(()=>window.calls.find(x=>x.args?.request?.op==='buildExport').args.request);
  assert.deepEqual(request.options.selected.sort(),['config','mods']);assert.equal(request.file,'C:\\Exports\\test.mrpack');assert.equal(page.context().pages().length,1);
});
test("экспорт отдельно подтверждает замену существующего файла",async()=>{
  await page.evaluate(()=>window.exportExists=true);await buildDialog('export');const d=page.locator('.build-dialog');await d.locator('[data-export-index="0"]').waitFor();await d.locator('[data-build-command=save]').click();
  await d.getByRole('button',{name:'Заменить файл',exact:true}).waitFor();assert.equal(await page.evaluate(()=>window.calls.some(x=>x.args?.request?.op==='buildExport')),false);
  await d.getByRole('button',{name:'Заменить файл',exact:true}).click();await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='buildExport'&&x.args.request.options.overwrite===true));
});
test("копирование скачивание и переустановка используют встроенную очередь",async()=>{
  await buildDialog('duplicate');const d=page.locator('.build-dialog');await d.locator('[name=title]').fill('Независимая копия');await d.getByRole('button',{name:'Создать копию',exact:true}).click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='duplicate'&&x.args.request.title==='Независимая копия'));
  for(const [kind,op]of [['download','buildDownload'],['reinstall','buildReinstall']]){await page.waitForFunction(()=>state.running===false);await page.locator('.instance-header [data-action=build-menu]').click();await page.locator(`.build-actions [data-kind=${kind}]`).click();await page.waitForFunction(op=>window.calls.some(x=>x.args?.request?.op===op),op);}
  assert.equal(page.context().pages().length,1);assert.equal(await page.evaluate(()=>window.calls.some(x=>x.args?.request?.op==='instanceWindow')),false);
});
test("ошибка миграции возвращает проверку и позволяет отключить мод",async()=>{
  await page.evaluate(()=>window.failMigration=true);await buildDialog('migrate');let d=page.locator('.build-dialog');
  await d.locator('[data-build-command=save]:not([disabled])').click();await d.locator('.build-migration-mod').first().waitFor();
  await d.locator('[data-keep=all]').click();await d.locator('[data-build-command=save]').click();
  await page.locator('[data-build-command=disable-failed]').waitFor();assert.equal(page.context().pages().length,1);
  await page.locator('[data-build-command=disable-failed]').click();
  await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='buildMigrationApply'&&x.args.request.options.disableFailed==='sodium.jar'));
});
test("удаление сборки ждёт подтверждения в Tauri",async()=>{
  await buildDialog('delete');const d=page.locator('.build-dialog');await d.locator('[data-build-command=delete]:not([disabled])').waitFor();
  assert.equal(await page.evaluate(()=>window.calls.some(x=>x.args?.request?.op==='buildDelete')),false);
  await d.locator('[data-build-command=delete]').click();await page.locator('#main #build-list').waitFor();
  assert.equal(await page.locator('.build-row').count(),1);assert.equal(page.context().pages().length,1);
});
test("смена языка не оставляет старый перевод в верхней панели", async () => {
  await page.locator("#settings-button").click();
  await page.locator("[data-action=settings-tab][data-tab=language]").click();
  await page.locator("[data-action=language][data-id=ES]").click();
  await page.waitForFunction(() =>
    document.getElementById("game-status").textContent.startsWith("ES:"),
  );
  await page.locator("[data-action=language][data-id=RU]").click();
  await page.waitForFunction(
    () =>
      document.getElementById("game-status").textContent ===
      "● Нет активных сборок",
  );
});

test("шапка сборки доступна до окончания чтения модов", async () => {
  await page.evaluate(() => (window.deferContent = true));
  await page.locator("#navigation [data-page=library]").click();
  await page.locator(".open-build").first().click();
  await page.locator(".instance-header").waitFor();
  assert.equal(await page.locator(".content-table").count(), 0);
  await page.locator("#main [data-tab=screenshots]").click();
  await page.locator("#shot-content").waitFor();
  await page.waitForFunction(() => !!window.finishContent);
  await page.evaluate(() => window.finishContent());
  await page.locator("#main [data-tab=content]").click();
  await page.locator(".content-table").waitFor();
  assert.equal(
    await page.evaluate(
      () =>
        window.calls.filter(
          (x) => x.command === "request" && x.args.request.op === "content",
        ).length,
    ),
    1,
  );
});

test("переключение аккаунта сразу меняет футер и не перечитывает весь лаунчер", async () => {
  await page.evaluate(() => (window.deferAccount = true));
  await page.locator("#account-button").click();
  await page
    .locator("#account-menu [data-action=select-account][data-id=two]")
    .click();
  assert.equal(
    await page.locator("#account-name").textContent(),
    "MicrosoftPlayer",
  );
  assert.equal(await page.locator("#account-menu").isVisible(), false);
  assert.equal(
    await page.locator("#account-button").getAttribute("aria-busy"),
    "true",
  );
  assert.equal(
    await page.evaluate(
      () =>
        window.calls.filter(
          (x) =>
            x.command === "request" && x.args.request.op === "launcherState",
        ).length,
    ),
    1,
  );
  await page.waitForFunction(() => !!window.commitAccount);
  await page.evaluate(() => window.commitAccount());
  await page.waitForFunction(
    () =>
      document.getElementById("account-button").getAttribute("aria-busy") ===
      "false",
  );
  assert.equal(
    await page.evaluate(
      () =>
        window.calls.filter(
          (x) =>
            x.command === "request" && x.args.request.op === "launcherState",
        ).length,
    ),
    1,
  );
});
test("ошибка сохранения выбора возвращает прежний аккаунт", async () => {
  await page.evaluate(() => (window.rejectAccount = true));
  await page.locator("#account-button").click();
  await page
    .locator("#account-menu [data-action=select-account][data-id=two]")
    .click();
  await page.locator("#toast.error").waitFor();
  assert.equal(await page.locator("#account-name").textContent(), "AlexPlayer");
});
test("запуск ждёт подтверждения сохранённого аккаунта", async () => {
  await page.evaluate(() => (window.deferAccount = true));
  await page.locator("#account-button").click();
  await page
    .locator("#account-menu [data-action=select-account][data-id=two]")
    .click();
  await page.locator(".hero [data-action=launch]").click();
  assert.equal(
    await page.evaluate(
      () =>
        window.calls.filter(
          (x) => x.command === "request" && x.args.request.op === "launch",
        ).length,
    ),
    0,
  );
  await page.waitForFunction(() => !!window.commitAccount);
  await page.evaluate(() => window.commitAccount());
  await page.waitForFunction(() =>
    window.calls.some(
      (x) => x.command === "request" && x.args.request.op === "launch",
    ),
  );
});
async function openSkins() {
  await page.locator('#account-button').click();
  await page.locator('#account-menu [data-action=edit-account][data-id=two]').click();
  await page.locator('#skin-page .skin-card[data-id=current]').waitFor();
  await page.waitForFunction(()=>document.querySelector('.skin-live-preview canvas')?.dataset.ready==='true');
}
test("ошибка библиотеки скинов повторяется в той же странице",async()=>{
  await page.evaluate(()=>window.failSkinStateOnce=true);await page.locator('#account-button').click();await page.locator('#account-menu [data-action=edit-account][data-id=two]').click();
  await page.locator('.skin-error').waitFor();await page.locator('.skin-error [data-skin=refresh]').click();await page.locator('.skin-card[data-id=current]').waitFor();
  assert.equal(await page.locator('.skin-error').isVisible(),false);assert.equal(page.context().pages().length,1);
});
test("скины открываются внутри Tauri без отдельного окна",async()=>{
  await openSkins();assert.equal(await page.locator('#main').isVisible(),true);
  assert.equal(await page.locator('#crumb').innerText(),'Скины');
  assert.equal(await page.evaluate(()=>window.calls.some(x=>x.args?.request?.op==='accountSkinWindow')),false);
  assert.equal(page.context().pages().length,1);
  assert.equal(await page.locator('.skin-card').count(),4);
  const out=path.resolve(__dirname,'../test-results');await fs.mkdir(out,{recursive:true});await page.screenshot({path:path.join(out,'tauri-skins.png')});
});
test("каталог скинов фильтруется и разворачивает наборы без перезагрузки",async()=>{
  await openSkins();await page.getByRole('searchbox',{name:'Поиск скинов или набора'}).fill('праздничный');
  assert.equal(await page.locator('.skin-card').count(),1);assert.equal(await page.locator('.skin-card').getAttribute('data-id'),'preset:event');
  await page.getByRole('searchbox',{name:'Поиск скинов или набора'}).fill('');
  await page.getByRole('button',{name:'Сохранённые скины',exact:true}).click();
  assert.equal(await page.locator('.skin-card[data-id=saved-one]').count(),0);
  assert.equal(await page.evaluate(()=>window.calls.filter(x=>x.args?.request?.op==='launcherState').length),1);
});
test("редактор хранит модель и плащ до явного применения",async()=>{
  await openSkins();await page.locator('[data-skin=edit]').click();const dialog=page.locator('.skin-editor-dialog');await dialog.waitFor();
  await dialog.locator('[name=name]').fill('Мой сохранённый скин');await dialog.locator('[data-model=SLIM]').click();await dialog.locator('[data-cape=cape-1]').click();
  assert.equal(await dialog.locator('[data-cape=cape-1]').getAttribute('aria-checked'),'true');
  assert.equal(await dialog.locator('[data-cape=""]').getAttribute('aria-checked'),'false');
  await page.screenshot({path:path.resolve(__dirname,'../test-results/tauri-skin-editor.png')});
  await dialog.getByRole('button',{name:'Добавить скин',exact:true}).click();await dialog.waitFor({state:'detached'});
  assert.equal(await page.evaluate(()=>window.calls.some(x=>x.args?.request?.op==='accountSkinApply')),false);
  assert.equal(await page.locator('.skin-card[data-id=saved-new]').getAttribute('aria-selected'),'true');
  await page.locator('[data-skin=apply]').click();await page.waitForFunction(()=>window.calls.some(x=>x.args?.request?.op==='accountSkinApply'));
  const request=await page.evaluate(()=>window.calls.find(x=>x.args?.request?.op==='accountSkinApply').args.request);
  assert.equal(request.kind,'SLIM');assert.equal(request.options.capeId,'cape-1');assert.equal(request.options.changeCape,true);
});
test("скин переставляется и удаляется после подтверждения",async()=>{
  await openSkins();await page.locator('.skin-card[data-id=saved-one] [data-skin=menu]').click();await page.getByRole('button',{name:'Вправо',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.skin-card[data-id=saved-two]')?.nextElementSibling?.dataset.id==='saved-one');
  await page.locator('.skin-card[data-id=saved-one] [data-skin=menu]').click();await page.locator('[data-skin-menu=delete]').click();
  assert.equal(await page.evaluate(()=>window.calls.some(x=>x.args?.request?.op==='accountSkinRemove')),false);
  await page.locator('#modal [data-action=confirm]').click();await page.locator('.skin-card[data-id=saved-one]').waitFor({state:'detached'});
});
test("статистика показывает даты выбранной недели",async()=>{
  await page.locator('#navigation [data-page=activity]').click();await page.getByRole('heading',{name:'Время по дням'}).waitFor();
  const label=()=>page.locator('.week-controls').locator('..').locator('small').first().innerText();
  const current=await label();assert.match(current,/\d.*—.*\d/);await page.locator('[data-action=week-prev]').click();const previous=await label();assert.notEqual(current,previous);assert.doesNotMatch(previous,/назад/);
});
test("3D-перенос сохраняет слои, модель, плащ и движение",async()=>{
  const result=await page.evaluate(async()=>{
    const canvas=document.createElement('canvas');canvas.width=256;canvas.height=400;
    const scene=new AWSkinPreview.Scene(canvas);await scene.setSkin(window.testSkinPng);
    const counts=()=>{const copy=document.createElement('canvas');copy.width=256;copy.height=400;const ctx=copy.getContext('2d');ctx.drawImage(canvas,0,0);const data=ctx.getImageData(0,0,256,400).data;let red=0,green=0;for(let i=0;i<data.length;i+=4){if(data[i+3]>200&&data[i]>150)red++;if(data[i+3]>200&&data[i+1]>150&&data[i]<100)green++;}return {red,green};};
    scene.draw({yaw:0,layers:false});const base=canvas.toDataURL(),baseColors=counts();
    scene.draw({yaw:0,layers:true});const outer=canvas.toDataURL(),outerColors=counts();
    scene.draw({yaw:0,layers:false,model:'SLIM'});const slim=canvas.toDataURL();
    scene.draw({yaw:90,layers:false});const rotated=canvas.toDataURL();
    scene.draw({yaw:0,layers:false,time:2});const idle=canvas.toDataURL();
    scene.draw({yaw:0,layers:false,wave:.5});const wave=canvas.toDataURL();
    scene.draw({yaw:180,layers:false});const plainRear=canvas.toDataURL();
    const capeCanvas=document.createElement('canvas');capeCanvas.width=64;capeCanvas.height=32;const capeContext=capeCanvas.getContext('2d');capeContext.fillStyle='#2850f5';capeContext.fillRect(0,0,64,32);
    await scene.setCape(capeCanvas.toDataURL());scene.draw({yaw:180,layers:false});const cape=plainRear!==canvas.toDataURL();
    scene.destroy();return {baseColors,outerColors,layers:outer!==base,slim:slim!==base,rotated:rotated!==base,idle:idle!==base,wave:wave!==base,cape};
  });
  assert.ok(result.baseColors.red>1000);assert.ok(result.outerColors.green>1000);
  for(const key of ['layers','slim','rotated','idle','wave','cape'])assert.equal(result[key],true,key);
});
test("PNG импортируется в редактор без изменения профиля Minecraft",async()=>{
  await openSkins();const png=await page.evaluate(()=>window.testSkinPng);
  await page.locator('.skin-file-input').setInputFiles({name:'Imported.png',mimeType:'image/png',buffer:Buffer.from(png.split(',')[1],'base64')});
  const dialog=page.locator('.skin-editor-dialog');await dialog.waitFor();assert.equal(await dialog.locator('[name=name]').inputValue(),'Imported');
  assert.equal(await page.evaluate(()=>window.calls.some(x=>x.args?.request?.op==='accountSkinApply')),false);
  await dialog.getByRole('button',{name:'Отменить',exact:true}).click();await dialog.waitFor({state:'detached'});
  await page.locator('.skin-file-input').setInputFiles({name:'Invalid.png',mimeType:'image/png',buffer:Buffer.from('<svg></svg>')});
  await page.locator('.skin-error').waitFor();assert.equal(await page.locator('.skin-editor-dialog').count(),0);
});
test("старые PNG зеркалят конечности и скрывают непрозрачный старый верхний слой",async()=>{
  const result=await page.evaluate(async()=>{
    const c=document.createElement('canvas');c.width=64;c.height=32;const ctx=c.getContext('2d');ctx.fillStyle='#112233';ctx.fillRect(0,0,64,32);
    for(let x=4;x<8;x++){ctx.fillStyle=`rgb(170,51,${x})`;ctx.fillRect(x,20,1,12);}
    const image=new Image();image.src=c.toDataURL();await image.decode();const out=AWSkinPreview.normalize(image).getContext('2d');
    return {a:[...out.getImageData(20,52,1,1).data],b:[...out.getImageData(23,52,1,1).data],hat:out.getImageData(40,8,1,1).data[3],overlay:out.getImageData(20,36,1,1).data[3]};
  });
  assert.deepEqual(result.a,[170,51,7,255]);assert.deepEqual(result.b,[170,51,4,255]);assert.equal(result.hat,0);assert.equal(result.overlay,0);
});
test("удаление обновляет только аккаунты и подтверждается отдельно", async () => {
  await page.locator("#account-button").click();
  await page
    .locator("#account-menu [data-action=remove-account][data-id=two]")
    .click();
  assert.equal(
    await page.evaluate(() =>
      window.calls.some(
        (x) => x.command === "request" && x.args.request.op === "accountRemove",
      ),
    ),
    false,
  );
  await page.locator("[data-action=confirm]").click();
  await page.waitForFunction(() =>
    window.calls.some(
      (x) => x.command === "request" && x.args.request.op === "accountRemove",
    ),
  );
  await page.locator("#account-button").click();
  await page.waitForFunction(
    () =>
      document.querySelectorAll("#account-menu [data-action=select-account]")
        .length === 1,
  );
  assert.equal(
    await page.evaluate(
      () =>
        window.calls.filter(
          (x) =>
            x.command === "request" && x.args.request.op === "launcherState",
        ).length,
    ),
    1,
  );
});
test("индикаторы языков имеют одинаковые контуры и список прокручивается отдельно", async () => {
  await page.locator("#settings-button").click();
  await page.locator("[data-action=settings-tab][data-tab=language]").click();
  assert.ok((await page.locator(".language-radio").count()) > 10);
  assert.equal(
    await page
      .locator("#language-list")
      .evaluate((el) => /[◉○]/.test(el.textContent)),
    false,
  );
  const radio = page.locator("[data-action=language][data-id=RU]");
  assert.equal(await radio.getAttribute("aria-checked"), "true");
  assert.equal(
    await radio
      .locator(".language-radio")
      .evaluate((el) => el.getBoundingClientRect().width),
    20,
  );
  assert.ok(
    await page
      .locator("#language-list")
      .evaluate((el) => el.scrollHeight > el.clientHeight),
  );
});
test("единственный снимок располагается сразу под поиском", async () => {
  await page.locator("#navigation [data-page=library]").click();
  await page
    .locator(".open-build")
    .filter({ hasText: "Вторая сборка" })
    .click();
  await page.locator(".content-table").waitFor();
  await page.locator("#main [data-tab=screenshots]").click();
  await page.locator(".shot").waitFor();
  const gap = await page.evaluate(
    () =>
      document.getElementById("shot-count").getBoundingClientRect().top -
      document.getElementById("shot-query").getBoundingClientRect().bottom,
  );
  assert.ok(gap >= 0 && gap < 30, `Gap: ${gap}`);
});
test("карточка времени открывает активность мышью и клавиатурой", async () => {
  await page.locator("#home-activity").click();
  await page
    .getByRole("heading", { name: "Активность", exact: true })
    .waitFor();
  await page.locator("#navigation [data-page=home]").click();
  await page.locator("#home-activity").focus();
  await page.keyboard.press("Enter");
  await page
    .getByRole("heading", { name: "Активность", exact: true })
    .waitFor();
});

test('launcher update stays actionable in titlebar and about, reports progress and closes after handoff', async () => {
  await page.waitForFunction(() => document.querySelector('#launcher-update button')?.textContent === 'Обновить до 1.0.53');
  await page.locator('#settings-button').click();
  await page.locator('[data-action=settings-tab][data-tab=about]').click();
  assert.equal(await page.locator('#about-update [data-action=install-update]').textContent(), 'Обновить до 1.0.53');
  await page.locator('#about-update [data-action=install-update]').click();
  await page.waitForFunction(() => document.querySelector('#launcher-update button')?.textContent === 'Скачиваю 42%');
  assert.equal(await page.locator('#about-update [data-action=install-update]').isDisabled(), true);
  assert.equal(await page.evaluate(() => window.calls.filter(c => c.args?.request?.op === 'installUpdate').length), 1);
  await page.evaluate(() => window.finishUpdate());
  await page.waitForFunction(() => window.closedForUpdate);
});

test('failed update keeps a retry button and does not close the launcher', async () => {
  await page.waitForFunction(() => document.querySelector('#launcher-update button'));
  await page.evaluate(() => window.rejectUpdate = true);
  await page.locator('#launcher-update button').click();
  await page.waitForFunction(() => document.querySelector('#launcher-update button')?.textContent === 'Не обновилось · ещё раз');
  assert.equal(await page.evaluate(() => !!window.closedForUpdate), false);
  await page.evaluate(() => window.rejectUpdate = false);
  await page.locator('#launcher-update button').click();
  await page.waitForFunction(() => typeof window.finishUpdate === 'function');
  await page.evaluate(() => window.finishUpdate());
  await page.waitForFunction(() => window.closedForUpdate);
});

test('launch starts outside a paused downloads queue and restores controls after failure', async () => {
  await page.evaluate(() => state.paused = true);
  await page.evaluate(() => window.deferLaunch = true);
  const play = page.locator('.hero [data-action=launch]');
  await play.click();
  await page.waitForFunction(() => typeof window.finishLaunch === 'function');
  assert.equal(await play.isDisabled(), true);
  assert.equal(await page.locator('#game-status').textContent(), 'Подготовка к запуску…');
  assert.equal(await page.locator('#toast').textContent().then(s => s.includes('Добавлено в очередь')), false);
  await page.evaluate(() => window.finishLaunch());
  await page.waitForFunction(() => !document.querySelector('.hero [data-action=launch]').disabled);
  await page.locator('#navigation [data-page=downloads]').click();
  assert.equal(await page.evaluate(() => state.queue.length), 0);
  await page.locator('#navigation [data-page=home]').click();
  await page.evaluate(() => { window.deferLaunch = false; window.rejectLaunch = true; });
  await page.locator('.hero [data-action=launch]').click();
  await page.waitForFunction(() => document.querySelector('#toast').textContent === 'Не удалось запустить игру');
  assert.equal(await page.locator('.hero [data-action=launch]').isDisabled(), false);
  assert.equal(await page.evaluate(() => state.queue.length), 0);
});

test('only downloads wait while the download queue is paused', async () => {
  const outcome = await page.evaluate(async () => {
    state.paused = true;
    for (const op of ['toggle', 'importFiles', 'remove', 'saveOptions', 'duplicate', 'buildExport']) {
      await enqueue(op, { op, target: state.data.builds[0].key });
    }
    for (const op of ['install', 'updateItem', 'buildDownload', 'buildReinstall', 'buildMigrationApply']) {
      await enqueue(op, { op, target: state.data.builds[0].key });
    }
    return {
      sent: window.calls.map(call => call.args?.request?.op),
      queued: state.queue.map(item => [item.request.op, item.status]),
    };
  });
  for (const op of ['toggle', 'importFiles', 'remove', 'saveOptions', 'duplicate', 'buildExport']) {
    assert.ok(outcome.sent.includes(op), op);
  }
  assert.deepEqual(outcome.queued, ['install', 'updateItem', 'buildDownload', 'buildReinstall', 'buildMigrationApply'].map(op => [op, 'waiting']));
  assert.ok(outcome.queued.every(([op]) => !outcome.sent.includes(op)));
});

test('Microsoft sign-in displays its code while downloads are paused', async () => {
  await page.evaluate(() => {
    state.paused = true;
    actions.login().catch(fail);
  });
  await page.locator('#modal').getByRole('heading', { name: 'AW-TEST' }).waitFor();
  assert.equal(await page.evaluate(() => state.queue.length), 0);
  await page.evaluate(() => window.finishMicrosoftLogin());
  await page.locator('#modal').waitFor({ state: 'hidden' });
});
