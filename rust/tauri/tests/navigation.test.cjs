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
      window.calls = [];
      window.deferScreenshots = false;
      window.rejectToggle = false;
      const invoke = async (command, args) => {
        window.calls.push({ command, args: JSON.parse(JSON.stringify(args)) });
        if (command === "request") {
          const r = args.request;
          if (r.op === "launcherState") return structuredClone(snapshot);
          if(r.op==='accountSkinWindow'||r.op==='instanceWindow')return structuredClone(snapshot.accounts);
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
          if (r.op === "launch") return { pid: 123, message: "Игра запущена" };
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
            close: async () => {},
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
test("меню сборки сохраняет все действия Kotlin",async()=>{await openBuild();await page.locator('.instance-header [data-action=build-menu]').click();const menu=page.locator('.build-actions');await menu.waitFor();for(const label of ['Настройки','Изменить иконку','Изменить группу','Экспортировать в .mrpack','Дублировать','Изменить версию Minecraft','Играть','Логи','Скачать файлы','Переустановить','Удалить…'])assert.equal(await menu.getByRole('button',{name:label,exact:true}).count(),1);await menu.getByRole('button',{name:'Настройки',exact:true}).click();await page.waitForFunction(()=>window.calls.some(x=>x.command==='request'&&x.args.request.op==='instanceWindow'&&x.args.request.kind==='settings'));});
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
test("редактор аккаунта открывает полный исходный раздел скинов",async()=>{await page.locator('#account-button').click();assert.equal(await page.locator('#account-menu [data-action=remove-account]').count(),2);const edit=page.locator('#account-menu [data-action=edit-account][data-id=two]');assert.equal(await edit.locator('svg.material-icon path').count(),1);await edit.click();await page.waitForFunction(()=>window.calls.some(x=>x.command==='request'&&x.args.request.op==='accountSkinWindow'&&x.args.request.file==='two'));});
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
