"""Generate bundled locale files; never used by the running launcher.

Development dependency: translatepy 2.3; opencc-python-reimplemented for zh-TW.
Run from the repository root. Completed batches are cached for safe resuming.
"""
import concurrent.futures
import json
import re
import time
import html
from pathlib import Path

from translatepy.translators.yandex import YandexTranslate
from translatepy.utils.request import Request
import requests

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / "src/main/resources/i18n"
CACHE = ROOT / "build/locale-generation"
LANGUAGES = {
    "de-DE": "de", "fr-FR": "fr", "pt-BR": "pt", "it-IT": "it", "nl-NL": "nl", "pl-PL": "pl", "tr-TR": "tr",
    "zh-CN": "zh", "ja-JP": "ja", "id-ID": "id", "fa-IR": "fa", "cs-CZ": "cs", "vi-VN": "vi", "ko-KR": "ko",
    "uk-UA": "uk", "ar-SA": "ar", "hu-HU": "hu", "sv-SE": "sv", "ro-RO": "ro", "el-GR": "el", "da-DK": "da",
    "fi-FI": "fi", "he-IL": "he", "sk-SK": "sk", "th-TH": "th", "bg-BG": "bg", "hr-HR": "hr", "sr-RS": "sr",
    "nb-NO": "no", "lt-LT": "lt", "sl-SI": "sl", "ca-ES": "ca", "et-EE": "et", "lv-LV": "lv", "bn-BD": "bn",
    "hi-IN": "hi", "bs-BA": "bs", "az-AZ": "az", "ka-GE": "ka", "is-IS": "is", "ms-MY": "ms", "kk-KZ": "kk", "hy-AM": "hy",
}

original_request = requests.Session.request
def bounded_request(self, *args, **kwargs):
    kwargs.setdefault("timeout", (10, 45))
    return original_request(self, *args, **kwargs)
requests.Session.request = bounded_request

def write(path, values):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(path)

def slots(value):
    return set(re.findall(r"\{\d+\}", value))

def normalize(value):
    value = html.unescape(re.sub(r'</?span\b[^>]*>', '', value))
    value = re.sub(r"\{\s*(\d+)\s*\}", lambda m: "{" + str(int(m[1])) + "}", value)
    return value.replace("\u200e", "").replace("\u200f", "").strip()

def generate(tag, target, source):
    path = CACHE / (tag + ".json")
    saved = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    pending = [(key, value) for key, value in source.items() if key not in saved or slots(saved[key]) != slots(key)]
    translator = YandexTranslate(request=Request())
    while pending:
        batch = []
        size = 0
        for item in pending:
            if batch and size + len(item[1]) > 7000: break
            batch.append(item)
            size += len(item[1])
            if len(batch) >= 90: break
        result = None
        for attempt in range(3):
            try:
                response = translator.session.post(translator._api_url.format(endpoint="translate"),
                    params={"ucid": translator._ucid(), "srv": "android", "format": "html"},
                    data=[("text", re.sub(r"\{\d+\}", lambda m: '<span translate="no">' + m[0] + '</span>', html.escape(text))) for _, text in batch] + [("lang", "en-" + target)])
            except (requests.Timeout, requests.ConnectionError):
                if attempt == 2: raise
                time.sleep(2 + attempt)
                continue
            payload = response.json()
            if response.status_code == 200 and payload.get("code") == 200:
                result = payload.get("text")
                break
            if response.status_code in (401, 403, 429) or payload.get("code") in (401, 402, 403, 404, 408):
                raise RuntimeError(f"Translation service refused {tag}: HTTP {response.status_code}, code {payload.get('code')}")
            time.sleep(2 + attempt)
        if not isinstance(result, list) or len(result) != len(batch):
            raise RuntimeError(f"Invalid translation batch for {tag}")
        for (key, text), translated in zip(batch, result):
            translated = normalize(translated)
            if not translated or slots(translated) != slots(key):
                raise RuntimeError(f"Invalid placeholders for {tag}: {key} -> {translated}")
            saved[key] = translated
        write(path, saved)
        pending = pending[len(batch):]
        time.sleep(0.25)
    write(DIRECTORY / (tag + ".json"), {key: saved[key] for key in source})
    print(f"Completed {tag}: {len(source)} strings", flush=True)

def main():
    source = json.loads((DIRECTORY / "en.json").read_text(encoding="utf-8"))
    failures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = {pool.submit(generate, tag, target, source): tag for tag, target in LANGUAGES.items()}
        for future in concurrent.futures.as_completed(futures):
            try: future.result()
            except Exception as error:
                failures.append(futures[future])
                print(f"Failed {futures[future]}: {error}", flush=True)
    if failures: raise RuntimeError("Incomplete languages: " + ", ".join(failures))
    def british_word(match):
        word = match[0].lower()
        replacement = {"favorite": "favourite", "favorites": "favourites", "color": "colour", "catalog": "catalogue", "behavior": "behaviour"}[word]
        return replacement.capitalize() if match[0][0].isupper() else replacement
    british = {key: re.sub(r"\b(favorites?|color|catalog|behavior)\b", british_word, value, flags=re.IGNORECASE) for key, value in source.items()}
    write(DIRECTORY / "en-GB.json", british)
    spanish = json.loads((DIRECTORY / "es.json").read_text(encoding="utf-8"))
    write(DIRECTORY / "es-MX.json", {key: value.replace("ordenador", "computadora").replace("Ordenador", "Computadora") for key, value in spanish.items()})
    portugal = json.loads((DIRECTORY / "pt-BR.json").read_text(encoding="utf-8"))
    for key, value in portugal.items():
        for old, new in [("Arquivos", "Ficheiros"), ("arquivos", "ficheiros"), ("Arquivo", "Ficheiro"), ("arquivo", "ficheiro"), ("Configurações", "Definições"), ("configurações", "definições"), ("Baixar", "Transferir"), ("baixar", "transferir"), ("usuário", "utilizador"), ("tela", "ecrã"), ("Salvar", "Guardar"), ("salvar", "guardar")]: value = value.replace(old, new)
        portugal[key] = value
    write(DIRECTORY / "pt-PT.json", portugal)
    from opencc import OpenCC
    converter = OpenCC("s2t")
    chinese = json.loads((DIRECTORY / "zh-CN.json").read_text(encoding="utf-8"))
    write(DIRECTORY / "zh-TW.json", {key: converter.convert(value).replace('爲', '為') for key, value in chinese.items()})

if __name__ == "__main__": main()
