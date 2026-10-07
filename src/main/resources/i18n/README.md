# UI languages

New launcher behavior and cape strings were translated with Google Translate through the same development-only `translatepy` library when Yandex was unavailable. These additions are also machine translations pending native-speaker review. Existing translations are retained; font subsets are extended when a new string requires an additional character.

Russian is the canonical source text; `en.json` is the reference dictionary. `Language` in `core/Settings.kt` declares 50 interface locales, with regional variants counted separately. English (United States) is the default for new preferences; existing serialized EN, ES and RU preferences remain compatible.

Each bundled dictionary contains every reference key and preserves numbered placeholders. Loading and rendering do not contact translation services. Dictionaries are loaded on demand and UI text is cached.

The added dictionaries were generated with Yandex Translate through the development-only [translatepy](https://github.com/Animenosekai/translate) library (2.3). They are machine translations, pending native-speaker review. UK English, Latin American Spanish and Portuguese from Portugal include regional wording adjustments; Traditional Chinese is converted with [OpenCC](https://github.com/BYVoid/OpenCC). Keep product names, file paths and placeholders unchanged when reviewing translations.

To regenerate, install `translatepy==2.3` and `opencc-python-reimplemented==0.1.7` in a development environment and run `python tools/generate_translations.py` from the repository root. Add new keys to both `en.json` and `es.json` first. Batches are cached in `build/locale-generation`; delete a locale's cache to regenerate existing values. Service refusals and placeholder mismatches stop generation instead of silently inserting English text.

`tools/generate_fonts.py` builds script-specific subsets from the [Google Fonts repository](https://github.com/google/fonts), with SIL OFL licenses alongside each font. It requires a JDK in JAVA_HOME, `fonttools` and `requests`. It includes locale display names and UI glyphs; missing Latin/Cyrillic symbols are sourced from bundled Onest. Modified families use an AW prefix. Regenerate fonts after adding translated characters.

Verification: `gradlew test -PnoDeploy` checks all dictionary keys and placeholders. The `renderScreens` task can render settings and language lists without opening the desktop launcher.
