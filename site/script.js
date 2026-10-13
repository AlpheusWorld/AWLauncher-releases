/* ============================================================
   AWLauncher — сайт лаунчера: логика страницы
   Без зависимостей. Анимации срабатывают при появлении и действиях пользователя.
   ============================================================ */
(function () {
  'use strict';

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  function bindChoiceKeys(selector) {
    $$(selector).forEach(function (group) {
      var choices = $$('[role="tab"], [role="radio"]', group);
      choices.forEach(function (choice) {
        choice.tabIndex = choice.getAttribute('aria-selected') === 'true' || choice.getAttribute('aria-checked') === 'true' ? 0 : -1;
      });
      group.addEventListener('keydown', function (event) {
        var index = choices.indexOf(event.target);
        if (index < 0) return;
        var next;
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (index + 1) % choices.length;
        else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (index - 1 + choices.length) % choices.length;
        else if (event.key === 'Home') next = 0;
        else if (event.key === 'End') next = choices.length - 1;
        else return;
        event.preventDefault();
        choices[next].click();
        choices[next].focus();
      });
    });
  }

  /* ══════════════════════════════════════════════════════════
     1. ЯЗЫК: русский снимком из разметки, английский словарём
     ══════════════════════════════════════════════════════════ */
  var EN = {
    'hero.preview': 'Everything you need, in one window',
    'mock.vanilla': 'no mods',
    "stats.localesValue": "50 interface languages",
    "mock.status": "Ready to play",
    "mock.libraryHint": "Minecraft Java Edition",
    "mock.library": "My instances",
    'a11y.skip': 'Skip to content',
    'a11y.menu': 'Menu',
    'nav.features': 'Features',
    'nav.loaders': 'Loaders',
    'nav.profiles': 'Instances',
    'nav.accounts': 'Accounts',
    'nav.discord': 'Discord',
    'nav.themes': "Appearance",
    'nav.install': 'Install',
    'nav.faq': 'Questions',
    'cta.download': 'Download for Windows',
    'cta.downloadShort': 'Download',
    'cta.features': "What is inside",
    'menu.note': 'Windows 10 and 11 · 64-bit · free',
    'hero.eyebrow': "Minecraft Java Edition",
    'hero.title1': "Build your own",
    'hero.title2': "Minecraft",
    'hero.lead': "Your favorite version, mods and worlds — in one launcher. Pick an instance and play; AWLauncher installs Java and the loader for you.",
    'hero.meta1': "Windows 10 / 11",
    'hero.meta2': "64-bit",
    'hero.meta3': "Free",
    'mock.sub': "Fabric · Java 25",
    'mock.play': 'Play',
    'stats.loaders': "Your favorite loader",
    'stats.catalogs': "Mods inside the launcher",
    'stats.locales': "In your language",
    'features.eyebrow': 'Features',
    'features.title': "Everything your game needs",
    'features.lead': "Versions, mods and Java in one place. Choose what to play — the launcher takes care of installation and file checks.",
    'f1.title': "Versions and mod loaders",
    'f1.text': "From the first alphas to recent releases and snapshots. Pick Minecraft and a loader — libraries and game files install automatically.",
    'f1.tagNew': "current release", 'f1.tagMods': 'for mods', 'f1.tagStable': 'battle-tested', 'f1.tagLegacy': 'for old packs', 'f1.tagSnap': 'snapshot',
    'f1.m1v': "918", 'f1.m1': "versions in the Mojang catalog", 'f1.m2v': 'automatic', 'f1.m2': 'libraries and assets', 'f1.m3v': 'cached', 'f1.m3': 'relaunch without re-downloading',
    'f2.title': "Find mods without leaving the app",
    'f2.text': "Modrinth and CurseForge inside the launcher. Find mods, packs, resources and shaders; required dependencies install with them.",
    'f2.t1': 'Import .mrpack packs and .zip files with manifest.json',
    'f2.t2': 'Choose the exact release and Minecraft version',
    'f2.t3': 'If a required file is unavailable, the pack is never installed half-broken',
    'f3.title': "The right Java, sorted",
    'f3.text': "The right Java installs with the game. Prefer your own? Set its path in the settings, separately for each instance.",
    'f3.demo': "matches 26.3",
    'f4.title': "When something goes wrong",
    'f4.text': "The launcher checks files and reads the crash log: missing memory, a library or a mod for the wrong version.",
    'f5.title': "Make it fit your PC",
    'f5.text': "Memory, window size and launch options — globally or per instance. The launcher suggests how much memory to allocate.",
    'f5.rec': 'we suggest 6 GB',
    'f5.min': '2 GB', 'f5.max': '16 GB',
    'f6.title': "Your worlds come with you",
    'f6.text': "Choose your old launcher folder. AWLauncher imports instances, worlds, mods and settings from Prism, MultiMC, Modrinth, CurseForge or ATLauncher. The original files stay in place.",
    'loaders.eyebrow': 'Loaders',
    'loaders.title': "Support for your favorite mods",
    'loaders.lead': "Fabric for a light setup, Forge for your favorite mods — or NeoForge and Quilt. Choose a loader when creating an instance.",
    'profiles.eyebrow': 'Instances',
    'profiles.title': "Your instances stay separate",
    'profiles.lead': "Vanilla today, a big modpack tomorrow. Each instance has its own mods, saves and settings. Group, favorite and duplicate them, worlds included.",
    'profiles.t1': 'Favorites, groups and a multi-select mode',
    'profiles.t2': 'Per-profile memory and Java',
    'profiles.t3': 'Playtime statistics and an activity calendar',
    'profiles.t4': 'Drag a folder or an archive straight into the library',
    'profiles.cardMeta': "Vanilla · no mods",
    'profiles.note': ".aw/instances/skyforge → mods, saves, config, shaderpacks",
    'accounts.eyebrow': 'Accounts and skin',
    'accounts.title': "Sign in with your account",
    'acc1.title': 'Microsoft sign-in',
    'acc1.text': "Sign in with Microsoft in your browser or create an offline profile. Your password stays on the Microsoft page and Windows DPAPI protects your tokens.",
    'acc1.code': 'Sign-in code',
    'acc1.t1': 'Tokens stay protected by Windows DPAPI',
    'acc1.t2': 'The session renews itself and you can cancel the sign-in from the accounts screen',
    'acc1.t3': 'The password never reaches the launcher — only the Microsoft page',
    'acc2.title': 'Skin with a 3D preview',
    'acc2.text': "Upload your PNG skin, pick Classic or Slim and see how it looks in the game. Drag the model below to rotate it.",
    'acc2.drag': 'Drag to rotate',
    'acc2.foot': "In the launcher, apply the skin to your chosen account after previewing it",
    'discord.eyebrow': 'Discord Rich Presence',
    'discord.title': "Show friends what you are playing",
    'discord.lead': "Discord shows your instance and time in the game. It connects automatically through the desktop app — no extra mods needed.",
    'discord.t1': 'The server address stays hidden until you allow it',
    'discord.t2': 'Show the game only, or hide the instance name',
    'discord.t3': 'Local instance images are never uploaded anywhere',
    'discord.t4': 'A Discord restart is handled automatically',
    'rpc.playing': 'Online', 'rpc.using': 'Playing',
    'rpc.mode': 'Singleplayer', 'rpc.elapsed': 'in session',
    'rpc.btnProject': 'Project page', 'rpc.btnLauncher': 'Get AWLauncher',
    'themes.eyebrow': 'Appearance',
    'themes.title': "Choose your appearance",
    'themes.lead': "Light, dark, system or OLED. Try them on this page — your choice stays saved in this browser.",
    'themes.light': 'Light', 'themes.lightHint': "light surfaces, gentle contrast",
    'themes.dark': 'Dark', 'themes.darkHint': "graphite and emerald",
    'themes.system': 'System', 'themes.systemHint': 'whatever Windows uses',
    'themes.oled': 'OLED', 'themes.oledHint': 'true black, no bloom',
    'locales.title': "We speak your language",
    'locales.text': "50 languages, including English. Translations and fonts ship with the launcher and work offline.",
    'install.eyebrow': 'Install',
    'install.title': "Install and start playing",
    'install.lead': "Ready-made installers are available for Windows 10 and 11, 64-bit. Choose your system to see the available options.",
    'os.win.tag': "installer",
    'os.src.tag': "no packages",
    'os.copy': 'Copy',
    'os.copied': 'Copied',
    'reqs.title': 'What you will need',
    'reqs.r1': "Windows 10 or 11, 64-bit",
    'reqs.r2': "Microsoft sign-in requires an account that owns Minecraft Java Edition",
    'reqs.r3': "Internet access for the first download of the game, Java and selected mods",
    'reqs.r4': "The launcher Java runtime is included in the installer",
    'reqs.title2': 'How a release is verified',
    'reqs.v1': "The update manifest is signed with Ed25519; the launcher verifies it before installation",
    'reqs.v2': "The installer size and SHA-256 are checked before updating",
    'reqs.v3': "Releases are available to everyone in the public repository; the initial installer does not currently have a trusted Authenticode certificate",
    'faq.eyebrow': 'Questions',
    'faq.title': "Any questions?",
    'faq.q1': 'Do I need a Minecraft license?',
    'faq.a1': "Microsoft sign-in requires an account that owns Minecraft Java Edition. Offline profiles work on servers configured to accept them: online-mode=false.",
    'faq.q2': 'Where does the launcher keep its files?',
    'faq.a2': "In the .aw folder inside your home directory. Instances live in .aw/instances, each with its own mods, saves, resourcepacks, shaderpacks and config. Shared libraries, game files and Java runtimes are stored separately.",
    'faq.q3': 'How do I move instances from another launcher?',
    'faq.a3': 'New instance → import from folder. Point at a profile, .minecraft or a shared instances directory — AWLauncher detects Prism, MultiMC, Modrinth, CurseForge or ATLauncher and copies the contents. Playtime and logs come along, and hours are never counted twice.',
    'faq.q4': 'What about the CurseForge catalog?',
    'faq.a4': "The CurseForge catalog uses an official AlpheusWorld API key. In a custom build, set it through AW_CURSEFORGE_API_KEY or .aw/curseforge_api_key.txt. If the key is missing or an author has disabled third-party downloads, the launcher does not bypass that restriction; Modrinth keeps working.",
    'faq.q5': 'The game crashes — where do I look?',
    'faq.a5': 'At the instance log: the launcher parses the game output and points at the cause. Check the profile files and the memory limit first — those are the two most common reasons.',
    'faq.q6': 'How much memory should I allocate?',
    'faq.a6': 'The launcher suggests a value for your machine. Big modded profiles with a hundred mods usually do fine with 4–8 GB; handing over all of your RAM is a bad idea.',
    'faq.q7': 'What is AWAssistant?',
    'faq.a7': 'A client mod for searching server rules inside Minecraft, opened with F8. AWLauncher 1.0.11 automatically installs it for Fabric 26.1.2 and 26.3 with Java 25 and Fabric Loader 0.19.3 or newer. Disable automatic installation in Settings → Game → AWAssistant client mod.',
    'foot.tagline': "Minecraft Java Edition. Your versions, mods and worlds — in one launcher.",
    'foot.note': 'This project is not affiliated with Mojang Studios or Microsoft. Minecraft is a trademark of Mojang Studios.',
    'foot.colProduct': 'Product',
    'foot.colMore': 'Details',
    'foot.colLinks': 'Links',
    'foot.releases': 'Releases',
    'foot.repo': "Source code",
    'foot.readme': 'Documentation',
    'foot.auth': 'MinecraftAuth',
    'foot.rights': "© 2026 AlpheusWorld. Launcher code is MIT-licensed; font and third-party notices ship with the distribution.",
    'foot.up': 'Back to top'
  };

  var TITLES = {
    ru: 'AWLauncher — лаунчер Minecraft для Windows',
    en: 'AWLauncher — a Minecraft launcher for Windows'
  };
  var DESCRIPTIONS = {
    ru: 'AWLauncher ставит Minecraft Java Edition, подбирает загрузчик и Java, разводит сборки по отдельным папкам. Fabric, Forge, NeoForge, Quilt, каталоги Modrinth и CurseForge, 50 языков, четыре темы.',
    en: 'AWLauncher installs Minecraft Java Edition, picks the loader and Java build and keeps every instance in its own folder. Fabric, Forge, NeoForge, Quilt, Modrinth and CurseForge catalogs, 50 languages, four themes.'
  };

  var LOADERS = {
    fabric: { name: 'Fabric', tag: pair('для сборок на Fabric', 'for Fabric instances'),
      text: pair('Выбирай для модов на Fabric. В примере — Minecraft 26.3 с Java 25; перед установкой мода проверь, что он выпущен для этой версии игры.', 'Choose it for Fabric mods. This example uses Minecraft 26.3 and Java 25; check that each mod supports this game version.'),
      loader: 'fabric-loader-0.19.5', minecraft: '26.3', java: '25' },
    forge: { name: 'Forge', tag: pair('для сборок на Forge', 'for Forge instances'),
      text: pair('Подходит для сборок, которые требуют Forge. В примере — Minecraft 1.20.1 и рекомендованный для него Forge 47.4.10. Моды на другом загрузчике могут не подойти.', 'Use it for instances that require Forge. The example uses Minecraft 1.20.1 and its recommended Forge 47.4.10 build. Mods for other loaders may be incompatible.'),
      loader: 'forge-47.4.10', minecraft: '1.20.1', java: '17' },
    neoforge: { name: 'NeoForge', tag: pair('для сборок на NeoForge', 'for NeoForge instances'),
      text: pair('Выбирай, если моды выпущены для NeoForge. В примере — Minecraft 1.21.1 и Java 21. Совместимость с Forge проверяй по требованиям конкретного мода.', 'Choose it for mods built for NeoForge. This example uses Minecraft 1.21.1 and Java 21. Check each mod for Forge compatibility.'),
      loader: 'neoforge-21.1.259', minecraft: '1.21.1', java: '21' },
    quilt: { name: 'Quilt', tag: pair('для сборок на Quilt', 'for Quilt instances'),
      text: pair('Подходит для модов и сборок, которые требуют Quilt. В примере — Minecraft 1.20.1 и Java 17. Для Fabric-модов проверяй заявленную совместимость с Quilt.', 'Use it for mods and instances that require Quilt. The example uses Minecraft 1.20.1 and Java 17. Check Fabric mods for declared Quilt compatibility.'),
      loader: 'quilt-loader-0.30.1', minecraft: '1.20.1', java: '17' }
  };

  var ruSnapshot = {};
  var ruAttrSnapshot = {};
  var currentLang = 'ru';
  // Verified fallback values, 2026-10-11. Live metadata refreshes them once per page load.
  var minecraftInfo = { release: '26.3', snapshot: '26.4-snapshot-3', count: 918 };

  function renderMinecraftInfo() {
    $$('[data-minecraft-release]').forEach(function (element) { element.textContent = minecraftInfo.release; });
    $$('[data-minecraft-snapshot]').forEach(function (element) { element.textContent = minecraftInfo.snapshot; });
    $$('[data-minecraft-count]').forEach(function (element) {
      element.textContent = minecraftInfo.count.toLocaleString(currentLang === 'ru' ? 'ru-RU' : 'en-US');
    });
  }

  function collectRu() {
    $$('[data-i18n]').forEach(function (el) {
      var key = el.getAttribute('data-i18n');
      if (!(key in ruSnapshot)) ruSnapshot[key] = el.textContent.trim();
    });
    $$('[data-i18n-attr]').forEach(function (el) {
      el.getAttribute('data-i18n-attr').split('|').forEach(function (pair) {
        var bits = pair.split(':');
        var attr = bits[0].trim();
        var key = bits[1].trim();
        if (!(key in ruAttrSnapshot)) ruAttrSnapshot[key] = el.getAttribute(attr) || '';
      });
    });
  }

  function applyLang(lang) {
    currentLang = lang;
    document.documentElement.lang = lang;
    $$('[data-i18n]').forEach(function (el) {
      var key = el.getAttribute('data-i18n');
      var value = lang === 'ru' ? ruSnapshot[key] : (EN[key] || ruSnapshot[key]);
      if (typeof value === 'string') el.textContent = value;
    });
    $$('[data-i18n-attr]').forEach(function (el) {
      el.getAttribute('data-i18n-attr').split('|').forEach(function (pair) {
        var bits = pair.split(':');
        var attr = bits[0].trim();
        var key = bits[1].trim();
        var value = lang === 'ru' ? ruAttrSnapshot[key] : (EN[key] || ruAttrSnapshot[key]);
        if (typeof value === 'string') el.setAttribute(attr, value);
      });
    });
    document.title = TITLES[lang] || TITLES.ru;
    var meta = $('meta[name="description"]');
    if (meta) meta.setAttribute('content', DESCRIPTIONS[lang] || DESCRIPTIONS.ru);
    $$('.lang .lang__opt').forEach(function (opt) {
      opt.classList.toggle('is-active', opt.getAttribute('data-lang') === lang);
    });
    applyLoader(activeLoader);
    applyOs(activeOs);
    renderLog();
    renderMinecraftInfo();
    try { localStorage.setItem('aw-lang', lang); } catch (e) {}
  }

  function toggleLang() { applyLang(currentLang === 'ru' ? 'en' : 'ru'); }

  ['#langToggle', '#langToggleMenu'].forEach(function (sel) {
    var el = $(sel);
    if (el) el.addEventListener('click', toggleLang);
  });

  /* ══════════════════════════════════════════════════════════
     2. ТЕМЫ
     ══════════════════════════════════════════════════════════ */
  var THEME_COLORS = { light: '#f5f5f1', dark: '#101213', oled: '#000000', system: '#101213' };
  var themeButtons = $$('#themePicker .theme-btn');

  function setTheme(theme) {
    if (!Object.prototype.hasOwnProperty.call(THEME_COLORS, theme)) theme = 'dark';
    document.documentElement.dataset.theme = theme;
    themeButtons.forEach(function (btn) {
      var selected = btn.getAttribute('data-theme-value') === theme;
      btn.setAttribute('aria-checked', String(selected));
      btn.tabIndex = selected ? 0 : -1;
    });
    var meta = $('meta[name="theme-color"]');
    var resolvedTheme = theme === 'system' ? (window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark') : theme;
    if (meta) meta.setAttribute('content', THEME_COLORS[resolvedTheme] || THEME_COLORS.dark);
    try { localStorage.setItem('aw-theme', theme); } catch (e) {}
  }

  themeButtons.forEach(function (btn) {
    btn.addEventListener('click', function () { setTheme(btn.getAttribute('data-theme-value')); });
  });

  window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', function () {
    if (document.documentElement.dataset.theme === 'system') setTheme('system');
  });

  /* ══════════════════════════════════════════════════════════
     3. МЕНЮ
     ══════════════════════════════════════════════════════════ */
  var burger = $('#burger');
  var menu = $('#menu');

  function openMenu() {
    if (!menu) return;
    menu.hidden = false;
    menu.classList.add('is-open');
    if (burger) burger.setAttribute('aria-expanded', 'true');
    document.body.classList.add('is-locked');
    setMenuBackground(true);
    var first = $('.menu__list a', menu);
    if (first) first.focus({ preventScroll: true });
  }

  function closeMenu(returnFocus) {
    if (!menu) return;
    menu.classList.remove('is-open');
    if (burger) burger.setAttribute('aria-expanded', 'false');
    document.body.classList.remove('is-locked');
    setMenuBackground(false);
    menu.hidden = true;
    if (returnFocus && burger) burger.focus({ preventScroll: true });
  }

  function setMenuBackground(locked) {
    $$('#main, .foot, .nav__brand, .nav__links, .nav__side > :not(#burger)').forEach(function (element) {
      element.inert = locked;
    });
  }

  if (burger && menu) {
    burger.addEventListener('click', function () {
      if (menu.hidden) openMenu(); else closeMenu(true);
    });
    $$('.menu a', menu).forEach(function (link) {
      link.addEventListener('click', function () { closeMenu(false); });
    });
    document.addEventListener('keydown', function (event) {
      if (menu.hidden) return;
      if (event.key === 'Escape') closeMenu(true);
      if (event.key === 'Tab') {
        var focusable = $$('a, button', menu).filter(function (element) { return element.getClientRects().length > 0; });
        focusable.push(burger);
        var current = focusable.indexOf(document.activeElement);
        var next = event.shiftKey ? current - 1 : current + 1;
        event.preventDefault();
        focusable[(next + focusable.length) % focusable.length].focus();
      }
    });
    window.addEventListener('resize', function () {
      if (!menu.hidden && window.innerWidth > 1120) closeMenu(false);
    });
  }

  /* ══════════════════════════════════════════════════════════
     4. ПОЯВЛЕНИЕ БЛОКОВ БЕЗ ПОСТОЯННОГО ЦИКЛА
     ══════════════════════════════════════════════════════════ */
  function initReveals() {
    var preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    var elements = $$('.reveal');
    if (preference.matches || !('IntersectionObserver' in window)) return;

    var observer = null;
    function show(element, animate) {
      element.classList.remove('is-pending');
      if (animate) element.classList.add('is-entering');
      if (observer) observer.unobserve(element);
    }

    try {
      observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) show(entry.target, !preference.matches);
        });
      }, { threshold: 0.08, rootMargin: '0px 0px -24px 0px' });
      elements.forEach(function (element) {
        element.classList.add('is-pending');
        observer.observe(element);
      });
    } catch (error) {
      if (observer) observer.disconnect();
      elements.forEach(function (element) { show(element, false); });
      return;
    }

    document.addEventListener('animationend', function (event) {
      if (event.animationName === 'aw-enter') event.target.classList.remove('is-entering');
    });
    document.addEventListener('focusin', function (event) {
      var pending = event.target.closest('.reveal.is-pending');
      if (pending) show(pending, false);
    });
    preference.addEventListener('change', function () {
      if (!preference.matches) return;
      observer.disconnect();
      elements.forEach(function (element) {
        show(element, false);
        element.classList.remove('is-entering');
      });
    });
  }

  /* ══════════════════════════════════════════════════════════
     5. НАВИГАЦИЯ: прогресс и активный раздел
     ══════════════════════════════════════════════════════════ */
  var progress = $('#navProgress');
  var navLinks = $$('.nav__links a');
  var sections = $$('main section[id]');
  var ticking = false;

  function onScrollFrame() {
    ticking = false;
    var scrolled = window.scrollY || document.documentElement.scrollTop;
    var max = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    if (progress) progress.style.transform = 'scaleX(' + Math.min(1, scrolled / max).toFixed(4) + ')';

    var bestId = '';
    var bestTop = -Infinity;
    sections.forEach(function (section) {
      var top = section.getBoundingClientRect().top - window.innerHeight * 0.35;
      if (top <= 0 && top > bestTop) { bestTop = top; bestId = section.id; }
    });
    navLinks.forEach(function (link) {
      link.classList.toggle('is-current', link.getAttribute('href') === '#' + bestId);
    });
  }

  function requestFrame() {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(onScrollFrame);
  }

  window.addEventListener('scroll', requestFrame, { passive: true });
  window.addEventListener('resize', requestFrame, { passive: true });
  requestFrame();

  /* ══════════════════════════════════════════════════════════
     6. МОКАП ЛАУНЧЕРА: статичный журнал
     ══════════════════════════════════════════════════════════ */
  var LOG_STEPS = [
    { ru: 'Minecraft 26.3 · Fabric 0.19.5', en: 'Minecraft 26.3 · Fabric 0.19.5' },
    { ru: 'Java 25 · установлена', en: 'Java 25 · installed' },
    { ru: 'Файлы сборки проверены', en: 'Instance files verified' },
    { ru: 'Всё готово к запуску', en: 'Ready to launch' }
  ];

  function renderLog() {
    var host = $('#mockLog');
    if (!host) return;
    host.textContent = '';
    LOG_STEPS.forEach(function (step) {
      var line = document.createElement('li');
      line.textContent = step[currentLang] || step.ru;
      host.appendChild(line);
    });
  }
  var mockBar = $('#mockBar');
  if (mockBar) mockBar.style.transform = 'scaleX(1)';

  /* ══════════════════════════════════════════════════════════
     8. ВЫБОР ЗАГРУЗЧИКА
     ══════════════════════════════════════════════════════════ */
  var activeLoader = 'fabric';
  var loaderPanel = $('#loaderPanel');

  function applyLoader(key) {
    var data = LOADERS[key];
    if (!data || !loaderPanel) return;
    activeLoader = key;
    loaderPanel.dataset.active = key;
    var name = $('#loaderName');
    var tag = $('#loaderTag');
    var text = $('#loaderText');
    if (name) name.textContent = data.name;
    if (tag) tag.textContent = data.tag[currentLang] || data.tag.ru;
    if (text) text.textContent = data.text[currentLang] || data.text.ru;
    var rowLoader = $('#rowLoader');
    var rowMc = $('#rowMc');
    var rowJava = $('#rowJava');
    if (rowLoader) rowLoader.textContent = data.loader;
    if (rowMc) rowMc.textContent = data.minecraft;
    if (rowJava) rowJava.textContent = data.java;
    $$('#loaderPicker .loader-tab').forEach(function (tab) {
      var on = tab.getAttribute('data-loader') === key;
      tab.classList.toggle('is-active', on);
      tab.setAttribute('aria-selected', String(on));
      tab.tabIndex = on ? 0 : -1;
    });
  }

  $$('#loaderPicker .loader-tab').forEach(function (tab) {
    tab.addEventListener('click', function () { applyLoader(tab.getAttribute('data-loader')); });
  });

  function t(key) {
    var value = currentLang === 'ru' ? ruSnapshot[key] : (EN[key] || ruSnapshot[key]);
    return typeof value === 'string' ? value : '';
  }

  /* ══════════════════════════════════════════════════════════
     9. УСТАНОВЩИК: ВЫБОР СИСТЕМЫ
     ══════════════════════════════════════════════════════════ */
  var RELEASES_URL = 'https://github.com/AlpheusWorld/AWLauncher-releases/releases/latest';
  var INSTALLER_URL = RELEASES_URL + '/download/AWLauncher-Setup.exe';

  function pair(ru, en) { return { ru: ru, en: en }; }

  function unavailableSystem(name, icon) {
    return {
      icon: icon, badge: pair(name + ' · пакетов нет', name + ' · no packages'),
      file: pair('Установщика для ' + name + ' пока нет', 'No installer for ' + name + ' yet'),
      rows: [
        [pair('пакеты', 'packages'), pair('не опубликованы', 'not published')],
        [pair('готовая версия', 'available build'), 'Windows x64'],
        [pair('исходный код', 'source code'), pair('открыт, MIT', 'open, MIT')]
      ],
      cmd: null, btn: pair('Посмотреть релизы', 'View releases'), btnIcon: '#i-external', href: RELEASES_URL,
      note: pair('Официальные установщики сейчас выпускаются только для Windows. Работа на ' + name + ' не подтверждена; готовые пакеты для этой системы не публикуются.', 'Official installers are currently released for Windows only. Operation on ' + name + ' is unverified; no packages are published for this system.'),
      steps: [
        [pair('Проверь доступные системы', 'Check available platforms'), pair('Текущий выпуск поддерживает Windows 10 и 11, 64-бит.', 'The current release supports Windows 10 and 11, 64-bit.')],
        [pair('Не ищи готовый пакет для ' + name, 'No ready-made ' + name + ' package'), pair('В официальном релизе есть EXE и MSI для Windows. Пакетов для выбранной системы пока нет.', 'Official releases include EXE and MSI installers for Windows. Packages for the selected system are not available yet.')],
        [pair('Проверяй страницу релизов', 'Check the releases page'), pair('Новые установщики и заметки об обновлениях публикуются в открытом репозитории AWLauncher-releases.', 'New installers and release notes are published in the public AWLauncher-releases repository.')]
      ]
    };
  }

  var SYSTEMS = {
    windows: {
      icon: '#i-windows', badge: pair('Windows 10 и 11 · 64-бит', 'Windows 10 and 11 · 64-bit'),
      file: 'AWLauncher-Setup.exe',
      rows: [
        [pair('версия', 'version'), '1.0.11'],
        [pair('формат', 'format'), 'EXE · MSI'],
        [pair('размер EXE', 'EXE size'), pair('≈98,6 МБ', '≈98.6 MB')],
        [pair('обновления', 'updates'), 'Ed25519 · SHA-256'],
        [pair('Java для игры', 'game Java'), pair('подбирается автоматически', 'selected automatically')]
      ],
      cmd: null, btn: pair('Скачать .exe', 'Download .exe'), btnIcon: '#i-download', href: INSTALLER_URL,
      note: pair('Установщик содержит Java для лаунчера. Файлы Minecraft загружаются отдельно. У первого установщика пока нет доверенного Authenticode-сертификата Windows; подпись обновлений Ed25519 проверяется отдельно.', 'The installer includes Java for the launcher. Minecraft files download separately. The initial installer does not yet have a trusted Windows Authenticode certificate; Ed25519 update signatures are verified separately.'),
      steps: [
        [pair('Скачай EXE и запусти установщик', 'Download the EXE and run the installer'), pair('Открой AWLauncher-Setup.exe из официального релиза и выбери папку. MSI включён в EXE; отдельно устанавливать Java для лаунчера не нужно.', 'Open AWLauncher-Setup.exe from the official release and choose a folder. The EXE contains the MSI; no separate Java installation is needed for the launcher.')],
        [pair('Войди в аккаунт', 'Sign in'), pair('Открой «Аккаунты» → «Войти через Microsoft» и подтверди вход в браузере. Нужен аккаунт с Minecraft Java Edition. Офлайн-профиль подходит для совместимых серверов.', 'Open Accounts → Sign in with Microsoft and confirm in your browser. An account that owns Minecraft Java Edition is required. Offline profiles work on compatible servers.')],
        [pair('Создай сборку', 'Create an instance'), pair('Выбери версию игры и подходящий загрузчик, добавь моды и нажми «Играть». Java подбирается по выбранной версии Minecraft, а сборки хранятся в .aw/instances.', 'Pick a game version and compatible loader, add mods and press Play. Java is selected for that Minecraft version, and instances live in .aw/instances.')]
      ]
    },
    linux: unavailableSystem('Linux', '#i-terminal'),
    macos: unavailableSystem('macOS', '#i-apple')
  };

  var activeOs = 'windows';

  function fetchMetadata(url) {
    var controller = new AbortController();
    var timeout = window.setTimeout(function () { controller.abort(); }, 10000);
    return window.fetch(url, { signal: controller.signal, credentials: 'omit', referrerPolicy: 'no-referrer' })
      .then(function (response) {
        if (!response.ok) throw new Error('Metadata response ' + response.status);
        return response.json();
      }).finally(function () { window.clearTimeout(timeout); });
  }

  function refreshPublicData() {
    if (!window.fetch || !window.AbortController) return;
    fetchMetadata('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
      .then(function (data) {
        if (!data || !data.latest || !Array.isArray(data.versions) || !data.versions.length) return;
        var release = data.versions.find(function (entry) { return entry.id === data.latest.release && entry.type === 'release'; });
        var snapshot = data.versions.find(function (entry) { return entry.id === data.latest.snapshot; });
        var safeId = /^[a-zA-Z0-9_.+-]{1,64}$/;
        if (!release || !snapshot || !safeId.test(release.id) || !safeId.test(snapshot.id)) return;
        minecraftInfo = { release: release.id, snapshot: snapshot.id, count: data.versions.length };
        renderMinecraftInfo();
      }).catch(function () {});

    fetchMetadata('https://api.github.com/repos/AlpheusWorld/AWLauncher-releases/releases/latest')
      .then(function (data) {
        if (!data || data.draft || data.prerelease || !Array.isArray(data.assets)) return;
        var version = String(data.tag_name || '').replace(/^v/, '');
        var installer = data.assets.find(function (asset) { return asset.name === 'AWLauncher-Setup.exe'; });
        if (!/^\d+\.\d+\.\d+$/.test(version) || !installer || !Number.isSafeInteger(installer.size) || installer.size <= 0 || installer.size > 2147483648) return;
        SYSTEMS.windows.rows[0][1] = version;
        var megabytes = installer.size / 1000000;
        SYSTEMS.windows.rows[2][1] = pair('≈' + megabytes.toFixed(1).replace('.', ',') + ' МБ', '≈' + megabytes.toFixed(1) + ' MB');
        applyOs(activeOs);
        var metadata = $('#softwareMetadata');
        if (metadata) {
          var application = JSON.parse(metadata.textContent);
          application.softwareVersion = version;
          metadata.textContent = JSON.stringify(application);
        }
      }).catch(function () {});
  }

  function pick(value) {
    if (value && typeof value === 'object') return value[currentLang] || value.ru;
    return value;
  }

  function applyOs(key) {
    var sys = SYSTEMS[key];
    if (!sys) return;
    activeOs = key;

    var badge = $('#osBadge');
    var file = $('#osFile');
    var rows = $('#osRows');
    var cmd = $('#osCmd');
    var cmdText = $('#osCmdText');
    var copyLabel = $('#osCmdCopyLabel');
    var btn = $('#osBtn');
    var btnLabel = $('#osBtnLabel');
    var note = $('#osNote');
    var icon = $('#osIconUse');
    var btnIcon = $('#osBtnIcon');

    if (badge) badge.textContent = pick(sys.badge);
    if (file) file.textContent = pick(sys.file);

    if (rows) {
      rows.innerHTML = '';
      sys.rows.forEach(function (row) {
        var line = document.createElement('div');
        line.className = 'lrow';
        var k = document.createElement('span');
        k.className = 'lrow__k';
        k.textContent = pick(row[0]);
        var v = document.createElement('span');
        v.className = 'lrow__v';
        v.textContent = pick(row[1]);
        line.appendChild(k);
        line.appendChild(v);
        rows.appendChild(line);
      });
    }

    if (cmd && cmdText) {
      if (sys.cmd) {
        cmdText.textContent = sys.cmd;
        cmd.hidden = false;
      } else {
        cmdText.textContent = '';
        cmd.hidden = true;
      }
    }
    if (copyLabel) copyLabel.textContent = t('os.copy');
    var copy = $('#osCmdCopy');
    if (copy) copy.classList.remove('is-done');

    if (btn) btn.setAttribute('href', sys.href);
    if (btnLabel) btnLabel.textContent = pick(sys.btn);
    if (note) note.textContent = pick(sys.note);
    if (icon) icon.setAttribute('href', sys.icon);
    if (btnIcon && sys.btnIcon) btnIcon.setAttribute('href', sys.btnIcon);

    sys.steps.forEach(function (step, index) {
      var title = $('#osStep' + (index + 1) + 'T');
      var text = $('#osStep' + (index + 1) + 'P');
      if (title) title.textContent = pick(step[0]);
      if (text) text.innerHTML = pick(step[1]);
    });

    $$('#osTabs .os-tab').forEach(function (tab) {
      var on = tab.getAttribute('data-os') === key;
      tab.classList.toggle('is-active', on);
      tab.setAttribute('aria-selected', String(on));
      tab.tabIndex = on ? 0 : -1;
    });
  }

  $$('#osTabs .os-tab').forEach(function (tab) {
    tab.addEventListener('click', function () { applyOs(tab.getAttribute('data-os')); });
  });

  var copyBtn = $('#osCmdCopy');
  if (copyBtn) {
    copyBtn.addEventListener('click', function () {
      var source = $('#osCmdText');
      var text = source ? source.textContent : '';
      if (!text) return;

      var done = function () {
        var label = $('#osCmdCopyLabel');
        copyBtn.classList.add('is-done');
        if (label) label.textContent = t('os.copied');
        window.setTimeout(function () {
          copyBtn.classList.remove('is-done');
          if (label) label.textContent = t('os.copy');
        }, 1800);
      };

      var legacyCopy = function () {
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.top = '-1000px';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        try { document.execCommand('copy'); done(); } catch (e) {}
        document.body.removeChild(area);
      };

      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, legacyCopy);
      } else {
        legacyCopy();
      }
    });
  }

  /* ══════════════════════════════════════════════════════════
     10. ПРОСМОТР СКИНА: воксельная фигура на CSS 3D
     ══════════════════════════════════════════════════════════ */
  var VOX_PARTS = [
    { name: 'head', w: 30, h: 30, d: 30, x: 0, y: 0, c: '#C08A62' },
    { name: 'torso', w: 32, h: 38, d: 17, x: 0, y: 30, c: '#2E9E68' },
    { name: 'arm', w: 12, h: 36, d: 12, x: -21, y: 31, c: '#B57C55' },
    { name: 'arm', w: 12, h: 36, d: 12, x: 21, y: 31, c: '#B57C55' },
    { name: 'leg', w: 14, h: 42, d: 14, x: -8, y: 68, c: '#3E5A82' },
    { name: 'leg', w: 14, h: 42, d: 14, x: 8, y: 68, c: '#3E5A82' }
  ];
  var VOX_FACES = ['front', 'back', 'left', 'right', 'top', 'bottom'];

  function buildVoxel(host) {
    VOX_PARTS.forEach(function (part) {
      var box = document.createElement('div');
      box.className = 'vox__b vox__b--' + part.name;
      box.style.setProperty('--w', part.w + 'px');
      box.style.setProperty('--h', part.h + 'px');
      box.style.setProperty('--d', part.d + 'px');
      box.style.setProperty('--x', part.x + 'px');
      box.style.setProperty('--y', part.y + 'px');
      box.style.setProperty('--c', part.c);
      VOX_FACES.forEach(function (face) {
        var el = document.createElement('i');
        el.className = 'vox__f vox__f--' + face;
        if (part.name === 'head' && face === 'front') {
          var eyes = document.createElement('span');
          eyes.className = 'vox__eyes';
          eyes.appendChild(document.createElement('i'));
          eyes.appendChild(document.createElement('i'));
          el.appendChild(eyes);
        }
        box.appendChild(el);
      });
      host.appendChild(box);
    });
  }

  var skinStage = $('#skinstage');
  var voxHost = $('#vox');
  if (skinStage && voxHost) {
    buildVoxel(voxHost);

    var voxAngle = -16;
    var voxDrag = false;
    var voxPointerX = 0;
    var voxStartAngle = 0;

    function voxApply(angle) {
      voxHost.style.transform = 'rotateX(5deg) rotateY(' + angle.toFixed(2) + 'deg)';
    }

    voxApply(voxAngle);

    skinStage.addEventListener('pointerdown', function (event) {
      if (event.button !== 0 || !event.isPrimary) return;
      if (event.target.closest && event.target.closest('.seg')) return;
      voxDrag = true;
      voxPointerX = event.clientX;
      voxStartAngle = voxAngle;
      skinStage.classList.add('is-dragging');
      if (skinStage.setPointerCapture) skinStage.setPointerCapture(event.pointerId);
    });

    skinStage.addEventListener('pointermove', function (event) {
      if (!voxDrag) return;
      voxAngle = Math.max(-75, Math.min(75, voxStartAngle + (event.clientX - voxPointerX) * 0.6));
      voxApply(voxAngle);
    });

    ['pointerup', 'pointercancel'].forEach(function (name) {
      skinStage.addEventListener(name, function (event) {
        if (!voxDrag) return;
        voxDrag = false;
        skinStage.classList.remove('is-dragging');
        if (skinStage.hasPointerCapture && skinStage.hasPointerCapture(event.pointerId)) {
          skinStage.releasePointerCapture(event.pointerId);
        }
      });
    });

    $$('.seg__btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        skinStage.dataset.model = btn.getAttribute('data-model');
        $$('.seg__btn').forEach(function (other) {
          var on = other === btn;
          other.classList.toggle('is-active', on);
          other.setAttribute('aria-checked', String(on));
          other.tabIndex = on ? 0 : -1;
        });
      });
    });
  }

  /* ══════════════════════════════════════════════════════════
     11. FAQ
     ══════════════════════════════════════════════════════════ */
  $$('.faq__q').forEach(function (button) {
    var answer = $('.faq__a', button.closest('.faq__item'));
    if (answer) answer.setAttribute('aria-hidden', 'true');
    button.addEventListener('click', function () {
      var item = button.closest('.faq__item');
      var isOpen = item.classList.contains('is-open');
      $$('.faq__item').forEach(function (other) {
        other.classList.remove('is-open');
        var q = $('.faq__q', other);
        if (q) q.setAttribute('aria-expanded', 'false');
        var otherAnswer = $('.faq__a', other);
        if (otherAnswer) otherAnswer.setAttribute('aria-hidden', 'true');
      });
      if (!isOpen) {
        item.classList.add('is-open');
        button.setAttribute('aria-expanded', 'true');
        if (answer) answer.setAttribute('aria-hidden', 'false');
      }
    });
  });

  /* ══════════════════════════════════════════════════════════
     12. СТАРТ
     ══════════════════════════════════════════════════════════ */
  var savedLang = null;
  try { savedLang = localStorage.getItem('aw-lang'); } catch (e) {}
  var attrLang = document.documentElement.getAttribute('data-lang');
  collectRu();
  applyLang(savedLang === 'en' || attrLang === 'en' ? 'en' : 'ru');

  var savedTheme = document.documentElement.dataset.theme || 'dark';
  setTheme(savedTheme);
  bindChoiceKeys('#themePicker, #loaderPicker, #osTabs, .seg');
  initReveals();
  refreshPublicData();
})();
