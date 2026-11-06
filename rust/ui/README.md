# Интерфейс AWLauncher на Rust

В версии 1.0.50 Rust-интерфейс входит в обычное приложение AWLauncher. Он читает те же сборки, настройки и историю из `.aw`. Упакованный Rust-движок выполняет запуск Minecraft, авторизацию, установку и проверку файлов, разрешение зависимостей модов и восстановление снимков.

В интерфейс перенесены главная страница, библиотека, настройки, статистика, аккаунты, уведомления, скриншоты, каталоги Modrinth и CurseForge, страницы проектов и очередь загрузок. Страница сборки включает контент с переключателями и массовыми действиями, файлы, миры, журнал, статистику и историю. Описания проектов используют CommonMark-парсер и обработку SVG. Переводы берутся из исходных словарей лаунчера. Раздел «Друзья» отключён.

## Сборка

Нужны JDK 21, стабильный Rust, Python 3 и зависимости рабочего стола для Linux. Сборка приложения из корня репозитория:

```powershell
.\gradlew.bat createDistributable -PnoDeploy
```

В Linux используется `./gradlew`. Gradle собирает Rust-бинарник AWLauncher. Для разработки отдельного окна:

```powershell
cargo run --manifest-path rust/ui/Cargo.toml -- --backend "ПУТЬ-К-ПАПКЕ-ПРИЛОЖЕНИЯ/AWLauncher.exe"
```

Указанная папка должна быть собрана через `createDistributable`: адаптер запускает основной Rust-бинарник с протоколом `--native-task`.

## Проверки

```powershell
cargo test --locked --manifest-path rust/ui/Cargo.toml
cargo test --locked --manifest-path rust/engine/Cargo.toml
xvfb-run -a dbus-run-session -- python3 tools/tests/rust_linux_smoke.py /opt/awlauncher/bin/AWLauncher
```

Последняя команда проверяет изменения контента и историю в отдельном временном профиле. Явно включаемые сетевые smoke-тесты помечены `ignored` и запускаются отдельно.
