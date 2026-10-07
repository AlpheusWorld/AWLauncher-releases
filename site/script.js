/* ============================================================
   AWLauncher — сайт лаунчера: логика страницы
   Никаких зависимостей. Всё на transform/opacity.
   ============================================================ */
(function () {
  'use strict';

  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  /* ══════════════════════════════════════════════════════════
     1. ЯЗЫК: русский снимком из разметки, английский словарём
     ══════════════════════════════════════════════════════════ */
  var EN = {
    'a11y.skip': 'Skip to content',
    'a11y.menu': 'Menu',
    'nav.features': 'Features',
    'nav.loaders': 'Loaders',
    'nav.profiles': 'Instances',
    'nav.accounts': 'Accounts',
    'nav.discord': 'Discord',
    'nav.themes': 'Themes',
    'nav.install': 'Install',
    'nav.faq': 'Questions',
    'cta.download': 'Download for Windows',
    'cta.downloadShort': 'Download',
    'cta.features': 'What is inside',
    'menu.note': 'Windows 10 and 11 · 64-bit · free',
    'hero.eyebrow': 'Windows 10 and 11 · 64-bit · free',
    'hero.title1': 'Installs Minecraft, a loader and mods.',
    'hero.title2': 'Keeps every instance in its own folder',
    'hero.lead': 'AWLauncher downloads the game version you pick, chooses Java itself, installs the loader and sorts mods into instances. When the game crashes, it shows the reason from the log.',
    'hero.meta1': 'MSI signed with Authenticode and SHA-256',
    'hero.meta2': 'Auto-update over an HTTPS feed',
    'hero.meta3': 'Microsoft sign-in in the browser',
    'mock.sub': '148 mods · 6 GB of RAM · Java 21',
    'mock.play': 'Play',
    'mock.fps': 'frames per second in menus',
    'float.verifyTitle': 'Files verified',
    'float.verifyText': '12 480 files, 0 mismatches',
    'float.versionTitle': 'Version pulled',
    'float.versionText': 'snapshot 24w45a available',
    'stats.loaders': 'Loaders',
    'stats.catalogs': 'Content catalogs',
    'stats.locales': 'Interface languages',
    'stats.themes': 'Window themes',
    'mq.1': 'Fabric', 'mq.2': 'Forge', 'mq.3': 'NeoForge', 'mq.4': 'Quilt',
    'mq.5': 'Modrinth', 'mq.6': 'CurseForge', 'mq.7': 'Microsoft sign-in',
    'mq.8': 'Discord Rich Presence', 'mq.9': 'Automatic Java', 'mq.10': 'Snapshots and alpha',
    'mq.11': '.mrpack import', 'mq.12': 'Resource packs and shaders',
    'features.eyebrow': 'Features',
    'features.title': 'Minecraft versions, loaders, mods and Java',
    'features.lead': 'The launcher downloads version manifests, libraries and Java by itself. Modrinth and CurseForge catalogs open inside, and every profile lives in its own folder.',
    'f1.title': 'Versions and loaders in one list',
    'f1.text': 'Releases, snapshots, alpha and beta sit next to Fabric, Forge, NeoForge and Quilt. Pick a version and a loader — the launcher assembles the manifest, libraries, assets and launch arguments itself.',
    'f1.tagNew': 'latest', 'f1.tagMods': 'for mods', 'f1.tagStable': 'battle-tested', 'f1.tagLegacy': 'for old packs', 'f1.tagSnap': 'snapshot',
    'f1.m1v': '1,200+', 'f1.m1': 'versions across the manifests', 'f1.m2v': 'automatic', 'f1.m2': 'libraries and assets', 'f1.m3v': 'cached', 'f1.m3': 'relaunch without re-downloading',
    'f2.title': 'Modrinth and CurseForge catalogs',
    'f2.text': 'Search mods, modpacks, resource packs and shaders inside the launcher. Required dependencies install with the project, updates come from the instance card.',
    'f2.t1': 'Import .mrpack packs and .zip files with manifest.json',
    'f2.t2': 'Choose the exact release and Minecraft version',
    'f2.t3': 'If a required file is unavailable, the pack is never installed half-broken',
    'f3.title': 'No hunting for Java by hand',
    'f3.text': 'The launcher picks a matching runtime for the game version and installs it. Bring your own Java too: point at an executable or a JDK folder, even per instance.',
    'f3.demo': 'matches 1.20.1',
    'f4.title': 'File checks and readable crashes',
    'f4.text': 'It compares downloads against manifests and reads the game log. Instead of "Error 1" you get a line about what is missing: libraries, memory or a mod for a different version.',
    'f5.title': 'Memory, JVM flags and the window',
    'f5.text': 'A memory slider with a recommendation for your machine, custom JVM arguments, window size and fullscreen — global or per instance.',
    'f5.rec': 'we suggest 6 GB',
    'f5.min': '2 GB', 'f5.max': '16 GB',
    'f6.title': 'Move in from another launcher',
    'f6.text': 'Point at a profile folder, .minecraft or a shared instances directory — AWLauncher recognises Prism, MultiMC, Modrinth, CurseForge and ATLauncher, copies worlds, mods and settings, and rebuilds the activity calendar from logs. Source files stay untouched, credentials stay in the old launcher.',
    'loaders.eyebrow': 'Loaders',
    'loaders.title': 'Fabric, Forge, NeoForge and Quilt',
    'loaders.lead': 'The loader is chosen when you create an instance. The launcher runs its installer, spreads files inside the profile folder and records the loader version on the instance card.',
    'profiles.eyebrow': 'Instances',
    'profiles.title': 'Every instance has its own folder, worlds and settings',
    'profiles.lead': 'Mods from one instance never get in the way of another. Rename profiles, duplicate them together with their worlds, group them. Icons come from built-in badges and backgrounds, or from your own PNG, JPEG and WebP up to 8 MB.',
    'profiles.t1': 'Favorites, groups and a multi-select mode',
    'profiles.t2': 'Per-profile memory and Java',
    'profiles.t3': 'Playtime statistics and an activity calendar',
    'profiles.t4': 'Drag a folder or an archive straight into the library',
    'profiles.cardMeta': 'Vanilla · no mods',
    'profiles.note': '.aw/profiles/skyforge → mods, worlds, config, shaderpacks',
    'accounts.eyebrow': 'Accounts and skin',
    'accounts.title': 'Microsoft accounts, offline profiles and skins',
    'acc1.title': 'Microsoft sign-in',
    'acc1.text': 'Confirm the sign-in in your browser — the password is only ever typed on the Microsoft page. Tokens are protected with Windows DPAPI and the session renews itself. Offline accounts work on servers with online-mode=false.',
    'acc1.code': 'Sign-in code',
    'acc1.t1': 'Tokens stay protected by Windows DPAPI',
    'acc1.t2': 'The session renews itself and you can cancel the sign-in from the accounts screen',
    'acc1.t3': 'The password never reaches the launcher — only the Microsoft page',
    'acc2.title': 'Skin with a 3D preview',
    'acc2.text': 'Upload a 64×64 or 64×32 PNG up to 1 MB, pick Classic or Slim and look at the character before sending. Drag with the mouse to rotate it.',
    'acc2.drag': 'Drag to rotate',
    'acc2.foot': 'The “Apply to account” button sends the skin to Minecraft Services with that account token. The launcher checks the profile UUID before writing.',
    'discord.eyebrow': 'Discord Rich Presence',
    'discord.title': 'Discord activity',
    'discord.lead': 'The launcher talks to your installed Discord over local Windows IPC. No sign-in, no bot, no tokens. The mode is read from the game launch and its log, without any mod.',
    'discord.t1': 'The server address stays hidden until you allow it',
    'discord.t2': 'Show the game only, or hide the instance name',
    'discord.t3': 'Local instance images are never uploaded anywhere',
    'discord.t4': 'A Discord restart is handled automatically',
    'rpc.playing': 'Online', 'rpc.using': 'Playing',
    'rpc.mode': 'Singleplayer', 'rpc.elapsed': 'in session',
    'rpc.btnProject': 'Project page', 'rpc.btnLauncher': 'Get AWLauncher',
    'themes.eyebrow': 'Appearance',
    'themes.title': 'Four themes: light, dark, system, OLED',
    'themes.lead': 'The choice is remembered in the launcher. Below you can switch this page and see how they differ.',
    'themes.light': 'Light', 'themes.lightHint': 'soft green on white',
    'themes.dark': 'Dark', 'themes.darkHint': 'graphite and emerald',
    'themes.system': 'System', 'themes.systemHint': 'whatever Windows uses',
    'themes.oled': 'OLED', 'themes.oledHint': 'true black, no bloom',
    'locales.title': '50 interface locales',
    'locales.text': 'Russian, US and UK English, Spain and Latin American Spanish, Brazilian and European Portuguese, two Chinese variants, plus Arabic and Hebrew with right-to-left layout. Fonts and translations ship inside the app — no network needed.',
    'install.eyebrow': 'Install',
    'install.title': 'Pick your system',
    'install.lead': 'A signed installer ships for Windows. On Linux and macOS the launcher builds from source with one Gradle command — there are no ready-made packages yet.',
    'os.win.tag': 'installer',
    'os.src.tag': 'from source',
    'os.copy': 'Copy',
    'os.copied': 'Copied',
    'reqs.title': 'What you will need',
    'reqs.r1': 'Windows 10 or 11, 64-bit — for the ready-made installer',
    'reqs.r2': 'JDK 21 — to build the launcher from source on Linux and macOS',
    'reqs.r3': 'Java for the game is installed by the launcher, no manual hunting needed',
    'reqs.r4': 'Access to the AlpheusWorld organization: the repository is private',
    'reqs.title2': 'How a release is verified',
    'reqs.v1': 'The MSI and EXE are Authenticode-signed and the signature is checked before publishing',
    'reqs.v2': 'update.json carries the SHA-256 of the build',
    'reqs.v3': 'Updates come from an HTTPS feed; releases of the private repository are available to AlpheusWorld organization members',
    'faq.eyebrow': 'Questions',
    'faq.title': 'Common questions',
    'faq.q1': 'Do I need a Minecraft license?',
    'faq.a1': 'For official servers, yes — sign in with Microsoft. An offline account is enough for servers with online-mode=false and for singleplayer.',
    'faq.q2': 'Where does the launcher keep its files?',
    'faq.a2': 'In the .aw folder: accounts, settings, game folders and instances. Each profile keeps its own mods, worlds, resource packs, shaders and configs, so instances never overlap.',
    'faq.q3': 'How do I move instances from another launcher?',
    'faq.a3': 'New instance → import from folder. Point at a profile, .minecraft or a shared instances directory — AWLauncher detects Prism, MultiMC, Modrinth, CurseForge or ATLauncher and copies the contents. Playtime and logs come along, and hours are never counted twice.',
    'faq.q4': 'What about the CurseForge catalog?',
    'faq.a4': 'It needs the official AlpheusWorld API key: set it in the environment as AW_CURSEFORGE_API_KEY or drop it into a file in your user profile. It travels in a header, not in the URL. Without a key the CurseForge catalog stays quiet while Modrinth works as usual.',
    'faq.q5': 'The game crashes — where do I look?',
    'faq.a5': 'At the instance log: the launcher parses the game output and points at the cause. Check the profile files and the memory limit first — those are the two most common reasons.',
    'faq.q6': 'How much memory should I allocate?',
    'faq.a6': 'The launcher suggests a value for your machine. Big modded profiles with a hundred mods usually do fine with 4–8 GB; handing over all of your RAM is a bad idea.',
    'dl.eyebrow': 'Windows release',
    'dl.title': 'Download AWLauncher',
    'dl.lead': 'A .msi installer for Windows 10 and 11 (64-bit), signed with Authenticode. On Linux and macOS the launcher builds from source — commands are in the Install section.',
    'dl.btn': 'Download .msi',
    'dl.fact1b': '~45 MB',
    'dl.repo': 'Repository on GitHub',
    'dl.fact2': 'installer with no extra downloads',
    'dl.fact3': 'Data in the .aw folder',
    'foot.tagline': 'A Minecraft Java Edition launcher for Windows: versions, loaders, content catalogs and a separate folder for every instance.',
    'foot.note': 'This project is not affiliated with Mojang Studios or Microsoft. Minecraft is a trademark of Mojang Studios.',
    'foot.colProduct': 'Product',
    'foot.colMore': 'Details',
    'foot.colLinks': 'Links',
    'foot.releases': 'Releases',
    'foot.repo': 'Repository',
    'foot.readme': 'Documentation',
    'foot.auth': 'MinecraftAuth',
    'foot.rights': '© 2026 AlpheusWorld. Onest and Noto fonts are SIL OFL, licenses ship with the build.',
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
    fabric: {
      name: 'Fabric',
      tag: { ru: 'под моды и оптимизацию', en: 'for mods and performance' },
      text: {
        ru: 'Максимум модов на новую версию игры: Fabric успевает за снапшотами и почти не добавляет нагрузки. Оптимизационные моды и шейдеры встают без конфликтов.',
        en: 'The most mods on the newest game version: Fabric keeps up with snapshots and adds almost no overhead. Performance mods and shaders drop in without conflicts.'
      },
      loader: 'fabric-loader-0.16.5', java: '21', mods: '148'
    },
    forge: {
      name: 'Forge',
      tag: { ru: 'для крупных сборок', en: 'for large packs' },
      text: {
        ru: 'Самый большой выбор старых и новых модов. Ставь, когда сборка собрана под Forge: индустрия, магия и техно-моды чаще всего живут именно здесь.',
        en: 'The largest pool of old and new mods. Use it when a pack is built for Forge: industry, magic and tech mods mostly live here.'
      },
      loader: 'forge-47.2.20', java: '17', mods: '212'
    },
    neoforge: {
      name: 'NeoForge',
      tag: { ru: 'современная ветка Forge', en: 'the modern Forge branch' },
      text: {
        ru: 'Наследник Forge для 1.20.2 и новее: те же моды, обновлённое ядро и загрузка быстрее. Хороший выбор, если нужна свежая версия игры без потери совместимости с модами.',
        en: 'The Forge successor for 1.20.2 and newer: same kind of mods, a refreshed core and faster loading. A good pick when you want a recent game version without losing mod support.'
      },
      loader: 'neoforge-21.1.72', java: '21', mods: '96'
    },
    quilt: {
      name: 'Quilt',
      tag: { ru: 'легковесная альтернатива', en: 'a lightweight alternative' },
      text: {
        ru: 'Форк Fabric с более гибким API. Ставится поверх большинства Fabric-модов и пригодится, если хочется попробовать другой набор библиотек без переезда.',
        en: 'A Fabric fork with a more flexible API. Most Fabric mods still work, which makes it handy when you want a different set of libraries without moving anywhere.'
      },
      loader: 'quilt-loader-0.23.1', java: '21', mods: '64'
    }
  };

  var ruSnapshot = {};
  var ruAttrSnapshot = {};
  var currentLang = 'ru';

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
  var THEME_COLORS = { light: '#F2F6F3', dark: '#111114', oled: '#000000', system: '#111114' };
  var themeButtons = $$('#themePicker .theme-btn');

  function setTheme(theme) {
    document.documentElement.dataset.theme = theme;
    themeButtons.forEach(function (btn) {
      btn.setAttribute('aria-checked', String(btn.getAttribute('data-theme-value') === theme));
    });
    var meta = $('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', THEME_COLORS[theme] || THEME_COLORS.dark);
    try { localStorage.setItem('aw-theme', theme); } catch (e) {}
  }

  themeButtons.forEach(function (btn) {
    btn.addEventListener('click', function () { setTheme(btn.getAttribute('data-theme-value')); });
  });

  /* ══════════════════════════════════════════════════════════
     3. МЕНЮ
     ══════════════════════════════════════════════════════════ */
  var burger = $('#burger');
  var menu = $('#menu');
  var menuTimer = null;

  function openMenu() {
    if (!menu) return;
    clearTimeout(menuTimer);
    menu.hidden = false;
    /* принудительный пересчёт, чтобы отработал переход */
    void menu.offsetHeight;
    menu.classList.add('is-open');
    if (burger) burger.setAttribute('aria-expanded', 'true');
    document.body.classList.add('is-locked');
    var first = $('.menu__list a', menu);
    if (first) first.focus({ preventScroll: true });
  }

  function closeMenu(returnFocus) {
    if (!menu) return;
    menu.classList.remove('is-open');
    if (burger) burger.setAttribute('aria-expanded', 'false');
    document.body.classList.remove('is-locked');
    clearTimeout(menuTimer);
    menuTimer = setTimeout(function () { menu.hidden = true; }, reduced ? 0 : 520);
    if (returnFocus && burger) burger.focus({ preventScroll: true });
  }

  if (burger && menu) {
    burger.addEventListener('click', function () {
      if (menu.hidden) openMenu(); else closeMenu(true);
    });
    $$('.menu a', menu).forEach(function (link) {
      link.addEventListener('click', function () { closeMenu(false); });
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && !menu.hidden) closeMenu(true);
    });
    window.addEventListener('resize', function () {
      if (!menu.hidden && window.innerWidth > 1120) closeMenu(false);
    });
  }

  /* ══════════════════════════════════════════════════════════
     4. REVEAL ПРИ ПРОКРУТКЕ
     ══════════════════════════════════════════════════════════ */
  var revealables = $$('.reveal');
  var revealed = 0;

  function revealAll() {
    revealables.forEach(function (el) { el.classList.add('is-in'); });
  }

  if ('IntersectionObserver' in window && !reduced) {
    var revealObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-in');
          revealed += 1;
          revealObserver.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
    revealables.forEach(function (el) { revealObserver.observe(el); });

    /* Страховка: если наблюдатель почему-то не сработал, показываем всё.
       Скрытый текст хуже отсутствующей анимации. */
    window.setTimeout(function () {
      if (revealed === 0) revealAll();
    }, 1400);
  } else {
    revealAll();
  }

  /* ══════════════════════════════════════════════════════════
     5. НАВИГАЦИЯ: прогресс, «залипание», активный раздел
     ══════════════════════════════════════════════════════════ */
  var nav = $('#nav');
  var progress = $('#navProgress');
  var navLinks = $$('.nav__links a');
  var sections = $$('main section[id]');
  var ticking = false;

  function onScrollFrame() {
    ticking = false;
    var scrolled = window.scrollY || document.documentElement.scrollTop;
    var max = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    if (progress) progress.style.transform = 'scaleX(' + Math.min(1, scrolled / max).toFixed(4) + ')';
    if (nav) nav.classList.toggle('is-stuck', scrolled > 30);

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
     6. МОКАП ЛАУНЧЕРА: журнал и полоса загрузки
     ══════════════════════════════════════════════════════════ */
  var LOG_STEPS = [
    { ru: 'Проверка файлов: 12 480 файлов', en: 'Checking files: 12 480 entries', warn: false },
    { ru: 'Fabric 0.16.5 → Minecraft 1.20.1', en: 'Fabric 0.16.5 → Minecraft 1.20.1', warn: false },
    { ru: 'Java 21 найдена: jdk-21.0.4+7', en: 'Java 21 found: jdk-21.0.4+7', warn: false },
    { ru: 'mods/jei-1.20.1-forge.jar', en: 'mods/jei-1.20.1-forge.jar', warn: false },
    { ru: 'Шейдеры: Complementary 4.7', en: 'Shaders: Complementary 4.7', warn: false },
    { ru: 'gson-2.10.1 не совпала — перекачана', en: 'gson-2.10.1 mismatched — re-downloaded', warn: true },
    { ru: 'Проверка целостности: OK', en: 'Integrity check: OK', warn: false },
    { ru: 'Запуск Minecraft 1.20.1', en: 'Launching Minecraft 1.20.1', warn: false }
  ];

  var logHost = $('#mockLog');
  var mockBar = $('#mockBar');
  var logIndex = 0;
  var logTimer = null;

  function renderLog() {
    if (!logHost) return;
    var visible = reduced ? LOG_STEPS.slice(0, 4) : LOG_STEPS.slice(Math.max(0, logIndex - 4), logIndex);
    logHost.innerHTML = '';
    visible.forEach(function (step) {
      var li = document.createElement('li');
      li.textContent = step[currentLang] || step.ru;
      if (step.warn) li.className = 'is-warn';
      logHost.appendChild(li);
      if (reduced) {
        li.classList.add('is-in');
      } else {
        window.requestAnimationFrame(function () { li.classList.add('is-in'); });
      }
    });
  }

  function tickLog() {
    logIndex += 1;
    if (logIndex > LOG_STEPS.length) logIndex = 1;
    renderLog();
    if (mockBar) mockBar.style.transform = 'scaleX(' + (logIndex / LOG_STEPS.length).toFixed(3) + ')';
  }

  if (logHost) {
    logIndex = 4;
    renderLog();
    if (mockBar) mockBar.style.transform = 'scaleX(' + (logIndex / LOG_STEPS.length).toFixed(3) + ')';
    if (!reduced) logTimer = window.setInterval(tickLog, 1900);
  }

  /* ══════════════════════════════════════════════════════════
     7. ТАЙМЕР СЕССИИ В КАРТОЧКЕ DISCORD
     ══════════════════════════════════════════════════════════ */
  var rpcTimer = $('#rpcTimer');
  if (rpcTimer && !reduced) {
    var seconds = 1 * 3600 + 24 * 60 + 7;
    window.setInterval(function () {
      seconds += 1;
      var h = String(Math.floor(seconds / 3600)).padStart(2, '0');
      var m = String(Math.floor((seconds % 3600) / 60)).padStart(2, '0');
      var s = String(seconds % 60).padStart(2, '0');
      rpcTimer.textContent = h + ':' + m + ':' + s;
    }, 1000);
  }

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
    var rowMods = $('#rowMods');
    if (rowLoader) rowLoader.textContent = data.loader;
    if (rowMc) rowMc.textContent = key === 'forge' ? '1.19.2' : '1.20.1';
    if (rowJava) rowJava.textContent = data.java;
    if (rowMods) rowMods.textContent = data.mods;
    $$('#loaderPicker .loader-tab').forEach(function (tab) {
      var on = tab.getAttribute('data-loader') === key;
      tab.classList.toggle('is-active', on);
      tab.setAttribute('aria-selected', String(on));
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
  var RELEASES_URL = 'https://github.com/AlpheusWorld/AWLauncher/releases/latest';
  var REPO_URL = 'https://github.com/AlpheusWorld/AWLauncher';
  var SOURCE_CMD = 'git clone https://github.com/AlpheusWorld/AWLauncher.git\n' +
    './gradlew packageDistributionForCurrentOS -PnoDeploy';

  function pair(ru, en) { return { ru: ru, en: en }; }

  var SYSTEMS = {
    windows: {
      icon: '#i-windows',
      badge: pair('Windows 10 и 11 · 64-бит', 'Windows 10 and 11 · 64-bit'),
      file: 'AWLauncher-x.x.x.msi',
      rows: [
        [pair('формат', 'format'), 'MSI (Windows Installer)'],
        [pair('размер', 'size'), pair('~45 МБ', '~45 MB')],
        [pair('подпись', 'signature'), 'Authenticode + SHA-256'],
        [pair('Java', 'Java'), pair('ставится лаунчером', 'installed by the launcher')]
      ],
      cmd: null,
      btn: pair('Скачать .msi', 'Download .msi'),
      btnIcon: '#i-download',
      href: RELEASES_URL,
      note: pair(
        'Обновление приходит из лаунчера: update.json содержит SHA-256 сборки. Релизы приватного репозитория доступны участникам организации AlpheusWorld.',
        'Updates arrive from inside the launcher: update.json carries the SHA-256 of the build. Releases of the private repository are available to AlpheusWorld organization members.'
      ),
      steps: [
        [pair('Скачай установщик и запусти', 'Download the installer and run it'),
         pair('Компактное тёмное окно спросит папку и ярлык. Установку ведёт Windows Installer: прогресс, отмена и откат работают штатно, докачивать ничего не нужно.',
              'A compact dark window asks for a folder and a shortcut. Windows Installer does the work: progress, cancel and rollback behave, and nothing extra is downloaded.')],
        [pair('Войди в аккаунт', 'Sign in'),
         pair('«Аккаунты» → «Войти через Microsoft» и подтверждение в браузере. Для серверов с online-mode=false хватит офлайн-профиля.',
              'Accounts → Sign in with Microsoft, then confirm in the browser. For servers with online-mode=false an offline profile is enough.')],
        [pair('Создай сборку', 'Create an instance'),
         pair('Выбери версию и загрузчик, поставь моды из каталога — и жми «Играть». Данные лаунчера живут в папке .aw.',
              'Pick a version and a loader, add mods from the catalog, then hit Play. Launcher data lives in the .aw folder.')]
      ]
    },
    linux: {
      icon: '#i-terminal',
      badge: pair('Linux · сборка из исходников', 'Linux · built from source'),
      file: 'packageDistributionForCurrentOS',
      rows: [
        [pair('формат', 'format'), pair('deb или rpm', 'deb or rpm')],
        [pair('сборка', 'build'), 'jpackage'],
        [pair('нужен', 'required'), 'JDK 21'],
        [pair('готовых пакетов', 'prebuilt packages'), pair('нет', 'none yet')]
      ],
      cmd: SOURCE_CMD,
      btn: pair('Открыть репозиторий', 'Open the repository'),
      btnIcon: '#i-external',
      href: REPO_URL,
      note: pair(
        'Официальных сборок для Linux пока нет: лаунчер запускается из исходников. Gradle идёт в комплекте, отдельно ставить не нужно.',
        'There are no official Linux builds yet: the launcher runs from source. Gradle ships with the project, no separate install needed.'
      ),
      steps: [
        [pair('Поставь JDK 21 и склонируй репозиторий', 'Install JDK 21 and clone the repository'),
         pair('Репозиторий приватный, поэтому нужен доступ к организации AlpheusWorld. Команды для клонирования — в блоке справа.',
              'The repository is private, so you need access to the AlpheusWorld organization. The clone commands are in the block on the right.')],
        [pair('Собери пакет под свой дистрибутив', 'Build a package for your distribution'),
         pair('<code>./gradlew packageDistributionForCurrentOS -PnoDeploy</code> соберёт deb или rpm через jpackage. Параметр <code>-PnoDeploy</code> оставляет уже установленную копию лаунчера нетронутой.',
              '<code>./gradlew packageDistributionForCurrentOS -PnoDeploy</code> builds a deb or rpm through jpackage. The <code>-PnoDeploy</code> flag leaves an already installed copy of the launcher untouched.')],
        [pair('Запусти лаунчер', 'Run the launcher'),
         pair('Быстрая проверка без установки — <code>./gradlew run -PnoDeploy</code>. Для каталога CurseForge задай ключ <code>AW_CURSEFORGE_API_KEY</code>.',
              'A quick check without installing: <code>./gradlew run -PnoDeploy</code>. For the CurseForge catalog set <code>AW_CURSEFORGE_API_KEY</code>.')]
      ]
    },
    macos: {
      icon: '#i-apple',
      badge: pair('macOS · сборка из исходников', 'macOS · built from source'),
      file: 'packageDistributionForCurrentOS',
      rows: [
        [pair('формат', 'format'), pair('dmg', 'dmg')],
        [pair('сборка', 'build'), 'jpackage'],
        [pair('нужен', 'required'), 'JDK 21'],
        [pair('подпись Apple', 'Apple signature'), pair('нет', 'none')]
      ],
      cmd: SOURCE_CMD,
      btn: pair('Открыть репозиторий', 'Open the repository'),
      btnIcon: '#i-external',
      href: REPO_URL,
      note: pair(
        'Сборка не подписана Apple, поэтому при первом запуске Gatekeeper попросит подтвердить открытие приложения в «Настройки → Конфиденциальность и безопасность».',
        'The build is not signed by Apple, so on first launch Gatekeeper will ask you to confirm opening the app in System Settings → Privacy & Security.'
      ),
      steps: [
        [pair('Поставь JDK 21 и склонируй репозиторий', 'Install JDK 21 and clone the repository'),
         pair('Репозиторий приватный: нужен доступ к организации AlpheusWorld. Команды для клонирования — в блоке справа.',
              'The repository is private: you need access to the AlpheusWorld organization. The clone commands are in the block on the right.')],
        [pair('Собери dmg', 'Build the dmg'),
         pair('<code>./gradlew packageDistributionForCurrentOS -PnoDeploy</code> соберёт образ в <code>build/compose/binaries/main/dmg</code>.',
              '<code>./gradlew packageDistributionForCurrentOS -PnoDeploy</code> builds the image into <code>build/compose/binaries/main/dmg</code>.')],
        [pair('Открой приложение', 'Open the app'),
         pair('Первый запуск потребует подтверждения Gatekeeper. Быстрая проверка без упаковки — <code>./gradlew run -PnoDeploy</code>.',
              'The first launch needs a Gatekeeper confirmation. A quick check without packaging: <code>./gradlew run -PnoDeploy</code>.')]
      ]
    }
  };

  var activeOs = 'windows';

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
    if (file) file.textContent = sys.file;

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
    var voxRaf = null;
    var voxActive = false;
    var stageVisible = true;
    var pageVisible = true;

    function voxApply(angle) {
      voxHost.style.transform = 'rotateX(5deg) rotateY(' + angle.toFixed(2) + 'deg)';
    }

    function voxPaint(time) {
      if (!voxActive) { voxRaf = null; return; }
      voxRaf = window.requestAnimationFrame(voxPaint);
      if (voxDrag || reduced) return;
      voxApply(voxAngle + Math.sin(time / 2800) * 22);
    }

    function voxSync() {
      var wanted = stageVisible && pageVisible && !reduced;
      if (wanted === voxActive) return;
      voxActive = wanted;
      if (wanted && voxRaf === null) voxRaf = window.requestAnimationFrame(voxPaint);
    }

    voxApply(voxAngle);

    if ('IntersectionObserver' in window) {
      stageVisible = false;
      new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) { stageVisible = entry.isIntersecting; });
        voxSync();
      }, { threshold: 0.12 }).observe(skinStage);
    }
    voxSync();

    document.addEventListener('visibilitychange', function () {
      pageVisible = document.visibilityState === 'visible';
      voxSync();
    });

    skinStage.addEventListener('pointerdown', function (event) {
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
        if (voxActive && voxRaf === null) voxRaf = window.requestAnimationFrame(voxPaint);
      });
    });

    $$('.seg__btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        skinStage.dataset.model = btn.getAttribute('data-model');
        $$('.seg__btn').forEach(function (other) {
          var on = other === btn;
          other.classList.toggle('is-active', on);
          other.setAttribute('aria-checked', String(on));
        });
      });
    });
  }

  /* ══════════════════════════════════════════════════════════
     11. FAQ
     ══════════════════════════════════════════════════════════ */
  $$('.faq__q').forEach(function (button) {
    button.addEventListener('click', function () {
      var item = button.closest('.faq__item');
      var isOpen = item.classList.contains('is-open');
      $$('.faq__item').forEach(function (other) {
        other.classList.remove('is-open');
        var q = $('.faq__q', other);
        if (q) q.setAttribute('aria-expanded', 'false');
      });
      if (!isOpen) {
        item.classList.add('is-open');
        button.setAttribute('aria-expanded', 'true');
      }
    });
  });

  /* ══════════════════════════════════════════════════════════
     12. КУРСОРНЫЙ БЛИК ПО КАРТОЧКАМ
     ══════════════════════════════════════════════════════════ */
  if (window.matchMedia('(hover: hover)').matches) {
    $$('.spotlight').forEach(function (card) {
      card.addEventListener('pointermove', function (event) {
        var rect = card.getBoundingClientRect();
        card.style.setProperty('--mx', (event.clientX - rect.left) + 'px');
        card.style.setProperty('--my', (event.clientY - rect.top) + 'px');
      });
    });
  }

  /* ══════════════════════════════════════════════════════════
     13. МАГНИТНЫЕ КНОПКИ
     ══════════════════════════════════════════════════════════ */
  if (!reduced && window.matchMedia('(hover: hover) and (min-width: 900px)').matches) {
    $$('.magnetic').forEach(function (btn) {
      var raf = null;
      var target = { x: 0, y: 0 };

      function paint() {
        raf = null;
        btn.style.transform = 'translate3d(' + target.x.toFixed(2) + 'px,' + target.y.toFixed(2) + 'px,0)';
      }

      btn.addEventListener('pointermove', function (event) {
        var rect = btn.getBoundingClientRect();
        target.x = (event.clientX - (rect.left + rect.width / 2)) * 0.16;
        target.y = (event.clientY - (rect.top + rect.height / 2)) * 0.24;
        if (!raf) raf = window.requestAnimationFrame(paint);
      });

      btn.addEventListener('pointerleave', function () {
        target.x = 0;
        target.y = 0;
        if (!raf) raf = window.requestAnimationFrame(paint);
      });
    });
  }

  /* ══════════════════════════════════════════════════════════
     14. БЕСШОВНЫЕ БЕГУЩИЕ СТРОКИ
     ══════════════════════════════════════════════════════════ */
  $$('.marquee__track, .locale-band__track').forEach(function (track) {
    var clone = track.cloneNode(true);
    clone.setAttribute('aria-hidden', 'true');
    while (clone.firstChild) track.appendChild(clone.firstChild);
  });

  /* ══════════════════════════════════════════════════════════
     15. СТАРТ
     ══════════════════════════════════════════════════════════ */
  var savedLang = null;
  try { savedLang = localStorage.getItem('aw-lang'); } catch (e) {}
  var attrLang = document.documentElement.getAttribute('data-lang');
  collectRu();
  applyLang(savedLang === 'en' || attrLang === 'en' ? 'en' : 'ru');

  var savedTheme = document.documentElement.dataset.theme || 'dark';
  setTheme(savedTheme);
})();
