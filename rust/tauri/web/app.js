"use strict";
const $ = (s, root = document) => root.querySelector(s);
const esc = (v) =>
  String(v ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const icon = (name) => `<img class="icon" src="assets/nav/${name}.png" alt="">`;
const btn = (text, action, cls = "", data = "") =>
  `<button class="${cls}" data-action="${action}" ${data}>${text}</button>`;
const searchField = (id, hint) =>
  `<div class="search">${icon("search")}<input id="${id}" type="search" placeholder="${esc(hint)}" aria-label="${esc(hint)}"></div>`;
const ms = (value) => {
  const minutes = Math.floor((value || 0) / 60000);
  return minutes >= 60
    ? `${Math.floor(minutes / 60)} ч${minutes % 60 ? " " + (minutes % 60) + " мин" : ""}`
    : `${minutes} мин`;
};
const date = (value) =>
  value
    ? new Intl.DateTimeFormat("ru", { day: "numeric", month: "short" }).format(
        value,
      )
    : "Ещё не запускалась";
const names = {
  home: "Играть",
  library: "Сборки",
  catalog: "Каталог",
  downloads: "Загрузки",
  screenshots: "Скриншоты",
  activity: "Активность",
  notices: "Уведомления",
  instance: "Сборка",
  project: "Проект",
};
const tabs = {
  content: "Контент",
  files: "Файлы",
  worlds: "Миры",
  log: "Журнал",
  statistics: "Статистика",
  screenshots: "Скриншоты",
  history: "История",
};
const kinds = {
  modpack: "Модпаки",
  mod: "Моды",
  shader: "Шейдеры",
  resourcepack: "Ресурспаки",
  datapack: "Датапаки",
};
const state = {
  data: { builds: [], accounts: [], settings: {}, notices: { items: [] } },
  route: { page: "home" },
  back: [],
  forward: [],
  queue: [],
  paused: false,
  running: false,
  serial: 0,
  epoch: 0,
  content: [],
  shots: [],
  shotIndex: null,
  stats: null,
  catalog: {
    kind: "modpack",
    source: false,
    query: "",
    sort: "downloads",
    offset: 0,
    target: "",
    installed: false,
  },
  languages: [],
  settingsTab: "accounts",
  selectedContent: new Set(),
  filters: {},
  filePath: "",
  accountMode: "MICROSOFT",
};
const T = window.__TAURI__;
const invoke = (command, args = {}) =>
  T
    ? T.core.invoke(command, args)
    : Promise.reject(new Error("Открой приложение AWLauncher через Tauri"));
async function guarded(promise) {
  const epoch = state.epoch;
  const result = await promise;
  if (epoch !== state.epoch) throw new Error("Раздел уже закрыт");
  return result;
}
const selected = () =>
  state.data.builds.find((b) => b.key === state.route.target);
const current = () =>
  state.data.builds.find(
    (b) => b.key === `build:${state.data.settings.lastBuildId}`,
  ) ||
  state.data.builds.find(
    (b) => b.key === `pack:${state.data.settings.lastPack}`,
  ) ||
  state.data.builds[0];
const account = () => state.data.accounts.find((a) => a.selected);
let toastTimer;
function toast(message, error = false) {
  const node = $("#toast");
  node.textContent = String(message);
  node.classList.toggle("error", error);
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (node.hidden = true), error ? 10000 : 5000);
}
function fail(error) {
  toast(error?.message || error, true);
}
async function rpc(request, onEvent = () => {}, id = `read-${++state.serial}`) {
  const events = new T.core.Channel();
  events.onmessage = onEvent;
  return invoke("request", { id, request, events });
}
async function refresh() {
  state.data = await rpc({ op: "launcherState" });
  if (state.accountSelection)
    state.data.accounts.forEach(
      (a) => (a.selected = a.id === state.accountSelection.id),
    );
  applyTheme();
  renderChrome();
}
function applyTheme() {
  let theme = state.data.settings.themeMode || "SYSTEM";
  if (theme === "SYSTEM")
    theme = matchMedia("(prefers-color-scheme: light)").matches
      ? "LIGHT"
      : "DARK";
  document.documentElement.dataset.theme = theme;
}
matchMedia("(prefers-color-scheme: light)").addEventListener(
  "change",
  applyTheme,
);
function renderChrome() {
  $("#version").textContent = `v${state.data.version || "1.0.50"}`;
  renderAccountSummary();
  if (!$("#navigation").children.length)
    $("#navigation").innerHTML = [
      ["home", "play", "Играть"],
      ["library", "layers", "Мои сборки"],
      ["catalog", "search", "Каталог"],
      ["downloads", "download", "Загрузки"],
      ["caption"],
      ["friends", "friends", "Друзья"],
      ["screenshots", "image", "Скриншоты"],
      ["activity", "activity", "Активность"],
      ["notices", "notices", "Уведомления"],
    ]
      .map(([page, i, label]) =>
        page === "caption"
          ? '<span class="caption">ДРУГОЕ</span>'
          : `<button data-page="${page}" class="${page === (state.route.page === "instance" ? "library" : state.route.page === "project" ? "catalog" : state.route.page) ? "active" : ""}" ${page === "friends" ? 'disabled title="Раздел не перенесён в версии 1.0.50"' : ""}>${icon(i)}<span>${label}</span></button>`,
      )
      .join("");
  $("#crumb").textContent =
    state.route.page === "instance"
      ? selected()?.name || "Сборка"
      : names[state.route.page];
  $("#back").disabled = !state.back.length;
  $("#forward").disabled = !state.forward.length;
  const page =
    state.route.page === "instance"
      ? "library"
      : state.route.page === "project"
        ? "catalog"
        : state.route.page;
  document
    .querySelectorAll("#navigation [data-page]")
    .forEach((el) => el.classList.toggle("active", el.dataset.page === page));
  $("#game-status").textContent = state.games?.length
    ? "● Игра запущена"
    : "● Нет активных сборок";
  translateVisible($(".titlebar")).catch(() => {});
  translateVisible($("#sidebar")).catch(() => {});
}
function navigate(route, history = true) {
  if (history) {
    state.back.push({ ...state.route });
    state.forward = [];
  }
  state.route = typeof route === "string" ? { page: route } : route;
  state.filePath = "";
  closeAccountMenu();
  render().catch(fail);
}
async function render({ refresh: force = false } = {}) {
  const main = $("#main");
  if (activeView && main.firstElementChild === activeView.node) {
    activeView.scroll = main.scrollTop;
    activeView.data = captureViewData();
  }
  const epoch = ++state.epoch,
    key = viewKey();
  closePopover();
  hideTooltip();
  renderChrome();
  main.dataset.page = state.route.page;
  let view = screenViews.get(key);
  if (force) {
    if (
      state.route.page === "instance" &&
      (state.route.tab || "content") === "content"
    )
      contentReads.delete(state.route.target);
    if (view) disposeImages(view.node);
    screenViews.delete(key);
    view = null;
  }
  if (view) {
    Object.assign(state, view.data);
    activeView = view;
    main.replaceChildren(view.node);
    main.scrollTop = view.scroll || 0;
    view.used = Date.now();
    finishView(view.node);
    animateView(view.node);
    return;
  }
  const node = document.createElement("div");
  node.className = "page-view";
  node.innerHTML =
    (state.route.page === "instance" && selected()
      ? instanceHeader(selected())
      : "") +
    '<div class="empty"><span class="loading"></span><p>Загружаю раздел</p></div>';
  main.replaceChildren(node);
  main.scrollTop = 0;
  activeView = null;
  try {
    const html = await {
      home: homePage,
      library: libraryPage,
      catalog: catalogPage,
      downloads: downloadsPage,
      screenshots: shotsPage,
      activity: activityPage,
      notices: noticesPage,
      instance: instancePage,
      project: projectPage,
    }[state.route.page]();
    if (epoch !== state.epoch) return;
    node.innerHTML = html;
    view = { node, data: captureViewData(), scroll: 0, used: Date.now() };
    screenViews.set(key, view);
    activeView = view;
    while (screenViews.size > 7) {
      const oldest = [...screenViews]
        .filter(([k]) => k !== key)
        .sort((a, b) => a[1].used - b[1].used)[0];
      if (!oldest) break;
      disposeImages(oldest[1].node);
      screenViews.delete(oldest[0]);
    }
    bindPage();
    finishView(node);
    animateView(node);
  } catch (error) {
    if (epoch !== state.epoch) return;
    node.innerHTML = `<div class="empty"><h2>Не удалось открыть раздел</h2><p class="error">${esc(error?.message || error)}</p>${btn("Повторить", "reload")}</div>`;
  }
}
const screenViews = new Map();
let activeView = null;
function viewKey() {
  const r = state.route;
  return [
    r.page,
    r.target || "",
    r.tab || "",
    r.page === "project" ? state.projectHit?.project_id || "" : "",
    r.page === "instance" && ["files", "worlds"].includes(r.tab)
      ? state.filePath || ""
      : "",
  ].join("|");
}
function captureViewData() {
  const value = {};
  for (const key of [
    "content",
    "shots",
    "history",
    "log",
    "fileEntries",
    "projectData",
    "catalogResult",
    "stats",
    "shotIndex",
    "contentKind",
    "activityWeekOffset",
  ])
    value[key] = state[key];
  value.selectedContent = new Set(state.selectedContent);
  return value;
}
function invalidateViews(target = "") {
  if (target) contentReads.delete(target);
  else contentReads.clear();
  for (const [key] of screenViews) {
    if (
      !target ||
      key.includes(target) ||
      key.startsWith("home|") ||
      key.startsWith("library|")
    ) {
      const view = screenViews.get(key);
      if (view) disposeImages(view.node);
      screenViews.delete(key);
    }
  }
}
function animateView(node) {
  node.getAnimations().forEach((animation) => animation.cancel());
  if (!matchMedia("(prefers-reduced-motion: reduce)").matches)
    node.animate(
      [
        { transform: "translateX(7px)", opacity: 0.8 },
        { transform: "translateX(0)", opacity: 1 },
      ],
      { duration: 145, easing: "cubic-bezier(.2,.8,.2,1)" },
    );
}
function finishView(root) {
  const activity = root.querySelector("#home-activity");
  if (activity && state.homeStats)
    activity.innerHTML = `<small>Время в игре</small><h2 style="margin:6px 0">${ms(state.homeStats.week_ms)}</h2><small>Эта неделя</small>${miniBars(state.homeStats)}`;
  enhanceSelects(root);
  enhanceTooltips(root);
  loadImages();
  translateVisible(root).catch(() => {});
}
function avatarMarkup(a) {
  return a?.face
    ? `<img src="${esc(a.face)}" alt="" width="38" height="38">`
    : esc(a?.name?.[0] || "A");
}
function buildIcon(b, cls = "") {
  const o = b?.options || {},
    preset = [
      "grass",
      "crystal",
      "craft",
      "fox",
      "anvil",
      "compass",
      "flame",
      "planet",
    ].includes(o.iconPreset)
      ? o.iconPreset
      : "cube",
    bg =
      {
        emerald: "#184c39",
        blue: "#24466b",
        violet: "#51416d",
        rose: "#6c3c4a",
        amber: "#70522f",
        cyan: "#23515b",
        slate: "#3c424e",
      }[o.iconBackground] || "#24466b";
  let src = `assets/icons/${preset}.png`;
  const local =
    o.iconImage || (b?.icon === "aw-build-icon.png" ? b.icon : null);
  if (!local && /^https:\/\//.test(o.iconUrl || b?.icon || ""))
    src = o.iconUrl || b.icon;
  return `<img class="build-icon ${cls}" style="background-color:${bg}" src="${esc(src)}" alt="" ${local ? `data-image-target="${esc(b.key)}" data-image-file="${esc(local)}"` : ""}>`;
}
function buildRow(b, home = false) {
  return `<div class="build-row">${buildIcon(b)}<button class="open-build" data-action="open-build" data-target="${esc(b.key)}"><span class="name">${esc(b.name)}</span><small>${home ? `${esc(b.version)} · ${esc(b.loader)}` : `${esc(b.loader)} ${esc(b.version)}`}</small></button>${home ? btn("›", "open-build", "quiet", `data-target="${esc(b.key)}"`) : `${btn(b.options.favorite ? "★" : "☆", "favorite", "star", `data-target="${esc(b.key)}" aria-label="Избранное"`)}${btn(icon("play") + "Играть", "launch", current()?.key === b.key ? "primary" : "", `data-target="${esc(b.key)}"`)}${btn("⋮", "build-menu", "icon-button", `data-target="${esc(b.key)}" aria-label="Действия со сборкой"`)}`}</div>`;
}
const statsMemo = new Map();
async function getStats(target = "") {
  const old = statsMemo.get(target);
  if (old && Date.now() - old.at < 30000) return old.promise;
  const entry = {
    at: Date.now(),
    promise: invoke("local_data", {
      kind: "activity",
      target: target || null,
      relative: null,
    }),
  };
  statsMemo.set(target, entry);
  entry.promise.catch(() => {
    if (statsMemo.get(target) === entry) statsMemo.delete(target);
  });
  return entry.promise;
}
function loadHomeStats() {
  if (state.homeStats || state.homeStatsPending) return;
  state.homeStatsPending = true;
  getStats()
    .then((stats) => {
      state.homeStats = stats;
      const panel = $("#home-activity");
      if (state.route.page === "home" && panel)
        panel.innerHTML = `<small>Время в игре</small><h2 style="margin:6px 0">${ms(stats.week_ms)}</h2><small>Эта неделя</small>${miniBars(stats)}`;
    })
    .catch(() => {})
    .finally(() => (state.homeStatsPending = false));
}
async function homePage() {
  const b = current();
  const stats = state.homeStats || null;
  loadHomeStats();
  const pending = state.queue.filter((x) =>
    ["waiting", "running"].includes(x.status),
  );
  return `<section class="home constrained"><div class="row spread"><h1>Играть</h1>${btn("Все сборки　→", "library", "link")}</div><div class="home-grid"><article class="hero"><div class="row spread"><small>Выбранная сборка</small><small>${b ? (b.ready ? "Готова к игре" : "Не установлена") : ""}</small></div><div class="hero-middle"><div class="grow"><h2>${esc(b?.name || "Начни новую игру")}</h2><p>${b ? (b.lastPlayed ? "Последний запуск: " + date(b.lastPlayed) : "Ещё не запускалась") : "Создай сборку или выбери модпак из каталога"}</p><div class="row">${b ? btn(icon("play") + (b.ready ? "Играть" : "Установить и играть"), "launch", "primary", `data-target="${esc(b.key)}"`) + btn("Настроить", "build-settings", "", `data-target="${esc(b.key)}"`) : btn("Новая сборка", "create-build", "primary")}</div></div>${b ? `<div class="hero-art">${buildIcon(b)}${btn("Изменить иконку", "icon-editor", "", `data-target="${esc(b.key)}"`)}</div>` : ""}</div>${b ? `<div class="hero-meta"><div><small>Версия</small>${esc(b.version)}</div><div><small>Загрузчик</small>${esc(b.loader)}</div><div><small>Содержимое</small>Проектов: ${b.contentCount ?? b.options.catalogMods?.length ?? 0}</div>${btn("›", "open-build", "quiet", `data-target="${esc(b.key)}"`)}</div>` : ""}</article><aside class="home-side"><div class="panel"><div class="row spread"><h3>Загрузки</h3>${btn(icon("download"), "downloads", "icon-button")}</div><p>${pending.length ? `В очереди: ${pending.length}` : "Загрузок пока нет"}</p>${pending[0] ? `<div class="track"><i style="width:${(pending[0].fraction || 0) * 100}%"></i></div>` : ""}</div><div class="panel activity-card" id="home-activity" role="button" tabindex="0" data-action="activity" aria-label="Открыть активность"><small>Время в игре</small><h2 style="margin:6px 0">${stats ? ms(stats.week_ms) : "…"}</h2><small>Эта неделя</small>${stats ? miniBars(stats) : ""}</div></aside></div><div class="row spread"><h2>Мои сборки</h2>${btn("Все сборки　→", "library", "link")}</div><div class="home-builds">${
    state.data.builds
      .slice(0, 3)
      .map((b) => buildRow(b, true))
      .join("") || '<div class="empty"><p>Твои сборки появятся здесь</p></div>'
  }</div></section>`;
}
async function libraryPage() {
  return `<section class="constrained stack"><div class="row spread"><h1>Мои сборки</h1><div class="row">${btn(icon("folder") + "Группы", "groups")}${btn(icon("add") + "Новая сборка", "create-build", "primary")}</div></div><div class="toolbar">${searchField("build-query", "Поиск сборок…")}${btn("Фильтры", "filters")}</div><div class="row spread"><select id="build-sort" style="width:180px"><option value="recent">Недавнее</option><option value="name">По названию</option><option value="favorite">Избранное</option></select>${btn("Активность →", "activity", "link")}</div><div id="library-filters" hidden><div class="row"><select id="loader-filter"><option value="">Все загрузчики</option>${[...new Set(state.data.builds.map((b) => b.loader))].map((x) => `<option>${esc(x)}</option>`).join("")}</select><select id="group-filter"><option value="">Все группы</option>${(state.data.settings.libraryGroups || []).map((g) => `<option value="${esc(g.id)}">${esc(g.name)}</option>`).join("")}</select></div></div><div class="row spread"><strong>Все сборки</strong><small id="build-count">${state.data.builds.length}</small></div><div class="build-list" id="build-list">${state.data.builds.map((b) => buildRow(b)).join("")}</div></section>`;
}
async function catalogPage() {
  const c = { ...state.catalog },
    b = state.data.builds.find((b) => b.key === c.target);
  let page;
  if (c.installed) {
    page = {
      hits: state.data.builds
        .filter((b) => b.key.startsWith("pack:"))
        .map((b) => ({
          project_id: b.options.projectId || b.key,
          title: b.name,
          description: `Minecraft ${b.version} · ${b.loader}`,
          author: "Установленная сборка",
          categories: [],
          build: b.key,
          icon_url: b.icon,
        })),
      total_hits: state.data.builds.length,
    };
  } else
    page = await invoke("search", {
      query: c.query,
      kind: c.kind,
      offset: c.offset,
      curseforge: c.source,
      game: c.kind === "modpack" ? null : b?.version || null,
      loader: c.kind === "modpack" ? null : b?.loader?.toLowerCase() || null,
      sort: c.sort,
    });
  if (
    state.route.page !== "catalog" ||
    JSON.stringify(c) !== JSON.stringify(state.catalog)
  )
    return "";
  state.catalogResult = page;
  return `<section class="stack"><div class="row spread"><h2>Каталог</h2><select id="catalog-target" style="max-width:280px"><option value="">Выбрать сборку</option>${state.data.builds.map((b) => `<option value="${esc(b.key)}" ${b.key === c.target ? "selected" : ""}>Для ${esc(b.name)}</option>`).join("")}</select></div><div class="row" style="flex-wrap:wrap"><div class="tabs" style="border:0;margin:0">${Object.entries(
    kinds,
  )
    .map(([key, name]) =>
      btn(
        name,
        "catalog-kind",
        c.kind === key && !c.installed ? "active" : "",
        `data-kind="${key}"`,
      ),
    )
    .join(
      "",
    )}${btn("Установленные", "catalog-installed", c.installed ? "active" : "")}</div><div class="chips">${btn("Modrinth", "catalog-source", !c.source ? "active" : "", 'data-source="modrinth"')}${btn("CurseForge", "catalog-source", c.source ? "active" : "", 'data-source="curseforge"')}</div></div><form id="catalog-search" class="row">${searchField("catalog-query", "Найти проект…")}<button type="submit" class="quiet">Найти</button></form><div class="row"><div class="chips">${[
    ["downloads", "По загрузкам"],
    ["relevance", "По совпадению"],
    ["follows", "По подписчикам"],
    ["updated", "По обновлению"],
  ]
    .map(([sort, label]) =>
      btn(
        label,
        "catalog-sort",
        c.sort === sort ? "active" : "",
        `data-sort="${sort}"`,
      ),
    )
    .join(
      "",
    )}</div><small data-catalog-count>Найдено: ${page.total_hits}</small></div><div class="catalog-grid">${
    page.hits
      .map(
        (h, i) =>
          `<article class="project-card"><div class="row">${h.icon_url ? `<img class="project-icon" src="${esc(h.icon_url)}" alt="">` : icon("extension")}<div class="grow"><div class="project-title" title="${esc(h.title)}" data-action="project" data-index="${i}" role="button" tabindex="0">${esc(h.title)}</div><p>by ${esc(h.author)}</p></div></div><p>${esc(h.description)}</p><div class="categories">${(
            h.categories || []
          )
            .slice(0, 3)
            .map((x) => `<span class="badge">${esc(x)}</span>`)
            .join(
              "",
            )}</div><small>↓ ${new Intl.NumberFormat("ru", { notation: "compact", maximumFractionDigits: 1 }).format(h.downloads || 0)}</small><div class="install">${btn(h.build ? "Открыть" : "Установить", h.build ? "open-build" : "install", "primary", h.build ? `data-target="${esc(h.build)}"` : `data-index="${i}"`)}</div></article>`,
      )
      .join("") ||
    '<div class="empty"><h2>Ничего не найдено</h2><p>Попробуй другой запрос или выбери другую сборку</p></div>'
  }</div><div class="pagination">${btn("←", "catalog-page", "", `data-offset="${Math.max(0, c.offset - 20)}" ${c.offset === 0 ? "disabled" : ""}`)}<span class="badge">${Math.floor(c.offset / 20) + 1}</span><small>/ ${Math.max(1, Math.ceil(page.total_hits / 20))}</small>${btn("→", "catalog-page", "", `data-offset="${c.offset + 20}" ${c.offset + 20 >= page.total_hits ? "disabled" : ""}`)}</div></section>`;
}
function downloadsPage() {
  return `<section class="stack grow"><h1>Загрузки</h1><div class="toolbar">${btn(icon("play") + (state.paused ? "Продолжить очередь" : "Приостановить очередь"), "pause", "", state.queue.some((x) => x.status === "waiting") ? "" : "disabled")}${btn("Очистить завершённые", "clear-downloads", "", state.queue.some((x) => ["done", "failed", "cancelled"].includes(x.status)) ? "" : "disabled")}</div><div class="row"><h2>Очередь</h2><small>${state.queue.filter((x) => x.status === "running").length} активных</small></div>${state.queue.length ? state.queue.map((x) => `<article class="panel stack"><div class="row spread"><strong>${esc(x.title)}</strong><span class="badge">${{ waiting: "В очереди", running: "Загружаю", done: "Готово", failed: "Ошибка", cancelled: "Отменено" }[x.status]}</span></div><p class="${x.status === "failed" ? "error" : ""}">${esc(x.error || x.stage || "")}</p>${x.status === "running" ? `<div class="track"><i style="width:${Math.round((x.fraction || 0) * 100)}%"></i></div><small>${Math.round((x.fraction || 0) * 100)}%　${x.speed ? Math.round(x.speed / 1048576) + " МБ/с" : ""}</small>${btn("Отменить", "cancel", "", `data-id="${x.id}"`)}` : x.status === "failed" ? btn("Повторить", "retry", "", `data-id="${x.id}"`) : ""}${x.login ? `<div class="high"><p>Открой ${esc(x.login.url)} и введи код</p><h2>${esc(x.login.code)}</h2>${btn("Скопировать код", "copy-code", "", `data-code="${esc(x.login.code)}"`)}</div>` : ""}</article>`).join("") : `<div class="empty grow">${icon("download")}<h3>Загрузок пока нет</h3><p>Добавляй моды и сборки из каталога — они загрузятся по очереди</p></div>`}</section>`;
}
let catalogSequence = 0;
async function updateCatalog() {
  const grid = $(".catalog-grid");
  if (!grid) return render({ refresh: true });
  const sequence = ++catalogSequence,
    epoch = state.epoch;
  grid.setAttribute("aria-busy", "true");
  grid.classList.add("results-loading");
  grid.inert = true;
  document
    .querySelectorAll('[data-action="catalog-kind"]')
    .forEach((el) =>
      el.classList.toggle(
        "active",
        !state.catalog.installed && el.dataset.kind === state.catalog.kind,
      ),
    );
  $('[data-action="catalog-installed"]')?.classList.toggle(
    "active",
    state.catalog.installed,
  );
  document
    .querySelectorAll('[data-action="catalog-sort"]')
    .forEach((el) =>
      el.classList.toggle("active", el.dataset.sort === state.catalog.sort),
    );
  document
    .querySelectorAll('[data-action="catalog-source"]')
    .forEach((el) =>
      el.classList.toggle(
        "active",
        (el.dataset.source === "curseforge") === state.catalog.source,
      ),
    );
  try {
    const html = await catalogPage();
    if (sequence !== catalogSequence || epoch !== state.epoch || !html) return;
    const template = document.createElement("template");
    template.innerHTML = html;
    const result = template.content.querySelector(".catalog-grid");
    grid.replaceChildren(...result.childNodes);
    $(".pagination")?.replaceWith(
      template.content.querySelector(".pagination"),
    );
    $("[data-catalog-count]").textContent = template.content.querySelector(
      "[data-catalog-count]",
    ).textContent;
    if (activeView) activeView.data = captureViewData();
    finishView(grid);
    animateView(grid);
  } catch (error) {
    if (epoch === state.epoch && sequence === catalogSequence) {
      grid.innerHTML = `<div class="empty"><p class="error">${esc(error?.message || error)}</p>${btn("Повторить", "catalog-retry")}</div>`;
    }
  } finally {
    if (sequence === catalogSequence) {
      grid.removeAttribute("aria-busy");
      grid.classList.remove("results-loading");
      grid.inert = false;
    }
  }
}
function instanceHeader(b, tab = state.route.tab || "content") {
  return `<section class="instance-header row">${buildIcon(b)}<div class="grow"><h1>${esc(b.name)}</h1><p>${esc(b.loader)} ${esc(b.version)} · ${ms(b.playTime)}</p><p>Последний запуск: ${date(b.lastPlayed)}</p></div>${btn(icon("play") + "Играть", "launch", "primary", `data-target="${esc(b.key)}"`)}${btn(icon("settings"), "build-settings", "icon-button", `data-target="${esc(b.key)}" aria-label="Настройки сборки"`)}${btn("⋮", "build-menu", "icon-button", `data-target="${esc(b.key)}" aria-label="Действия"`)}</section><div class="tabs">${Object.entries(
    tabs,
  )
    .map(([key, label]) =>
      btn(
        label,
        "instance-tab",
        tab === key ? "active" : "",
        `data-tab="${key}"`,
      ),
    )
    .join("")}</div>`;
}
async function instancePage() {
  const b = selected();
  if (!b) throw new Error("Сборка больше не найдена");
  const tab = state.route.tab || "content";
  const header = instanceHeader(b, tab);
  const body = await {
    content: () => contentPage(b),
    screenshots: () => shotsPage(b.key),
    statistics: () => activityPage(b.key),
    history: () => historyPage(b),
    log: () => logPage(b),
    files: () => filesPage(b),
    worlds: () => filesPage(b, true),
  }[tab]();
  return header + body;
}
const contentReads = new Map();
function readContent(target, force = false) {
  if (force) contentReads.delete(target);
  const old = contentReads.get(target);
  if (old) return old;
  const request = rpc({ op: "content", target });
  contentReads.set(target, request);
  request.catch(() => {
    if (contentReads.get(target) === request) contentReads.delete(target);
  });
  while (contentReads.size > 8)
    contentReads.delete(contentReads.keys().next().value);
  return request;
}
async function contentPage(b) {
  state.content = await guarded(readContent(b.key));
  state.selectedContent.clear();
  return `<section class="stack"><div class="toolbar">${searchField("content-query", "Поиск контента…")}${btn(icon("folder") + "Добавить файлы", "import-files")}${btn("Найти проекты", "find-content", "primary")}</div><div class="row spread"><div class="chips">${[
    ["", "Всё"],
    ["mod", "Моды"],
    ["shader", "Шейдеры"],
    ["resourcepack", "Ресурспаки"],
    ["datapack", "Датапаки"],
  ]
    .map(([kind, label]) =>
      btn(
        label,
        "content-kind",
        kind === (state.contentKind || "") ? "active" : "",
        `data-kind="${kind}"`,
      ),
    )
    .join(
      "",
    )}</div><div class="row">${btn(icon("download"), "update-all", "icon-button", 'aria-label="Обновить выбранные проекты"')}${btn(icon("refresh"), "reload", "icon-button", 'aria-label="Обновить список"')}</div></div><div id="selection-actions" class="toolbar" hidden>${btn("Включить", "enable-selected")}${btn("Выключить", "disable-selected")}${btn("Удалить", "remove-selected", "danger")}</div><div class="content-table"><div class="content-row table-head"><input id="select-all" type="checkbox" aria-label="Выбрать все"><span id="content-count">Проекты (${state.content.length})</span><span class="version-cell">Версия</span><span>Действия</span></div><div id="content-rows">${contentRows()}</div></div></section>`;
}
function contentRows() {
  return (
    state.content
      .map(
        (x, i) =>
          `<div class="content-row" data-content-index="${i}"><input type="checkbox" data-select="${i}" aria-label="Выбрать ${esc(x.title)}" ${state.selectedContent.has(i) ? "checked" : ""}><div class="row content-name ${x.enabled ? "" : "disabled"}">${x.icon ? `<img class="item-icon" src="${esc(x.icon)}" alt="">` : icon("extension")}<div class="grow"><strong>${esc(x.title)}</strong><small>${kinds[x.kind] || "Контент"}</small></div></div><div class="grow version-cell">${esc(x.version || "Неизвестно")}<small data-content-file>${esc(x.file)}</small></div><div class="content-actions">${x.update ? btn(icon("download"), "update-item", "icon-button", `data-index="${i}" aria-label="Обновить" title="Обновить проект"`) : '<span class="action-placeholder" aria-hidden="true"></span>'}${btn("", "toggle-content", `switch ${x.enabled ? "on" : ""}`, `data-index="${i}" role="switch" aria-checked="${x.enabled}" aria-label="${x.enabled ? "Выключить" : "Включить"} ${esc(x.title)}"`)}${btn(icon("delete"), "remove-content", "icon-button", `data-index="${i}" aria-label="Удалить" title="Удалить проект"`)}</div></div>`,
      )
      .join("") ||
    '<div class="empty"><h3>Проектов пока нет</h3><p>Добавь файлы или найди подходящие проекты в каталоге</p></div>'
  );
}
function filterContent() {
  const query = $("#content-query")?.value.toLowerCase() || "";
  let count = 0;
  document.querySelectorAll("[data-content-index]").forEach((row) => {
    const x = state.content[Number(row.dataset.contentIndex)];
    row.hidden = !!(
      (state.contentKind && x.kind !== state.contentKind) ||
      (query && !`${x.title} ${x.file}`.toLowerCase().includes(query))
    );
    if (!row.hidden) count++;
  });
  document
    .querySelectorAll('[data-action="content-kind"]')
    .forEach((b) =>
      b.classList.toggle(
        "active",
        b.dataset.kind === (state.contentKind || ""),
      ),
    );
  if ($("#content-count"))
    $("#content-count").textContent = `Проекты (${count})`;
}
function patchContentRow(index) {
  const x = state.content[index],
    row = document.querySelector(`[data-content-index="${index}"]`);
  if (!row) return;
  row.querySelector(".content-name").classList.toggle("disabled", !x.enabled);
  const toggle = row.querySelector('[data-action="toggle-content"]');
  toggle.classList.toggle("on", x.enabled);
  toggle.setAttribute("aria-checked", String(x.enabled));
  toggle.setAttribute(
    "aria-label",
    `${x.enabled ? "Выключить" : "Включить"} ${x.title}`,
  );
  row.querySelector("[data-content-file]").textContent = x.file;
}
async function filesPage(b, worlds = false) {
  state.fileEntries = await invoke("local_data", {
    kind: worlds ? "worlds" : "files",
    target: b.key,
    relative: state.filePath,
  });
  return `<section class="stack"><div class="toolbar">${searchField("file-query", worlds ? "Поиск миров…" : "Поиск файлов…")}${btn(icon("folder") + "Открыть папку", "open-folder", "", `data-target="${esc(b.key)}" data-relative="${worlds ? "saves" : esc(state.filePath)}"`)}${btn(icon("refresh"), "reload", "icon-button")}</div><div class="row"><p>${worlds ? "Миры сборки" : esc(state.filePath || "Папка сборки")}</p>${state.filePath ? btn("← Назад", "file-back", "quiet") : ""}</div><div id="file-rows">${state.fileEntries.map((x, i) => `<div class="file-row" data-file-name="${esc(x.name.toLowerCase())}">${icon(x.directory ? "folder" : "file")}<span class="grow">${esc(x.name)}</span><small>${x.directory ? "Папка" : new Intl.NumberFormat("ru", { notation: "compact" }).format(x.bytes) + " Б"}</small>${x.directory ? btn("Открыть", "file-open", "", `data-index="${i}"`) : ""}</div>`).join("") || '<div class="empty"><h3>Папка пуста</h3></div>'}</div></section>`;
}
async function logPage(b) {
  state.log = await guarded(
    invoke("local_data", { kind: "log", target: b.key, relative: null }),
  );
  return `<section class="journal-view"><div class="toolbar">${searchField("log-query", "Поиск в журнале…")}${btn(icon("refresh"), "refresh-log", "icon-button", 'aria-label="Обновить журнал" title="Обновить журнал"')}${btn("Перенос строк", "wrap-log", "quiet", 'aria-pressed="false"')}${btn("К концу", "log-end", "quiet")}</div><div class="row spread"><small id="log-count">Строк: ${state.log.length}</small><small>Журнал сборки</small></div><div class="log-scroll" id="log-scroll" tabindex="0" role="log" aria-label="Журнал сборки"><div id="log-lines">${logRows(state.log)}</div></div></section>`;
}
function logRows(lines) {
  return (
    lines
      .map((line) => {
        const level =
          /\b(ERROR|FATAL|WARN|DEBUG|TRACE)\b/.exec(line)?.[1]?.toLowerCase() ||
          "info";
        return `<div class="log-line log-${level}">${esc(line) || " "}</div>`;
      })
      .join("") ||
    '<div class="empty"><p>Журнал пока пуст</p><small>Записи появятся после запуска Minecraft</small></div>'
  );
}
function filterLog() {
  const query = $("#log-query")?.value.toLowerCase() || "",
    lines = state.log.filter((line) => line.toLowerCase().includes(query));
  $("#log-lines").innerHTML = logRows(lines);
  $("#log-count").textContent = `Строк: ${lines.length}`;
}
async function refreshLog() {
  const target = selected()?.key;
  if (!target) return;
  const lines = await invoke("local_data", {
    kind: "log",
    target,
    relative: null,
  });
  if (
    state.route.page !== "instance" ||
    state.route.target !== target ||
    state.route.tab !== "log"
  )
    return;
  if (JSON.stringify(lines) === JSON.stringify(state.log)) return;
  const scroll = $("#log-scroll"),
    atEnd = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 30,
    top = scroll.scrollTop;
  state.log = lines;
  filterLog();
  scroll.scrollTop = atEnd ? scroll.scrollHeight : top;
}
async function shotsPage(target = "") {
  state.shotIndex = null;
  state.shots = await guarded(
    invoke("local_data", {
      kind: "screenshots",
      target: target || null,
      relative: null,
    }),
  );
  return `<section class="stack grow"><div class="toolbar">${target ? "" : '<h2>Скриншоты</h2><select id="shot-target" style="width:180px"><option value="">Все сборки</option>' + state.data.builds.map((b) => `<option value="${esc(b.key)}">${esc(b.name)}</option>`).join("") + "</select>"}${btn(icon("refresh") + "Обновить список", "reload")}</div>${searchField("shot-query", "Поиск скриншотов…")}<small id="shot-count">Скриншотов: ${state.shots.length}</small><div id="shot-content" class="gallery">${shotCards()}</div></section>`;
}
function visibleShots() {
  const query = $("#shot-query")?.value.toLowerCase() || "",
    group = $("#shot-target")?.value;
  return state.shots
    .map((s, i) => ({ s, i }))
    .filter(
      ({ s }) =>
        (!group || s.target === group) &&
        (!query || `${s.name} ${s.title}`.toLowerCase().includes(query)),
    );
}
function shotCards() {
  return (
    visibleShots()
      .map(
        ({ s, i }) =>
          `<button class="shot" data-action="view-shot" data-index="${i}"><span class="shot-media media-loading"><img data-image-target="${esc(s.target)}" data-image-file="${esc(s.file)}" data-image-revision="${s.modified}" alt="${esc(s.name)}"><span class="media-message">Не удалось загрузить снимок<br>Нажми, чтобы повторить</span></span><span class="shot-caption"><strong>${esc(s.name)}</strong><small>${esc(s.title)}</small><small>${new Intl.DateTimeFormat("ru", { dateStyle: "medium", timeStyle: "short" }).format(s.modified)}</small></span></button>`,
      )
      .join("") ||
    '<div class="empty"><h3>Скриншотов пока нет</h3><p>Снимки, сделанные в Minecraft, появятся здесь</p></div>'
  );
}
function patchShots() {
  const gallery = $("#shot-content");
  if (!gallery) return;
  const old = new Map(
    [...gallery.querySelectorAll(".shot")].map((node) => {
      const img = node.querySelector("img");
      return [
        [
          img.dataset.imageTarget,
          img.dataset.imageFile,
          img.dataset.imageRevision,
        ].join("|"),
        node,
      ];
    }),
  );
  const template = document.createElement("template");
  template.innerHTML = shotCards();
  const nodes = [...template.content.children].map((node) => {
    const img = node.querySelector("img");
    if (!img) return node;
    const key = [
        img.dataset.imageTarget,
        img.dataset.imageFile,
        img.dataset.imageRevision,
      ].join("|"),
      existing = old.get(key);
    if (existing) {
      existing.dataset.index = node.dataset.index;
      return existing;
    }
    return node;
  });
  const retained = new Set(nodes);
  gallery.querySelectorAll(".shot").forEach((node) => {
    if (!retained.has(node)) disposeImages(node);
  });
  gallery.className = "gallery";
  gallery.replaceChildren(...nodes);
  if ($("#shot-count"))
    $("#shot-count").textContent = "Скриншотов: " + visibleShots().length;
  loadImages();
}
async function viewShot(index) {
  state.shotIndex = index;
  const s = state.shots[index],
    visible = visibleShots(),
    pos = visible.findIndex((x) => x.i === index);
  const node = $("#shot-content");
  disposeImages(node);
  node.className = "viewer";
  node.innerHTML = `<div class="row">${btn("← Все скриншоты", "shots-back")}<span class="grow">${esc(s.name)}</span><small>${pos + 1} / ${visible.length}</small></div><div class="viewer-media media-loading"><img data-image-target="${esc(s.target)}" data-image-file="${esc(s.file)}" data-image-revision="${s.modified}" data-large="true" alt="${esc(s.name)}"><span class="media-message">Не удалось загрузить снимок</span></div><div class="row">${btn("← Предыдущее", "prev-shot", "", pos === 0 ? "disabled" : "")}${btn("Следующее →", "next-shot", "", pos === visible.length - 1 ? "disabled" : "")}</div>`;
  loadImages();
}
async function activityPage(target = "") {
  const stats = await guarded(getStats(target));
  state.stats = stats;
  state.activityWeekOffset = 0;
  const rank = (items, tone) =>
    items
      .map(
        (x) =>
          `<div class="rank"><div class="row spread"><strong>${esc(x[0])}</strong><small>${ms(x[x.length - 1])}</small></div><div class="track"><i style="background:var(--${tone});width:${Math.max(0.4, (x[x.length - 1] / Math.max(1, items[0]?.[items[0].length - 1])) * 100)}%"></i></div></div>`,
      )
      .join("") || '<p class="rank-empty">Пока нет данных</p>';
  const heading = (iconName, label, tone) =>
    `<div class="stat-heading">${statIcon(iconName, tone)}<span>${label}</span></div>`;
  return `<section class="constrained stack activity-view"><h1>${target ? "Статистика сборки" : "Активность"}</h1><div class="stats-grid">${[
    ["clock", "Всего в игре", ms(stats.total_ms), "accent"],
    ["calendar", "Эта неделя", ms(stats.week_ms), "info"],
    ["flame", "Серия", stats.streak + " дня", "warning"],
    ["trophy", "Рекорд", ms(stats.longest_ms), "success"],
  ]
    .map(
      ([i, label, value, tone]) =>
        `<article class="panel stat">${heading(i, label, tone)}<strong>${value}</strong></article>`,
    )
    .join(
      "",
    )}</div><div class="charts"><article class="panel weekly-panel"><div id="weekly-chart">${weeklyChart(stats)}</div></article><article class="panel"><h3>Календарь активности</h3>${activityCalendar(stats)}</article></div><div class="rankings"><div class="stack">${!target ? `<article class="panel">${heading("layers", "ЛЮБИМЫЕ ВЕРСИИ", "info")}${rank(stats.versions || [], "info")}</article>` : ""}<article class="panel">${heading("server", "ЛЮБИМЫЕ СЕРВЕРЫ", "success")}${rank(stats.servers || [], "success")}</article></div><article class="panel">${heading("history", "ПОСЛЕДНИЕ СЕССИИ", "accent")}${
    (stats.recent || [])
      .slice(0, target ? 4 : 10)
      .map(
        (session) =>
          `<div class="session"><span>${date(session.start)}, ${new Intl.DateTimeFormat("ru", { hour: "2-digit", minute: "2-digit" }).format(session.start)}</span><span class="badge">${esc(session.buildName || session.instance)}</span><strong>${ms(session.end - session.start)}</strong></div>`,
      )
      .join("") ||
    '<div class="empty"><p>После игры здесь появятся твои сессии</p></div>'
  }</article></div></section>`;
}
function statIcon(name, tone) {
  return `<span class="stat-icon tone-${tone}"><span style="mask-image:url('assets/nav/${name}.png')"></span></span>`;
}
function activityCalendar(stats) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const begin = new Date(today);
  begin.setDate(begin.getDate() - 364 - ((begin.getDay() + 6) % 7));
  const cells = [],
    months = [];
  let previousMonth = -1;
  for (let week = 0; week < 53; week++) {
    const monday = new Date(begin);
    monday.setDate(monday.getDate() + week * 7);
    if (monday.getMonth() !== previousMonth) {
      if (week > 0 || monday.getDate() < 8)
        months.push(
          `<span style="grid-column:${week + 1}/span ${Math.min(4, 53 - week)}">${new Intl.DateTimeFormat("ru", { month: "short" }).format(monday).replace(".", "")}</span>`,
        );
      previousMonth = monday.getMonth();
    }
    for (let day = 0; day < 7; day++) {
      const d = new Date(monday);
      d.setDate(d.getDate() + day);
      const value = stats.days?.[localDate(d)] || 0;
      cells.push(
        `<i class="${d > today ? "future-day" : ""}" data-tooltip="${date(d.getTime())}: ${ms(value)}" style="${value ? "background:var(--accent);opacity:" + (0.25 + Math.min(value / 14400000, 1) * 0.75) : ""}"></i>`,
      );
    }
  }
  return `<div class="calendar-scroll"><div class="calendar-months">${months.join("")}</div><div class="calendar-body"><div class="calendar-weekdays"><span>пн</span><span>ср</span><span>пт</span></div><div class="calendar">${cells.join("")}</div></div></div><div class="calendar-legend"><small>Меньше</small>${[0, 0.25, 0.45, 0.65, 0.85, 1].map((level) => `<i style="${level ? "background:var(--accent);opacity:" + level : ""}"></i>`).join("")}<small>Больше</small></div>`;
}
function weeklyChart(stats) {
  const offset = state.activityWeekOffset || 0,
    values = weekValues(stats),
    total = values.reduce((a, b) => a + b, 0);
  return `<div class="row spread"><div><h3>Время по дням</h3><small>${offset ? "Недель назад: " + offset : "Эта неделя"}</small></div><div class="row week-controls">${btn("‹", "week-prev", "icon-button", 'aria-label="Предыдущая неделя"')}${btn("›", "week-next", "icon-button", `aria-label="Следующая неделя" ${offset ? "" : "disabled"}`)}<h2>${ms(total)}</h2></div></div>${weekBars(stats)}`;
}
function localDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
function weekValues(stats) {
  const now = new Date(),
    monday = new Date(now);
  monday.setDate(
    now.getDate() -
      ((now.getDay() + 6) % 7) -
      (state.activityWeekOffset || 0) * 7,
  );
  return Array.from({ length: 7 }, (_, i) => {
    const day = new Date(monday);
    day.setDate(day.getDate() + i);
    return stats.days?.[localDate(day)] || 0;
  });
}
function weekBars(stats) {
  const values = weekValues(stats),
    max = Math.max(1, ...values);
  return `<div class="bars">${values.map((value, i) => `<div class="bar-day" data-tooltip="${["Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"][i]}: ${ms(value)}"><span class="bar-track"><i style="--bar-scale:${value / max}"></i></span><small>${["пн", "вт", "ср", "чт", "пт", "сб", "вс"][i]}</small></div>`).join("")}</div>`;
}
function miniBars(stats) {
  const values = weekValues(stats),
    max = Math.max(1, ...values);
  return `<div class="row" style="align-items:flex-end;gap:4px;height:34px;margin-top:6px">${values.map((v) => `<i style="width:9px;border-radius:2px 2px 0 0;height:${Math.max(3, (v / max) * 30)}px;background:var(--accent);opacity:${v ? 1 : 0.15}"></i>`).join("")}</div>`;
}
function noticesPage() {
  const items = state.data.notices.items || [];
  return `<section class="stack grow"><h2>Уведомления</h2>${items.length ? items.map((n) => `<article class="panel stack"><h3 class="${n.level === "ERROR" ? "error" : ""}">${esc(n.title || n.text)}</h3>${n.title ? `<p>${esc(n.text)}</p>` : ""}<small>${date(n.at || n.id)}</small></article>`).join("") : `<div class="empty notice-empty grow">${icon("notices")}<h3>Пока тихо</h3><p>Если игра упадёт или мод не обновится, здесь будет понятно, что случилось и как это исправить</p></div>`}</section>`;
}
async function historyPage(b) {
  state.history = await guarded(rpc({ op: "history", target: b.key }));
  return `<section class="stack"><h3>Состояния до изменений</h3><p>Снимки сохраняют моды, настройки и другой контент перед обновлениями и массовыми изменениями</p><div class="toolbar">${btn("Вернуть рабочее состояние", "restore-latest", "primary", state.history.length ? "" : "disabled")}${btn("Сохранить состояние", "snapshot")}</div>${switchRow("Сохранять миры в новых снимках", "historyIncludeWorlds", b.options.historyIncludeWorlds, "Миры не копируются и при обычном откате сохраняют текущий прогресс", true)}${state.history.map((x, i) => `<article class="panel stack"><h3>${esc(x.reason || x.title || "Сохранённое состояние")}</h3><p>${date(x.createdAt || x.at)} · Minecraft ${esc(x.gameVersion)} · ${esc(x.loader)}</p><small>${x.includesWorlds ? "Снимок с мирами" : "Снимок без миров"} · ${new Intl.NumberFormat("ru", { notation: "compact" }).format(x.sizeBytes || 0)} Б</small><div class="toolbar">${btn("Восстановить", "restore", "", `data-index="${i}"`)}${btn("Показать изменения", "history-changes", "", `data-index="${i}"`)}</div></article>`).join("") || '<div class="empty"><h3>Снимков пока нет</h3><p>Сохрани состояние сборки, чтобы вернуться к нему после изменений</p></div>'}</section>`;
}
async function projectPage() {
  const h = state.projectHit;
  if (!h) throw new Error("Проект не выбран");
  const c = state.catalog;
  state.projectData = await guarded(
    rpc({
      op: "project",
      project: h.project_id,
      kind: c.kind,
      target: c.target,
    }),
  );
  const p = state.projectData.project;
  return `<section class="constrained stack"><div class="row">${h.icon_url ? `<img class="build-icon" src="${esc(h.icon_url)}" alt="">` : ""}<div class="grow"><h1>${esc(h.title)}</h1><p>${esc(h.author)}</p></div>${btn("Установить", "install-project", "primary")}</div><p>${esc(h.description)}</p><div class="tabs">${btn("Описание", "project-description", "active")}${btn("Версии", "project-versions")}</div><article class="panel" id="project-body" style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(p.body || p.description || h.description)}</article></section>`;
}
const imageCache = new Map(),
  imageJobs = new Map(),
  imageQueue = [];
let imageWorkers = 0,
  imageBytes = 0;
const imageObserver = new IntersectionObserver(
  (entries) => {
    for (const { target: img, isIntersecting } of entries) {
      img._near = isIntersecting;
      if (isIntersecting) queueImage(img);
      else if (
        img.closest(".shot-media") &&
        img.dataset.imageState === "loaded"
      ) {
        img.removeAttribute("src");
        img.dataset.imageState = "idle";
      }
    }
  },
  { root: $("#main"), rootMargin: "300px" },
);
function disposeImages(root) {
  root.querySelectorAll("img[data-image-target]").forEach((img) => {
    imageObserver.unobserve(img);
    img._observed = false;
  });
}
function loadImages() {
  document.querySelectorAll("img[data-image-target]").forEach((img) => {
    if (!img._observed) {
      img._observed = true;
      imageObserver.observe(img);
    }
    if (
      img.getBoundingClientRect().top < innerHeight + 300 &&
      img.getBoundingClientRect().bottom > 0
    )
      queueImage(img);
  });
}
function imageKey(img) {
  return [
    img.dataset.imageTarget,
    img.dataset.imageFile,
    img.dataset.imageRevision || "",
    img.dataset.large || "false",
  ].join("|");
}
function useImage(img, entry) {
  if (!img.isConnected || !img.getClientRects().length) {
    img.dataset.imageState = "idle";
    return;
  }
  img.src = entry.url;
  img.dataset.imageState = "loaded";
  img
    .closest(".shot-media,.viewer-media")
    ?.classList.remove("media-loading", "media-error");
}
function queueImage(img, retry = false) {
  if (
    !img.isConnected ||
    !img.getClientRects().length ||
    !img.dataset.imageFile
  )
    return;
  if (!retry && ["loaded", "loading", "error"].includes(img.dataset.imageState))
    return;
  const key = imageKey(img),
    cached = imageCache.get(key);
  if (cached) {
    imageCache.delete(key);
    imageCache.set(key, cached);
    useImage(img, cached);
    return;
  }
  img.dataset.imageState = "loading";
  img.closest(".shot-media,.viewer-media")?.classList.remove("media-error");
  img.closest(".shot-media,.viewer-media")?.classList.add("media-loading");
  if (imageJobs.has(key)) {
    imageJobs.get(key).images.add(img);
    return;
  }
  const job = {
    key,
    images: new Set([img]),
    target: img.dataset.imageTarget,
    file: img.dataset.imageFile,
    large: img.dataset.large === "true",
  };
  imageJobs.set(key, job);
  imageQueue.push(job);
  pumpImages();
}
function pumpImages() {
  while (imageWorkers < 2 && imageQueue.length) {
    const job = imageQueue.shift();
    if (
      ![...job.images].some(
        (img) =>
          img.isConnected && img.getClientRects().length && img._near !== false,
      )
    ) {
      imageJobs.delete(job.key);
      job.images.forEach((img) => (img.dataset.imageState = "idle"));
      continue;
    }
    imageWorkers++;
    invoke("image_data", {
      target: job.target,
      file: job.file,
      large: job.large,
    })
      .then((data) => {
        const match = /^data:(image\/[a-z0-9.+-]+);base64,(.+)$/i.exec(data);
        if (!match) throw Error("Некорректное изображение");
        const binary = atob(match[2]),
          bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        const entry = {
          url: URL.createObjectURL(new Blob([bytes], { type: match[1] })),
          bytes: bytes.byteLength,
        };
        imageCache.set(job.key, entry);
        imageBytes += entry.bytes;
        while (imageCache.size > 64 || imageBytes > 12 * 1024 * 1024) {
          const [key, old] = imageCache.entries().next().value;
          imageCache.delete(key);
          imageBytes -= old.bytes;
          URL.revokeObjectURL(old.url);
        }
        job.images.forEach((img) => useImage(img, entry));
      })
      .catch(() =>
        job.images.forEach((img) => {
          if (!img.isConnected) return;
          img.dataset.imageState = "error";
          const media = img.closest(".shot-media,.viewer-media");
          media?.classList.remove("media-loading");
          media?.classList.add("media-error");
        }),
      )
      .finally(() => {
        imageJobs.delete(job.key);
        imageWorkers--;
        pumpImages();
      });
  }
}
async function enqueue(title, request) {
  state.queue.push({
    id: `task-${++state.serial}`,
    title,
    request,
    status: "waiting",
    fraction: 0,
  });
  pump().catch(fail);
  if (state.route.page === "downloads") updateQueue();
  else toast("Добавлено в очередь: " + title);
}
async function pump() {
  if (state.running || state.paused) return;
  const item = state.queue.find((x) => x.status === "waiting");
  if (!item) return;
  state.running = true;
  item.status = "running";
  try {
    if (item.request.op === "launch" && state.accountSelection)
      await state.accountSelection.promise;
    item.result = await rpc(
      item.request,
      (event) => {
        if (event.type === "stage") item.stage = event.data;
        if (event.type === "progress") {
          item.fraction = event.data.fraction;
          item.speed = event.data.bytesPerSecond;
        }
        if (event.type === "login") {
          item.login = event.data;
          navigate("downloads");
        }
        updateQueue();
      },
      item.id,
    );
    item.status = "done";
    if (item.request.op === "launch") {
      state.games = state.games || [];
      state.games.push(item.result.pid);
      $("#game-status").textContent = "● Игра запущена";
    }
    toast(
      typeof item.result === "string"
        ? item.result
        : item.result?.message || "Готово",
    );
    await refresh();
    invalidateViews(item.request.target || "");
    if (
      state.route.page === "instance" &&
      state.route.target === item.request.target
    )
      await render({ refresh: true });
  } catch (error) {
    item.error = String(error);
    item.status = item.error.includes("отмен") ? "cancelled" : "failed";
    fail(error);
  } finally {
    state.running = false;
    updateQueue();
    await pump();
  }
}
function updateQueue() {
  if (state.route.page === "downloads") {
    const view = activeView?.node || $("#main");
    view.innerHTML = downloadsPage();
    finishView(view);
  }
}
async function saveSetting(key, value) {
  await invoke("save_setting", { key, value });
  state.data.settings[key] = value;
  applyTheme();
}
function showModal(title, body) {
  closePopover();
  const modal = $("#modal");
  modal.classList.toggle("build-settings-modal", title === "Настройки сборки");
  modal.innerHTML = `<div class="modal-head"><h2>${esc(title)}</h2>${btn("×", "close-modal", "icon-button", 'aria-label="Закрыть"')}</div><div class="modal-body stack">${body}</div>`;
  if (!modal.open) modal.showModal();
  finishView(modal);
}
function confirmAction(title, text, onConfirm) {
  showModal(
    title,
    `<p>${esc(text)}</p><div class="row">${btn("Отмена", "close-modal")}${btn("Продолжить", "confirm", "primary")}</div>`,
  );
  state.confirm = onConfirm;
}
function switchRow(label, key, value, hint = "", instance = false) {
  return `<div class="setting-row"><label>${esc(label)}${hint ? `<small>${esc(hint)}</small>` : ""}</label>${btn("", "setting-switch", `switch ${value ? "on" : ""}`, `role="switch" aria-checked="${!!value}" aria-label="${esc(label)}" data-key="${key}" ${instance ? 'data-instance="true"' : ""}`)}</div>`;
}
const settingsTabs = [
  [
    "accounts",
    "account",
    "Аккаунты",
    "Выбранный аккаунт используется при запуске Minecraft",
  ],
  ["general", "settings", "Общие", "Поведение лаунчера"],
  ["game", "settings", "Игра", "Память"],
  ["appearance", "image", "Внешний вид", "Внешний вид"],
  ["language", "file", "Язык", "Язык приложения"],
  ["downloads", "download", "Загрузки", "Загрузка и целостность"],
  ["discord", "friends", "Discord", "Discord Rich Presence"],
  ["about", "file", "О лаунчере", "Версия лаунчера"],
];
function openSettings(tab = state.settingsTab) {
  closePopover();
  state.settingsTab = tab;
  const current = $("#settings");
  if (current.open && current.querySelector(".settings-layout")) {
    const entry = settingsTabs.find((x) => x[0] === tab);
    current.querySelector(".settings-heading h2").textContent = entry[2];
    current.querySelector(".settings-heading small").textContent = entry[3];
    current
      .querySelectorAll(".settings-nav [data-tab]")
      .forEach((el) => el.classList.toggle("active", el.dataset.tab === tab));
    const body = current.querySelector(".settings-body");
    body.innerHTML = settingsBody(tab);
    body.scrollTop = 0;
    if (tab === "language") renderLanguages();
    finishView(body);
    translateVisible(current.querySelector(".settings-heading")).catch(
      () => {},
    );
    return;
  }
  const dialog = $("#settings");
  dialog.innerHTML = `<div class="settings-layout"><aside class="settings-nav"><h2>Настройки</h2><input id="settings-query" type="search" placeholder="Найти раздел" aria-label="Найти раздел"><nav>${settingsTabs.map(([id, i, title, subtitle]) => btn(`${icon(i)}<span>${title}<small>${subtitle}</small></span>`, "settings-tab", id === tab ? "active" : "", `data-tab="${id}"`)).join("")}</nav><small>AWLauncher ${esc(state.data.version)}</small></aside><section class="settings-content"><div class="settings-heading"><div><h2>${settingsTabs.find((x) => x[0] === tab)[2]}</h2><small>${settingsTabs.find((x) => x[0] === tab)[3]}</small></div>${btn("×", "close-settings", "icon-button", 'aria-label="Закрыть настройки"')}</div><div class="settings-body">${settingsBody(tab)}</div></section></div>`;
  if (!dialog.open) dialog.showModal();
  if (tab === "language") renderLanguages();
  finishView(dialog);
}
function settingsBody(tab) {
  const s = state.data.settings;
  switch (tab) {
    case "accounts":
      return `<div class="high" data-account-rows>${accountRows(false)}</div><hr><h3>Добавить аккаунт</h3><div class="chips">${btn("Microsoft", "account-mode", state.accountMode === "MICROSOFT" ? "active" : "", 'data-mode="MICROSOFT"')}${btn("Офлайн", "account-mode", state.accountMode === "OFFLINE" ? "active" : "", 'data-mode="OFFLINE"')}</div>${state.accountMode === "MICROSOFT" ? `<p>Подтверди вход по коду в браузере Microsoft</p><small>Нужен аккаунт с Minecraft Java Edition</small>${btn("Войти через Microsoft", "login", "primary")}` : `<form id="offline-account" class="stack"><input name="name" placeholder="Никнейм" pattern="[A-Za-z0-9_]{3,16}" minlength="3" maxlength="16" required aria-label="Никнейм"><small>От 3 до 16 латинских букв, цифр или символов подчёркивания</small><button class="primary">Добавить аккаунт</button></form>`}`;
    case "general":
      return `<div class="high"><h3>Поведение лаунчера</h3>${switchRow("Не закрывать лаунчер при запуске игры", "keepLauncherOpen", s.keepLauncherOpen)}${switchRow("Игра с друзьями", "friendsEnabled", s.friendsEnabled)}</div>`;
    case "game":
      return `<div class="high"><h3>Память</h3><p style="margin-top:8px">${Math.round(((s.memoryMb || 4096) / 1024) * 10) / 10} ГБ для игры · в компьютере ${Math.round(state.data.totalMemory / 1024)} ГБ</p><p class="accent" style="margin-top:10px">Рекомендуется для этого ПК: ${Math.round(state.data.recommendedMemory / 1024)} ГБ</p><input id="memory-range" type="range" min="512" max="${state.data.totalMemory - 1024}" step="256" value="${s.memoryMb || 4096}" aria-label="Память для игры"><form id="memory-form"><div class="field"><label for="memory-value">Своя память, МБ</label><input id="memory-value" type="number" min="512" max="${state.data.totalMemory - 1024}" value="${s.memoryMb || 4096}" required><small>От 512 до ${state.data.totalMemory - 1024} МБ · оставляем память системе</small></div><div class="row"><button class="quiet">Применить</button>${btn("Использовать рекомендуемую", "recommended", "quiet", 'type="button"')}</div></form></div><div class="high"><h3>Список версий</h3>${switchRow("Показывать снапшоты", "showSnapshots", s.showSnapshots)}${switchRow("Показывать alpha и beta", "showOldVersions", s.showOldVersions)}</div><form id="jvm-form" class="high stack"><h3>Аргументы JVM</h3><textarea id="jvm-args" rows="4" aria-label="Аргументы JVM">${esc(s.jvmArgs || "")}</textarea><button>Сохранить</button></form>`;
    case "appearance":
      return `<div class="high themes">${[
        ["SYSTEM", "Как в системе"],
        ["DARK", "Тёмная"],
        ["LIGHT", "Светлая"],
        ["OLED", "OLED · чёрная"],
      ]
        .map(([key, label]) =>
          btn(
            label,
            "theme",
            s.themeMode === key ? "active" : "",
            `data-theme="${key}"`,
          ),
        )
        .join("")}</div>`;
    case "language":
      return `<h3>Язык приложения</h3><input id="language-query" type="search" placeholder="Поиск языка…" aria-label="Поиск языка"><div id="language-list"></div>`;
    case "downloads":
      return `<div class="high stack"><h3>Загрузка и целостность</h3><p id="threads-label">Потоков загрузки: ${s.downloadConcurrency || "Авто"}</p><input id="threads-range" type="range" min="4" max="32" step="1" value="${s.downloadConcurrency || 16}" aria-label="Потоки загрузки"><div class="row" style="flex-wrap:wrap">${btn("Сбросить кэш проверки", "reset-cache", "quiet")}${btn(icon("folder") + "Открыть папку лаунчера", "open-root", "quiet")}</div><small>Сброс кэша проверки помогает, когда игра вылетает из-за повреждённых файлов: лаунчер перепроверит и докачает всё, что не сходится</small></div>`;
    case "discord":
      return `<h3>Discord Rich Presence</h3><p>Покажи друзьям, во что играешь</p><div class="high"><div class="row"><img src="assets/mark.png" width="64" height="64" alt=""><div><h3>AWLauncher</h3><p>В лаунчере</p><small>Выбирает следующую сборку</small></div></div><hr><small>С таймером текущей сессии и кнопкой сайта</small></div><div class="high">${switchRow("Показывать активность в Discord", "discordPresence", s.discordPresence, "Сборка, версия Minecraft и время игры")}${switchRow("Показывать открытый лаунчер", "discordShowLauncher", s.discordShowLauncher, "Если выключить, активность появится только во время игры")}${switchRow("Показывать название сборки", "discordShowInstance", s.discordShowInstance, "Для сборок из каталога добавится кнопка их страницы")}${switchRow("Показывать адрес сервера", "discordShowServer", s.discordShowServer, "Друзья увидят адрес сервера")}</div><small>Параметры используются существующим игровым движком</small>`;
    case "about":
      return `<h3>О лаунчере</h3><div class="row" style="margin:8px 0"><img src="assets/mark.png" width="40" height="40" alt=""><h1><span class="accent">AW</span>Launcher</h1></div><p>Версия лаунчера: ${esc(state.data.version)}</p>${btn("Проверить обновления", "check-update")}<hr>${btn(icon("folder") + "Открыть папку лаунчера", "open-root")}`;
  }
}
function renderLanguages() {
  const list = $("#language-list");
  if (!list) return;
  const top = list.scrollTop,
    focused = list.contains(document.activeElement)
      ? document.activeElement.dataset.id
      : null,
    first = !list._languageMounted,
    q = $("#language-query")?.value.toLowerCase() || "",
    language = state.data.settings.language;
  const english = new Intl.DisplayNames(["en"], {
    type: "language",
    languageDisplay: "standard",
  });
  list._languageMounted = true;
  list.setAttribute("role", "radiogroup");
  list.setAttribute("aria-label", "Язык приложения");
  list.innerHTML = state.languages
    .filter((l) =>
      `${l.label} ${l.tag} ${english.of(l.tag)}`.toLowerCase().includes(q),
    )
    .map((l) => {
      const region =
          l.tag.split("-")[1]?.toLowerCase() ||
          {
            ru: "ru",
            en: "us",
            es: "es",
            ja: "jp",
            ko: "kr",
            uk: "ua",
            zh: "cn",
            cs: "cz",
            el: "gr",
            hi: "in",
            ar: "sa",
          }[l.tag] ||
          l.tag,
        selected = language === l.id;
      return btn(
        `<img src="assets/flags/${esc(region)}.png" alt=""><span class="language-copy" data-native-text><strong>${esc(l.label)}</strong><small>${esc(english.of(l.tag))}</small></span><svg class="language-radio" viewBox="0 0 20 20" aria-hidden="true"><circle class="radio-ring" cx="10" cy="10" r="8.25"/><circle class="radio-dot" cx="10" cy="10" r="4.75"/></svg>`,
        "language",
        `language-row ${selected ? "active" : ""}`,
        `data-id="${esc(l.id)}" role="radio" aria-checked="${selected}" tabindex="${selected ? 0 : -1}"`,
      );
    })
    .join("");
  list.scrollTop = top;
  const checked = list.querySelector("[aria-checked=true]");
  if (!checked)
    list.querySelector("[role=radio]")?.setAttribute("tabindex", "0");
  if (first) {
    checked?.scrollIntoView({ block: "nearest" });
    list.scrollTop = Math.ceil(list.scrollTop / 56) * 56;
  }
  if (focused) {
    [...list.querySelectorAll("[role=radio]")]
      .find((row) => row.dataset.id === focused)
      ?.focus({ preventScroll: true });
  }
}
function bindPage() {
  if (
    state.route.page === "instance" &&
    (state.route.tab || "content") === "content"
  )
    filterContent();
  if ($("#catalog-query")) $("#catalog-query").value = state.catalog.query;
  if (state.route.page === "project" && state.projectData?.descriptionHtml)
    renderDescription();
}
function renderDescription() {
  const body = $("#project-body");
  body.className = "panel project-description";
  body.style.whiteSpace = "normal";
  const parsed = new DOMParser().parseFromString(
    state.projectData.descriptionHtml,
    "text/html",
  );
  const allowed = new Set([
    "P",
    "DIV",
    "SPAN",
    "H1",
    "H2",
    "H3",
    "H4",
    "H5",
    "H6",
    "STRONG",
    "EM",
    "B",
    "I",
    "S",
    "DEL",
    "BR",
    "HR",
    "UL",
    "OL",
    "LI",
    "BLOCKQUOTE",
    "PRE",
    "CODE",
    "TABLE",
    "THEAD",
    "TBODY",
    "TR",
    "TH",
    "TD",
    "A",
    "IMG",
  ]);
  function clean(node) {
    if (node.nodeType === Node.TEXT_NODE)
      return document.createTextNode(node.textContent);
    if (node.nodeType !== Node.ELEMENT_NODE || !allowed.has(node.tagName))
      return document.createTextNode(node.textContent || "");
    const element = document.createElement(node.tagName.toLowerCase());
    if (node.tagName === "IMG") {
      const src = node.getAttribute("src") || "";
      if (/^https:\/\//.test(src)) {
        element.src = src;
        element.alt = node.getAttribute("alt") || "";
        element.loading = "lazy";
      }
    }
    if (node.tagName === "A") {
      const href = node.getAttribute("href") || "";
      if (/^https:\/\//.test(href)) {
        element.href = href;
        element.dataset.action = "external";
        element.dataset.url = href;
      }
    }
    for (const child of node.childNodes) element.append(clean(child));
    return element;
  }
  body.replaceChildren(...[...parsed.body.childNodes].map(clean));
}
let popover = null;
function closePopover(restoreFocus = false) {
  if (!popover) return;
  const old = popover;
  popover = null;
  old.node.remove();
  old.anchor?.setAttribute("aria-expanded", "false");
  if (restoreFocus) old.anchor?.focus();
}
function openPopover(anchor, html, kind = "menu", width = 240) {
  closePopover();
  hideTooltip();
  const node = document.createElement("div");
  node.className = `floating-menu ${kind}`;
  node.innerHTML = html;
  (anchor.closest("dialog") || document.body).append(node);
  const rect = anchor.getBoundingClientRect();
  node.style.width = `${Math.min(width, innerWidth - 24)}px`;
  const height = Math.min(node.scrollHeight, 320);
  node.style.maxHeight = `${Math.min(320, innerHeight - 24)}px`;
  node.style.left = `${Math.max(12, Math.min(rect.left, innerWidth - node.offsetWidth - 12))}px`;
  node.style.top = `${Math.max(12, rect.bottom + height + 8 > innerHeight ? rect.top - height - 6 : rect.bottom + 6)}px`;
  popover = { node, anchor, kind };
  anchor.setAttribute("aria-expanded", "true");
  enhanceTooltips(node);
  translateVisible(node).catch(() => {});
  return node;
}
function enhanceSelects(root = document) {
  root.querySelectorAll("select").forEach((select) => {
    if (select._trigger) {
      select._trigger.querySelector(".select-label").textContent =
        select.selectedOptions[0]?.textContent || "";
      return;
    }
    const wrapper = document.createElement("span");
    wrapper.className = "select-field";
    wrapper.style.cssText = select.getAttribute("style") || "";
    select.removeAttribute("style");
    select.before(wrapper);
    wrapper.append(select);
    select.classList.add("native-select");
    select.tabIndex = -1;
    select.setAttribute("aria-hidden", "true");
    const trigger = document.createElement("button");
    trigger.type = "button";
    trigger.className = "select-trigger";
    trigger.setAttribute("role", "combobox");
    trigger.setAttribute("aria-haspopup", "listbox");
    trigger.setAttribute("aria-expanded", "false");
    trigger.setAttribute(
      "aria-label",
      select.getAttribute("aria-label") ||
        select.selectedOptions[0]?.textContent ||
        "Выбрать",
    );
    trigger.innerHTML =
      '<span class="select-label"></span><svg viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4"/></svg>';
    wrapper.append(trigger);
    select._trigger = trigger;
    const update = () =>
      (trigger.querySelector(".select-label").textContent =
        select.selectedOptions[0]?.textContent || "");
    trigger.disabled = select.disabled;
    update();
    select.addEventListener("change", update);
    trigger.addEventListener("click", () => {
      if (popover?.anchor === trigger) {
        closePopover();
        return;
      }
      openSelect(select);
    });
    trigger.addEventListener("keydown", (event) => {
      if (["ArrowDown", "ArrowUp"].includes(event.key)) {
        event.preventDefault();
        openSelect(select);
        popover?.node.querySelector("[aria-selected=true]")?.focus();
      }
    });
  });
}
function openSelect(select) {
  const node = openPopover(
    select._trigger,
    [...select.options]
      .map(
        (option, index) =>
          `<button type="button" role="option" aria-selected="${option.selected}" data-action="select-option" data-index="${index}" ${option.disabled ? "disabled" : ""}><span>${esc(option.textContent)}</span><span class="option-check">${option.selected ? '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="m3.5 8 3 3 6-6"/></svg>' : ""}</span></button>`,
      )
      .join(""),
    "select-options",
    Math.max(180, select._trigger.getBoundingClientRect().width),
  );
  node.setAttribute("role", "listbox");
  popover.select = select;
  node.addEventListener("keydown", (event) => {
    const options = [...node.querySelectorAll("button:not(:disabled)")],
      index = options.indexOf(document.activeElement);
    if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      const next =
        event.key === "Home"
          ? 0
          : event.key === "End"
            ? options.length - 1
            : (index + (event.key === "ArrowDown" ? 1 : -1) + options.length) %
              options.length;
      options[next]?.focus();
    }
  });
}
let tooltipNode = null;
function hideTooltip() {
  tooltipNode?.remove();
  tooltipNode = null;
}
function enhanceTooltips(root = document) {
  root.querySelectorAll("[title]").forEach((el) => {
    if (el.title) {
      el.dataset.tooltip = el.title;
      el.removeAttribute("title");
    }
  });
}
function showTooltip(el) {
  hideTooltip();
  if (!el.dataset.tooltip) return;
  const node = document.createElement("div");
  node.className = "ui-tooltip";
  node.setAttribute("role", "tooltip");
  node.textContent = el.dataset.tooltip;
  (el.closest("dialog") || document.body).append(node);
  const r = el.getBoundingClientRect();
  node.style.left = `${Math.max(8, Math.min(r.left + (r.width - node.offsetWidth) / 2, innerWidth - node.offsetWidth - 8))}px`;
  node.style.top = `${r.top > node.offsetHeight + 12 ? r.top - node.offsetHeight - 8 : r.bottom + 8}px`;
  tooltipNode = node;
}
document.addEventListener("pointerover", (event) => {
  const el = event.target.closest("[data-tooltip]");
  if (el && event.pointerType !== "touch") showTooltip(el);
});
document.addEventListener("pointerout", (event) => {
  if (event.target.closest("[data-tooltip]")) hideTooltip();
});
document.addEventListener("pointerdown", (event) => {
  hideTooltip();
  if (
    popover &&
    !popover.node.contains(event.target) &&
    !popover.anchor.contains(event.target)
  )
    closePopover();
});
document.addEventListener(
  "keydown",
  (event) => {
    if (event.key === "Escape" && popover) {
      event.preventDefault();
      event.stopImmediatePropagation();
      closePopover(true);
    }
  },
  true,
);
window.addEventListener("resize", () => {
  closePopover();
  hideTooltip();
});

async function openOriginalWindow(request){
 if(state.originalWindow)return;state.originalWindow=true;state.originalWindowReady=false;state.originalWindowTaskId=`read-${++state.serial}`;closePopover();closeAccountMenu();if($('#settings').open)$('#settings').close();
 try{const accounts=await rpc(request,event=>{if(event.type==='originalWindowReady')state.originalWindowReady=true;if(event.type==='gameStarted'){state.games=state.games||[];state.games.push(event.data);}},state.originalWindowTaskId);if(Array.isArray(accounts))applyAccounts(accounts);await refresh();invalidateViews();await render({refresh:true});}
 catch(error){if(!String(error).includes('отмен'))throw error;}
 finally{state.originalWindow=false;state.originalWindowReady=false;}
}
function editAccountIcon(){return '<svg class="account-control-icon material-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 0 0 0-1.41l-2.34-2.34a.996.996 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>';}
function deleteAccountIcon(){return '<svg class="account-control-icon material-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>';}
function accountCheck() {
  return '<svg class="account-control-icon" viewBox="0 0 20 20" aria-hidden="true"><path d="m4.5 10 3.5 3.5 7.5-7.5"/></svg>';
}
function renderAccountSummary() {
  const a = account();
  $("#account-name").textContent = a?.name || "Аккаунты";
  $("#account-type").textContent =
    a?.type === "MICROSOFT" ? "Microsoft" : a ? "Офлайн" : "Добавь аккаунт";
  $("#avatar").innerHTML = avatarMarkup(a);
  $("#account-button").setAttribute(
    "aria-busy",
    String(!!state.accountSelection),
  );
}
function accountRows(menu = false) {
  const busy = !!state.accountSelection || !!state.accountMutation;
  return (
    [...state.data.accounts]
      .sort((a, b) => Number(b.selected) - Number(a.selected))
      .map(
        (a) =>
          `<div class="account-management-row ${a.selected ? "selected" : ""}"><button class="account" data-action="select-account" data-id="${esc(a.id)}" ${busy ? "disabled" : ""} aria-label="Выбрать аккаунт ${esc(a.name)}"><span class="avatar">${avatarMarkup(a)}</span><span class="account-copy" data-native-text><strong>${esc(a.name)}</strong><small>${a.type === "MICROSOFT" ? "Microsoft" : "Офлайн"}</small></span></button>${!menu ? `<span class="badge ${a.type === "MICROSOFT" ? "license" : ""}">${a.type === "MICROSOFT" ? "Лицензия" : "Офлайн"}</span>` : ""}<span class="account-current" aria-label="${a.selected ? "Выбран" : ""}">${a.selected ? accountCheck() : ""}</span>${a.type === "MICROSOFT" ? btn(editAccountIcon(), "edit-account", "icon-button", `data-id="${esc(a.id)}" aria-label="Скин и плащ ${esc(a.name)}" title="Скин и плащ" ${busy ? "disabled" : ""}`) : '<span class="account-action-spacer"></span>'}${btn(deleteAccountIcon(), "remove-account", "icon-button", `data-id="${esc(a.id)}" aria-label="Удалить аккаунт ${esc(a.name)}" title="Удалить аккаунт" ${busy ? "disabled" : ""}`)}</div>`,
      )
      .join("") ||
    '<p class="account-empty">Добавь аккаунт для запуска Minecraft</p>'
  );
}
function renderAccountMenu() {
  const menu = $("#account-menu");
  menu.innerHTML = `<h3>Аккаунты</h3><div class="account-menu-list">${accountRows(true)}</div><div class="account-menu-footer">${btn(icon("add") + "Добавить аккаунт", "settings-tab", "quiet full", 'data-tab="accounts"')}</div>`;
}
function closeAccountMenu() {
  $("#account-menu").hidden = true;
  $("#account-button").setAttribute("aria-expanded", "false");
}
function syncAccountUi() {
  renderAccountSummary();
  if (!$("#account-menu").hidden) {
    renderAccountMenu();
    finishView($("#account-menu"));
  }
  const rows = $("[data-account-rows]");
  if (rows) {
    rows.innerHTML = accountRows(false);
    finishView(rows);
  }
}
function applyAccounts(accounts) {
  if (!Array.isArray(accounts)) throw Error("Не удалось прочитать аккаунты");
  state.data.accounts = accounts;
  if (state.accountSelection)
    accounts.forEach((a) => (a.selected = a.id === state.accountSelection.id));
  syncAccountUi();
}
async function selectAccount(id) {
  const target = state.data.accounts.find((a) => a.id === id);
  if (!target) throw Error("Аккаунт больше не найден");
  closeAccountMenu();
  if (target.selected || state.accountSelection) return;
  const previous = account()?.id,
    pending = { id, promise: null };
  state.accountSelection = pending;
  state.data.accounts.forEach((a) => (a.selected = a.id === id));
  syncAccountUi();
  pending.promise = (async () => {
    try {
      await rpc({ op: "accountSelect", file: id });
    } catch (error) {
      state.data.accounts.forEach((a) => (a.selected = a.id === previous));
      throw error;
    } finally {
      if (state.accountSelection === pending) state.accountSelection = null;
      syncAccountUi();
    }
  })();
  return pending.promise;
}
async function removeAccount(id) {
  if (state.accountSelection) await state.accountSelection.promise;
  state.accountMutation = true;
  syncAccountUi();
  try {
    applyAccounts(
      await rpc({
        op: "accountRemove",
        file: id,
        options: { returnAccounts: true },
      }),
    );
  } finally {
    state.accountMutation = false;
    syncAccountUi();
  }
}
const actions = {
  "catalog-retry": () => updateCatalog(),
  "week-prev": () => {
    state.activityWeekOffset = Math.min(
      52,
      (state.activityWeekOffset || 0) + 1,
    );
    $("#weekly-chart").innerHTML = weeklyChart(state.stats);
    finishView($("#weekly-chart"));
  },
  "week-next": () => {
    state.activityWeekOffset = Math.max(0, (state.activityWeekOffset || 0) - 1);
    $("#weekly-chart").innerHTML = weeklyChart(state.stats);
    finishView($("#weekly-chart"));
  },
  "select-option": (el) => {
    if (!popover?.select) return;
    const select = popover.select;
    select.selectedIndex = Number(el.dataset.index);
    closePopover(true);
    select.dispatchEvent(new Event("input", { bubbles: true }));
    select.dispatchEvent(new Event("change", { bubbles: true }));
  },
  "refresh-log": () => refreshLog(),
  "wrap-log": (el) => {
    const enabled = el.getAttribute("aria-pressed") !== "true";
    el.setAttribute("aria-pressed", String(enabled));
    $("#log-scroll").classList.toggle("wrap-lines", enabled);
  },
  "log-end": () => {
    const scroll = $("#log-scroll");
    scroll.scrollTop = scroll.scrollHeight;
  },
  external: (el) => invoke("open_url", { url: el.dataset.url }),
  reload: () => render({ refresh: true }),
  library: () => navigate("library"),
  activity: () => navigate("activity"),
  downloads: () => navigate("downloads"),
  "open-build": (el) =>
    navigate({ page: "instance", target: el.dataset.target, tab: "content" }),
  "instance-tab": (el) => navigate({ ...state.route, tab: el.dataset.tab }),
  launch: (el) =>
    enqueue(
      "Запуск " +
        state.data.builds.find((b) => b.key === el.dataset.target)?.name,
      { op: "launch", target: el.dataset.target },
    ),
  favorite: (el) => {
    const b = state.data.builds.find((b) => b.key === el.dataset.target);
    return enqueue("Избранное", {
      op: "saveOptions",
      target: b.key,
      options: { favorite: !b.options.favorite },
    });
  },
  "build-menu":el=>{
 const build=state.data.builds.find(b=>b.key===el.dataset.target),target=esc(build.key);const item=(label,action,i,extra='')=>btn(icon(i)+`<span>${label}</span>`,action,'',`data-target="${target}" ${extra}`);
 const native=(label,i,kind,danger=false)=>btn(icon(i)+`<span>${label}</span>`,'original-instance',danger?'menu-danger':'',`data-target="${target}" data-kind="${kind}"`);
 openPopover(el,native('Настройки','settings','settings')+item(build.options.favorite?'Убрать из избранного':'В избранное','favorite','trophy')+native('Изменить иконку','image','icon')+native('Изменить группу','folder','groups')+native('Экспортировать в .mrpack','download','export')+native('Дублировать','layers','duplicate')+native('Изменить версию Minecraft','refresh','migrate')+'<hr>'+item('Играть','launch','play')+item('Открыть папку','open-folder','folder')+item('Логи','open-journal','file')+item('Ярлык на рабочем столе','shortcut','forward')+'<hr>'+native('Скачать файлы','download','download')+native('Переустановить','refresh','reinstall')+item('Сохранить состояние','snapshot','history')+native('Удалить…','delete','delete',true),'build-actions',290);
 },
 "original-instance":el=>openOriginalWindow({op:'instanceWindow',target:el.dataset.target,kind:el.dataset.kind}),
 "open-journal":el=>navigate({page:'instance',target:el.dataset.target,tab:'log'}),
  "build-settings":el=>openOriginalWindow({op:'instanceWindow',target:el.dataset.target,kind:'settings'}),
  "create-build": async () => {
    showModal(
      "Новая сборка",
      '<div class="empty"><span class="loading"></span><p>Загружаю версии Minecraft</p></div>',
    );
    const versions = await rpc({ op: "versions" });
    showModal(
      "Новая сборка",
      `<form id="create-build" class="stack"><label>Название<input name="title" maxlength="80" required></label><label>Версия Minecraft<select name="version">${versions
        .filter(
          (v) =>
            v.type === "release" ||
            (state.data.settings.showSnapshots && v.type === "snapshot") ||
            state.data.settings.showOldVersions,
        )
        .map((v) => `<option value="${esc(v.id)}">${esc(v.id)}</option>`)
        .join(
          "",
        )}</select></label><label>Загрузчик<select name="kind">${["VANILLA", "FABRIC", "FORGE", "NEOFORGE", "QUILT"].map((l) => `<option>${l}</option>`).join("")}</select></label><button class="primary">Создать сборку</button></form>`,
    );
  },
  filters: () => {
    $("#library-filters").hidden = !$("#library-filters").hidden;
  },
  groups: () =>
    showModal(
      "Группы сборок",
      `<div class="stack">${(state.data.settings.libraryGroups || []).map((g) => `<div class="row"><span class="grow">${esc(g.name)}</span>${btn("Удалить", "remove-group", "danger", `data-id="${esc(g.id)}"`)}</div>`).join("")}<form id="new-group" class="row"><input name="name" maxlength="80" required placeholder="Название группы"><button>Добавить</button></form></div>`,
    ),
  "remove-group": async (el) => {
    await saveSetting(
      "libraryGroups",
      state.data.settings.libraryGroups.filter((g) => g.id !== el.dataset.id),
    );
    actions.groups();
  },
  "catalog-kind": (el) => {
    state.catalog.kind = el.dataset.kind;
    state.catalog.installed = false;
    state.catalog.offset = 0;
    return updateCatalog();
  },
  "catalog-installed": () => {
    state.catalog.installed = true;
    return updateCatalog();
  },
  "catalog-source": (el) => {
    state.catalog.source = el.dataset.source === "curseforge";
    state.catalog.offset = 0;
    return updateCatalog();
  },
  "catalog-sort": (el) => {
    state.catalog.sort = el.dataset.sort;
    state.catalog.offset = 0;
    return updateCatalog();
  },
  "catalog-page": (el) => {
    state.catalog.offset = Number(el.dataset.offset);
    return updateCatalog();
  },
  project: (el) => {
    state.projectHit = state.catalogResult.hits[Number(el.dataset.index)];
    navigate("project");
  },
  install: (el) =>
    installHit(state.catalogResult.hits[Number(el.dataset.index)]),
  "install-project": () => installHit(state.projectHit),
  "project-versions": () => {
    $("#project-body").innerHTML = (state.projectData.versions || [])
      .map(
        (v, i) =>
          `<div class="file-row"><span class="grow">${esc(v.name || v.versionNumber)}</span>${btn("Установить", "install-version", "primary", `data-index="${i}"`)}</div>`,
      )
      .join("");
  },
  "project-description": () => renderDescription(),
  "install-version": (el) =>
    installHit(
      state.projectHit,
      state.projectData.versions[Number(el.dataset.index)].id,
    ),
  pause: () => {
    state.paused = !state.paused;
    updateQueue();
    pump().catch(fail);
  },
  "clear-downloads": () => {
    state.queue = state.queue.filter((x) =>
      ["running", "waiting"].includes(x.status),
    );
    updateQueue();
  },
  cancel: (el) => invoke("cancel", { id: el.dataset.id }),
  retry: (el) => {
    const item = state.queue.find((x) => x.id === el.dataset.id);
    return enqueue(item.title, item.request);
  },
  "copy-code": (el) =>
    navigator.clipboard
      .writeText(el.dataset.code)
      .then(() => toast("Код скопирован")),
  "content-kind": (el) => {
    state.contentKind = el.dataset.kind;
    filterContent();
  },
  "toggle-content": async (el) => {
    const index = Number(el.dataset.index),
      x = state.content[index],
      target = selected().key,
      old = { enabled: x.enabled, file: x.file };
    el.disabled = true;
    el.setAttribute("aria-busy", "true");
    x.enabled = !x.enabled;
    patchContentRow(index);
    try {
      const result = await rpc({
        op: "toggle",
        target,
        file: old.file,
        kind: x.kind,
        enabled: x.enabled,
        options: { returnItem: true },
      });
      Object.assign(x, result);
      if (selected()?.key === target) patchContentRow(index);
    } catch (error) {
      Object.assign(x, old);
      if (selected()?.key === target) patchContentRow(index);
      throw error;
    } finally {
      el.disabled = false;
      el.removeAttribute("aria-busy");
    }
  },
  "remove-content": (el) => {
    const x = state.content[Number(el.dataset.index)];
    confirmAction("Удалить проект?", x.title, () =>
      enqueue("Удаление " + x.title, {
        op: "remove",
        target: selected().key,
        file: x.file,
        kind: x.kind,
      }),
    );
  },
  "update-item": (el) => {
    const x = state.content[Number(el.dataset.index)];
    return enqueue("Обновление " + x.title, {
      op: "updateItem",
      target: selected().key,
      file: x.file,
      kind: x.kind,
    });
  },
  "update-all": () => {
    const items = state.selectedContent.size
      ? [...state.selectedContent].map((i) => state.content[i])
      : state.content;
    return Promise.all(
      items
        .filter((x) => x.update)
        .map((x) =>
          enqueue("Обновление " + x.title, {
            op: "updateItem",
            target: selected().key,
            file: x.file,
            kind: x.kind,
          }),
        ),
    );
  },
  "enable-selected": () => bulkContent("toggle", true),
  "disable-selected": () => bulkContent("toggle", false),
  "remove-selected": () =>
    confirmAction(
      "Удалить выбранные проекты?",
      "Состояние сборки будет сохранено перед удалением",
      () => bulkContent("remove"),
    ),
  "find-content": () => {
    for (const key of screenViews.keys())
      if (key.startsWith("catalog|")) screenViews.delete(key);
    state.catalog.target = selected().key;
    state.catalog.kind = state.contentKind || "mod";
    state.catalog.installed = false;
    navigate("catalog");
  },
  "import-files": () => {
    showModal(
      "Добавить файлы",
      `<p>Выбери тип контента</p><div class="chips">${Object.entries(kinds)
        .filter(([k]) => k !== "modpack")
        .map(([kind, label]) =>
          btn(label, "import-kind", "", `data-kind="${kind}"`),
        )
        .join("")}</div>`,
    );
  },
  "import-kind": (el) => {
    const kind = el.dataset.kind;
    if (kind === "datapack")
      return chooseWorld((world) =>
        enqueue("Добавление файлов", {
          op: "importFiles",
          target: selected().key,
          kind,
          world,
        }),
      );
    $("#modal").close();
    return enqueue("Добавление файлов", {
      op: "importFiles",
      target: selected().key,
      kind,
    });
  },
  "open-folder": (el) =>
    invoke("local_data", {
      kind: "openFolder",
      target: el.dataset.target || selected().key,
      relative: el.dataset.relative || "",
    }),
  "file-open": (el) => {
    const entry = state.fileEntries[Number(el.dataset.index)];
    state.filePath = entry.relative;
    if (state.route.tab === "worlds")
      return invoke("local_data", {
        kind: "openFolder",
        target: selected().key,
        relative: entry.relative,
      });
    return render();
  },
  "file-back": () => {
    state.filePath = state.filePath.replace(/[/\\][^/\\]+$|^[^/\\]+$/, "");
    return render();
  },
  "view-shot": (el) => {
    const img = el.querySelector("img[data-image-target]");
    if (img?.dataset.imageState === "error") {
      queueImage(img, true);
      return;
    }
    return viewShot(Number(el.dataset.index));
  },
  "shots-back": () => {
    state.shotIndex = null;
    patchShots();
  },
  "prev-shot": () => stepShot(-1),
  "next-shot": () => stepShot(1),
  snapshot: (el) =>
    enqueue("Сохранение состояния", {
      op: "snapshot",
      target: el.dataset.target || selected().key,
    }),
  restore: (el) => restoreSnapshot(state.history[Number(el.dataset.index)]),
  "restore-latest": () => restoreSnapshot(state.history[0]),
  "history-changes": (el) => {
    const h = state.history[Number(el.dataset.index)];
    showModal(
      "Изменения сборки",
      `<pre style="white-space:pre-wrap">${esc((h.changes || []).map((c) => (typeof c === "string" ? c : `${c.title}: ${c.before || "—"} → ${c.after || "—"}`)).join("\n")) || "Подробности изменений отсутствуют"}</pre>`,
    );
  },
  shortcut: (el) => buildTask(el, "shortcut", "Создание ярлыка"),
  export: (el) => buildTask(el, "export", "Экспорт сборки"),
  duplicate: (el) => buildTask(el, "duplicate", "Копирование сборки"),
  "close-modal": () => $("#modal").close(),
  "close-settings": () => $("#settings").close(),
  confirm: async () => {
    const action = state.confirm;
    state.confirm = null;
    $("#modal").close();
    if (action) await action();
  },
  "settings-tab": (el) => openSettings(el.dataset.tab),
  "setting-switch": async (el) => {
    const key = el.dataset.key,
      value = el.getAttribute("aria-checked") !== "true";
    if (el.dataset.instance) {
      await enqueue("Настройки сборки", {
        op: "saveOptions",
        target: selected().key,
        options: { [key]: value },
      });
    } else {
      await saveSetting(key, value);
      el.classList.toggle("on", value);
      el.setAttribute("aria-checked", String(value));
    }
  },
  theme: async (el) => {
    await saveSetting("themeMode", el.dataset.theme);
    openSettings("appearance");
  },
  recommended: async () => {
    await saveSetting("memoryMb", state.data.recommendedMemory);
    openSettings("game");
  },
  language: async (el) => {
    await saveSetting("language", el.dataset.id);
    translationVersion++;
    await translateVisible(document.body);
    renderLanguages();
  },
  "reset-cache": () =>
    invoke("local_data", {
      kind: "resetCache",
      target: null,
      relative: null,
    }).then(() => toast("Кэш проверки сброшен")),
  "open-root": () =>
    invoke("local_data", { kind: "openRoot", target: null, relative: null }),
  "check-update": () =>
    enqueue("Проверка обновлений", {
      op: "checkUpdate",
      games: state.games?.length || 0,
    }),
  "account-mode": (el) => {
    state.accountMode = el.dataset.mode;
    openSettings("accounts");
  },
  login: () => {
    $("#settings").close();
    navigate("downloads");
    return enqueue("Вход через Microsoft", { op: "accountMicrosoft" });
  },
  "select-account": (el) => selectAccount(el.dataset.id),
  "edit-account": el => openOriginalWindow({op:"accountSkinWindow",file:el.dataset.id}),
  "remove-account": (el) => {
    const id = el.dataset.id;
    closeAccountMenu();
    confirmAction(
      "Удалить аккаунт из лаунчера?",
      "Сборки и аккаунт Microsoft сохранятся. Для входа потребуется авторизация заново",
      () => removeAccount(id),
    );
  },
  "icon-editor": (el) => {
    state.iconTarget = el.dataset.target;
    showModal(
      "Иконка сборки",
      `<p>Выбери рисунок</p><div class="toolbar">${["cube", "grass", "crystal", "craft", "fox", "anvil", "compass", "flame", "planet"].map((id) => btn(`<img src="assets/icons/${id}.png" width="48" height="48" alt="${id}">`, "icon-preset", "", `data-preset="${id}"`)).join("")}</div><p>Изменение иконок сохраняется через существующий менеджер сборок</p>`,
    );
  },
  "icon-preset": (el) =>
    enqueue("Иконка сборки", {
      op: "saveOptions",
      target: state.iconTarget,
      options: {
        iconPreset: el.dataset.preset === "cube" ? null : el.dataset.preset,
      },
    }),
};
function buildTask(el, op, title) {
  $("#modal").close();
  const b = state.data.builds.find(
    (b) => b.key === (el.dataset.target || selected()?.key),
  );
  return enqueue(title, {
    op,
    target: b.key,
    title: op === "duplicate" ? "Копия " + b.name : b.name,
  });
}
function stepShot(delta) {
  const visible = visibleShots(),
    pos = visible.findIndex((x) => x.i === state.shotIndex),
    next = visible[pos + delta];
  if (next) return viewShot(next.i);
}
function restoreSnapshot(h) {
  if (!h) return;
  confirmAction(
    "Восстановить состояние?",
    "Текущее состояние сохранится отдельно. Игровые миры сохранят текущий прогресс",
    () =>
      enqueue("Восстановление сборки", {
        op: "restore",
        target: selected().key,
        snapshot: h.id,
      }),
  );
}
function bulkContent(op, enabled = false) {
  return Promise.all(
    [...state.selectedContent].map((i) => {
      const x = state.content[i];
      return enqueue(x.title, {
        op,
        target: selected().key,
        file: x.file,
        kind: x.kind,
        enabled,
      });
    }),
  );
}
async function chooseWorld(callback) {
  state.worldCallback = callback;
  const worlds = await invoke("local_data", {
    kind: "worlds",
    target: selected()?.key || state.catalog.target,
    relative: null,
  });
  showModal(
    "Выбери мир",
    `<div class="stack">${
      worlds
        .filter((w) => w.directory)
        .map((w) =>
          btn(esc(w.name), "choose-world", "", `data-world="${esc(w.name)}"`),
        )
        .join("") || "<p>Сначала создай мир в этой сборке</p>"
    }</div>`,
  );
}
actions["choose-world"] = (el) => {
  const callback = state.worldCallback;
  $("#modal").close();
  return callback(el.dataset.world);
};
async function installHit(hit, version = null) {
  if (state.catalog.kind !== "modpack" && !state.catalog.target) {
    toast("Выбери сборку для установки", true);
    return;
  }
  const request = {
    op: "install",
    project: hit.project_id,
    title: hit.title,
    slug: hit.slug,
    kind: state.catalog.kind,
    target: state.catalog.target,
    version,
  };
  if (request.kind === "datapack")
    return chooseWorld((world) =>
      enqueue("Установка " + hit.title, { ...request, world }),
    );
  return enqueue("Установка " + hit.title, request);
}
document.addEventListener("click", (event) => {
  const nav = event.target.closest("#navigation [data-page]");
  if (nav) {
    navigate(nav.dataset.page);
    return;
  }
  const el = event.target.closest("[data-action]");
  if (el && !el.disabled) {
    event.preventDefault();
    const action = actions[el.dataset.action];
    if (action)
      Promise.resolve()
        .then(() => action(el))
        .catch(fail);
  }
});
document.addEventListener("keydown", (event) => {
  const radio = event.target.closest("#language-list [role=radio]");
  if (radio && ["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
    event.preventDefault();
    const rows = [...document.querySelectorAll("#language-list [role=radio]")],
      index = rows.indexOf(radio);
    const next =
      event.key === "Home"
        ? 0
        : event.key === "End"
          ? rows.length - 1
          : (index + (event.key === "ArrowDown" ? 1 : -1) + rows.length) %
            rows.length;
    rows[next]?.focus();
    rows[next]?.click();
    return;
  }
  const el = event.target.closest("[role=button][data-action]");
  if (el && ["Enter", " "].includes(event.key)) {
    event.preventDefault();
    el.click();
  }
  if (
    state.shotIndex !== null &&
    !$("#modal").open &&
    !$("#settings").open &&
    !["INPUT", "TEXTAREA"].includes(event.target.tagName)
  ) {
    if (event.key === "ArrowLeft") stepShot(-1);
    if (event.key === "ArrowRight") stepShot(1);
    if (event.key === "Escape") actions["shots-back"]();
  }
});
document.addEventListener("input", (event) => {
  const el = event.target;
  switch (el.id) {
    case "build-query":
    case "build-sort":
    case "loader-filter":
    case "group-filter":
      filterBuilds();
      break;
    case "content-query":
      filterContent();
      break;
    case "file-query":
      document
        .querySelectorAll("[data-file-name]")
        .forEach(
          (row) =>
            (row.hidden = !row.dataset.fileName.includes(
              el.value.toLowerCase(),
            )),
        );
      break;
    case "log-query":
      filterLog();
      break;
    case "shot-query":
    case "shot-target":
      state.shotIndex = null;
      patchShots();
      break;
    case "language-query":
      renderLanguages();
      break;
    case "settings-query":
      document
        .querySelectorAll(".settings-nav nav button")
        .forEach(
          (b) =>
            (b.hidden = !b.textContent
              .toLowerCase()
              .includes(el.value.toLowerCase())),
        );
      break;
    case "memory-range":
      $("#memory-value").value = el.value;
      break;
    case "threads-range":
      $("#threads-label").textContent = "Потоков загрузки: " + el.value;
      break;
  }
});
document.addEventListener("change", (event) => {
  const el = event.target;
  if (el.id === "catalog-target") {
    state.catalog.target = el.value;
    state.catalog.offset = 0;
    updateCatalog().catch(fail);
  }
  if (el.id === "threads-range")
    saveSetting("downloadConcurrency", Number(el.value)).catch(fail);
  if (el.dataset.select !== undefined) {
    const i = Number(el.dataset.select);
    el.checked ? state.selectedContent.add(i) : state.selectedContent.delete(i);
    $("#selection-actions").hidden = !state.selectedContent.size;
  }
  if (el.id === "select-all") {
    state.selectedContent.clear();
    document
      .querySelectorAll("[data-content-index]:not([hidden]) [data-select]")
      .forEach((c) => {
        c.checked = el.checked;
        if (el.checked) state.selectedContent.add(Number(c.dataset.select));
      });
    $("#selection-actions").hidden = !state.selectedContent.size;
  }
});
function filterBuilds() {
  const q = $("#build-query").value.toLowerCase(),
    loader = $("#loader-filter")?.value,
    group = $("#group-filter")?.value,
    sort = $("#build-sort").value;
  const builds = state.data.builds.filter(
    (b) =>
      b.name.toLowerCase().includes(q) &&
      (!loader || b.loader === loader) &&
      (!group || b.options.groupId === group),
  );
  builds.sort((a, b) =>
    sort === "name"
      ? a.name.localeCompare(b.name)
      : sort === "favorite"
        ? Number(b.options.favorite) - Number(a.options.favorite)
        : (b.lastPlayed || 0) - (a.lastPlayed || 0),
  );
  $("#build-list").innerHTML = builds.map((b) => buildRow(b)).join("");
  $("#build-count").textContent = builds.length;
  loadImages();
}
document.addEventListener("submit", (event) => {
  event.preventDefault();
  const form = event.target;
  if (!form.reportValidity()) return;
  const data = Object.fromEntries(new FormData(form));
  Promise.resolve()
    .then(async () => {
      switch (form.id) {
        case "catalog-search":
          state.catalog.query = $("#catalog-query").value;
          state.catalog.offset = 0;
          await updateCatalog();
          break;
        case "create-build":
          $("#modal").close();
          await enqueue("Создание сборки", { op: "createBuild", ...data });
          break;
        case "offline-account":
          $("#settings").close();
          await enqueue("Добавление аккаунта", {
            op: "accountOffline",
            title: data.name,
          });
          break;
        case "memory-form":
          await saveSetting("memoryMb", Number($("#memory-value").value));
          openSettings("game");
          break;
        case "jvm-form":
          await saveSetting("jvmArgs", $("#jvm-args").value);
          toast("Аргументы сохранены");
          break;
        case "new-group": {
          const groups = state.data.settings.libraryGroups || [];
          await saveSetting("libraryGroups", [
            ...groups,
            { id: crypto.randomUUID(), name: data.name.trim() },
          ]);
          actions.groups();
          break;
        }
        case "rename-build":
          $("#modal").close();
          await enqueue("Переименование", {
            op: "rename",
            target: state.editBuild.key,
            title: data.title,
          });
          break;
        case "build-options": {
          const options = {};
          for (const [key, value] of Object.entries(data))
            options[key] = ["memoryMb", "windowWidth", "windowHeight"].includes(
              key,
            )
              ? value
                ? Number(value)
                : null
              : key === "fullscreen"
                ? value
                  ? value === "true"
                  : null
                : value || null;
          $("#modal").close();
          await enqueue("Настройки сборки", {
            op: "saveOptions",
            target: state.editBuild.key,
            options,
          });
          break;
        }
      }
    })
    .catch(fail);
});
$("#back").onclick = () => {
  if (state.back.length) {
    state.forward.push({ ...state.route });
    state.route = state.back.pop();
    render().catch(fail);
  }
};
$("#forward").onclick = () => {
  if (state.forward.length) {
    state.back.push({ ...state.route });
    state.route = state.forward.pop();
    render().catch(fail);
  }
};
$("#settings-button").onclick = () => openSettings();
$("#account-button").onclick = () => {
  const menu = $("#account-menu");
  if (!menu.hidden) {
    closeAccountMenu();
    return;
  }
  renderAccountMenu();
  menu.hidden = false;
  $("#account-button").setAttribute("aria-expanded", "true");
  finishView(menu);
};
async function syncWindowButtons() {
  const win = T?.window.getCurrentWindow();
  if (!win?.isMaximized) return;
  const maximized = await win.isMaximized();
  const button = $("[data-window=toggleMaximize]");
  button.classList.toggle("maximized", maximized);
  button.title = maximized ? "Восстановить окно" : "Развернуть";
  button.setAttribute("aria-label", button.title);
}
document.querySelectorAll("[data-window]").forEach(
  (button) =>
    (button.onclick = async () => {
      try {
        await T.window.getCurrentWindow()[button.dataset.window]();
        if (button.dataset.window !== "close") await syncWindowButtons();
      } catch (error) {
        fail(error);
      }
    }),
);
if (T?.window.getCurrentWindow()?.onResized)
  T.window
    .getCurrentWindow()
    .onResized(() => syncWindowButtons().catch(fail))
    .catch(fail);
syncWindowButtons().catch(fail);
document.addEventListener("click", (event) => {
  if (!event.target.closest("#account-menu,#account-button"))
    closeAccountMenu();
});
for (const dialog of document.querySelectorAll("dialog"))
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) {
      const r = dialog.getBoundingClientRect();
      if (
        event.clientX < r.left ||
        event.clientX > r.right ||
        event.clientY < r.top ||
        event.clientY > r.bottom
      )
        dialog.close();
    }
  });
(async () => {
  state.languages = await fetch("assets/languages.json").then((r) => r.json());
  await refresh();
  await render();
})().catch((error) => {
  $("#main").innerHTML =
    `<div class="empty"><h2>Не удалось загрузить лаунчер</h2><p class="error">${esc(error?.message || error)}</p>${btn("Повторить", "restart")}</div>`;
  actions.restart = () => location.reload();
});

// Translate existing UI text with the launcher's shared dictionaries.

const originalText = new WeakMap();
let translationVersion = 0;
async function translateVisible(root = document.body) {
  const language = state.data.settings.language || "RU",
    version = translationVersion,
    walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT),
    nodes = [];
  let node;
  while ((node = walker.nextNode())) {
    if (
      node.parentElement?.closest(
        "script,style,input,textarea,.name,.project-title,.shot-caption,.project-description,.log-scroll,.content-name,.account strong,[data-native-text]",
      )
    )
      continue;
    const original = originalText.get(node) || node.textContent;
    if (!/[А-Яа-яЁё]/.test(original)) continue;
    originalText.set(node, original);
    if (language === "RU") node.textContent = original;
    else nodes.push({ node, original });
  }
  for (let start = 0; start < nodes.length; start += 256) {
    const batch = nodes.slice(start, start + 256),
      values = await invoke("translate_text", {
        language,
        values: batch.map((x) => x.original),
      });
    if (
      version !== translationVersion ||
      language !== state.data.settings.language
    )
      return;
    batch.forEach((x, i) => {
      if (x.node.isConnected && values[i]) x.node.textContent = values[i];
    });
  }
}
setInterval(async () => {
  try {
    const old = state.games || [];
    if (!old.length) return;
    state.games = await invoke("running_games", { pids: old });
    if (!state.games.length) {
      invalidateViews();
      statsMemo.clear();
      state.homeStats = null;
      document.getElementById("game-status").textContent =
        "● Нет активных сборок";
      await refresh();
      if (state.route.page === "home" || state.route.page === "activity")
        await render();
    }
  } catch {}
}, 3000);

setInterval(() => {
  if (
    state.route.page === "instance" &&
    state.route.tab === "log" &&
    document.visibilityState === "visible" &&
    $("#log-scroll")
  )
    refreshLog().catch(() => {});
}, 3000);
