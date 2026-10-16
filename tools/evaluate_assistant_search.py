"""Offline retrieval evaluation. Model files and server caches are explicit CLI inputs."""
import argparse
import json
import pathlib
import time

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


def chunks(rule):
    header = rule['title'] + '. ' + ', '.join(rule.get('keywords', [])) + '\n'
    header = header[:220]
    text = rule['description'] + '\n' + rule.get('sanction', '')
    return [header] + [header + text[start:start + 650] for start in range(0, len(text), 500)]


def evaluate(model, tokenizer_path, books, cases, retrieval):
    options = ort.SessionOptions()
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    options.add_session_config_entry('session.intra_op.allow_spinning', '0')
    session = ort.InferenceSession(str(model), sess_options=options, providers=['CPUExecutionProvider'])
    names = {i.name for i in session.get_inputs()}
    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    tokenizer.enable_truncation(max_length=256 if retrieval else 128)
    timings = []

    def embed(text, query=False):
        if retrieval:
            text = ('search_query: ' if query else 'search_document: ') + text
        encoded = tokenizer.encode(text)
        inputs = {'input_ids': np.asarray([encoded.ids], dtype=np.int64),
                  'attention_mask': np.asarray([encoded.attention_mask], dtype=np.int64)}
        if 'token_type_ids' in names:
            inputs['token_type_ids'] = np.asarray([encoded.type_ids], dtype=np.int64)
        start = time.perf_counter()
        output = session.run(None, inputs)[0][0]
        if query:
            timings.append((time.perf_counter() - start) * 1000)
        vector = output.mean(axis=0) if retrieval else output[0]
        return vector / max(1e-9, np.linalg.norm(vector))

    indexed = {}
    for server, book in books.items():
        docs, numbers = [], []
        for rule in book['rules']:
            passages = chunks(rule) if retrieval else [(rule['title'] + '. ' + rule['description'] + ' ' + ' '.join(rule.get('keywords', [])))[:1800]]
            for text in passages:
                docs.append(embed(text))
                numbers.append(rule['number'])
        indexed[server] = (np.asarray(docs), numbers)

    rows = []
    for case in cases:
        vectors, numbers = indexed[case['server']]
        scores = vectors @ embed(case['query'], True)
        per_rule = {}
        for number, score in zip(numbers, scores):
            per_rule[number] = max(per_rule.get(number, -1), float(score))
        ordered = sorted(per_rule.items(), key=lambda pair: pair[1], reverse=True)
        rows.append(dict(case, ranked=ordered))

    def metrics(split, threshold, margin):
        selected = [r for r in rows if r['split'] == split]
        positives = [r for r in selected if r['expected']]
        negatives = [r for r in selected if not r['expected']]
        def accepted(row):
            best = row['ranked'][0][1]
            second = row['ranked'][1][1] if len(row['ranked']) > 1 else -1
            return best >= threshold and best - second >= margin
        top1 = sum(accepted(r) and r['ranked'][0][0] in r['expected'] for r in positives) / len(positives)
        top3 = sum(accepted(r) and any(n in r['expected'] for n, _ in r['ranked'][:3]) for r in positives) / len(positives)
        reject = sum(not accepted(r) for r in negatives) / len(negatives)
        return {'top1': top1, 'top3': top3, 'reject_unanswerable': reject,
                'positive_cases': len(positives), 'negative_cases': len(negatives)}

    best = None
    for threshold in np.arange(.35, .91, .01):
        for margin in [0, .01, .02, .03, .04, .06]:
            result = metrics('validation', float(threshold), margin)
            objective = (result['top1'] + result['reject_unanswerable']) / 2
            if best is None or objective > best[0]:
                best = (objective, float(threshold), margin)
    _, threshold, margin = best
    return {'model': str(model), 'threshold': threshold, 'margin': margin,
            'validation': metrics('validation', threshold, margin),
            'holdout': metrics('holdout', threshold, margin),
            'median_query_ms': float(np.median(timings)), 'p95_query_ms': float(np.quantile(timings, .95)),
            'cases': rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--corpus', type=pathlib.Path, required=True)
    parser.add_argument('--old-model', type=pathlib.Path, required=True)
    parser.add_argument('--new-model', type=pathlib.Path, required=True)
    parser.add_argument('--tokenizer', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    bundled = root / 'client-mod/src/main/resources/assets/awassistant/rules'
    books = {}
    for file in bundled.glob('*.json'):
        short = json.loads(file.read_text('utf-8'))
        cache = args.corpus / file.name
        book = json.loads(cache.read_text('utf-8')) if cache.exists() else short
        references = {r['number']: r for r in short['rules']}
        for rule in book['rules']:
            reference = references.get(rule['number'])
            if reference:
                rule['title'] = reference['title']
                rule['keywords'] = list(dict.fromkeys(rule.get('keywords', []) + reference.get('keywords', [])))
        books[book['id']] = book
    cases = json.loads((root / 'client-mod/src/test/resources/search-evaluation.json').read_text('utf-8'))
    existing_queries = {c['query'].casefold() for c in cases}
    for server, book in books.items():
        unique = {}
        for rule in book['rules']:
            for keyword in rule.get('keywords', []):
                if 3 <= len(keyword) <= 80:
                    unique.setdefault(keyword.casefold(), []).append(rule['number'])
        for keyword, numbers in unique.items():
            if len(set(numbers)) == 1 and keyword not in existing_queries:
                cases.append({'server': server, 'query': keyword, 'expected': list(set(numbers)), 'split': 'training'})
    for case in cases:
        actual = {r['number'] for r in books[case['server']]['rules']}
        if not set(case['expected']) <= actual:
            raise ValueError('Expected rule is missing from corpus: ' + str(case))
    results = []
    for model, retrieval in [(args.old_model, False), (args.new_model, True)]:
        result = evaluate(model, args.tokenizer, books, cases, retrieval)
        results.append(result)
        print(json.dumps({k: v for k, v in result.items() if k != 'cases'}, ensure_ascii=False), flush=True)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__':
    main()
