#!/usr/bin/env python3
"""Real CPU inference benchmark; no calendar writes or network inference."""
import argparse
import json
import platform
import resource
import statistics
import time
from pathlib import Path

FIELDS = ('title', 'date', 'start', 'end_date', 'end', 'repeat', 'clarify')
SYSTEM = '''Extract a calendar event. Return ONLY a JSON object with exactly these keys:
{"title":string|null,"date":"YYYY-MM-DD"|null,"start":"HH:MM"|null,"end_date":"YYYY-MM-DD"|null,"end":"HH:MM"|null,"repeat":"none"|"daily"|"weekdays"|"weekly"|null,"clarify":boolean}
Today is Thursday 2026-10-08. Local timezone is America/Regina. Interpret dates locally.
No date means today. No end means 60 minutes after start. A date without a time means all-day: start and end are null and end_date equals date. No date AND no time requires clarification.
"3 to 5pm" means 15:00 to 17:00. A bare time without am/pm or a 24-hour cue requires clarification. "noon"=12:00 and "midnight"=00:00. An end earlier than start is on the following day.
Weekday names mean their next occurrence. "next Friday" means 2026-10-09. For repeating events use the first matching date on or after today.
Keep the event title, but remove date/time/repeat phrases and polite request words. Do not invent a location or other details.
If information is ambiguous, contradictory, or this is not a creation request, return all six other fields null and clarify true. Otherwise clarify false.'''
EXAMPLES = [
    ('Yoga tomorrow at 8am', dict(title='Yoga', date='2026-10-09', start='08:00', end_date='2026-10-09', end='09:00', repeat='none', clarify=False)),
    ('Movie Saturday from 8pm to 10pm', dict(title='Movie', date='2026-10-10', start='20:00', end_date='2026-10-10', end='22:00', repeat='none', clarify=False)),
    ('Water plants every day at 7am', dict(title='Water plants', date='2026-10-08', start='07:00', end_date='2026-10-08', end='08:00', repeat='daily', clarify=False)),
    ('Lunch tomorrow at 1', {**dict.fromkeys(FIELDS), 'clarify':True}),
]


def decode_result(raw):
    """Allow harmless code fences, but do not repair values or extract hidden JSON."""
    cleaned = raw.strip()
    if cleaned.startswith('```') and cleaned.endswith('```'):
        cleaned = '\n'.join(cleaned.splitlines()[1:-1])
    try:
        obj = json.loads(cleaned)
    except (ValueError, TypeError):
        return None
    if not isinstance(obj, dict) or set(obj) != set(FIELDS):
        return None
    if type(obj['clarify']) is not bool:
        return None
    if any(obj[k] is not None and not isinstance(obj[k], str) for k in FIELDS[:-1]):
        return None
    return obj


def matches(actual, expected):
    if actual is None:
        return {key:False for key in FIELDS}
    return {key: (actual[key].strip().casefold() == expected[key].strip().casefold()
                  if isinstance(actual[key], str) and isinstance(expected[key], str)
                  else actual[key] == expected[key]) for key in FIELDS}


def percentile(values, fraction):
    return sorted(values)[max(0, int(len(values) * fraction + 0.999) - 1)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', default='HuggingFaceTB/SmolLM2-135M-Instruct')
    parser.add_argument('--revision', default='main')
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--limit', type=int)
    parser.add_argument('--prompts', nargs='+', choices=('zero_shot', 'few_shot'), default=['zero_shot', 'few_shot'])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    import torch
    import transformers
    from huggingface_hub import model_info
    from transformers import AutoTokenizer, AutoModelForCausalLM
    torch.set_num_threads(args.threads)
    torch.set_num_interop_threads(1)
    torch.manual_seed(0)
    revision = model_info(args.model, revision=args.revision).sha
    started = time.perf_counter()
    tokenizer = AutoTokenizer.from_pretrained(args.model, revision=revision)
    model = AutoModelForCausalLM.from_pretrained(args.model, revision=revision, dtype=torch.float32).to('cpu').eval()
    load_seconds = time.perf_counter() - started
    print(f'Loaded {args.model}@{revision} in {load_seconds:.2f}s', flush=True)
    cases = json.loads(Path(__file__).with_name('cases.json').read_text())
    if args.limit:
        cases = cases[:args.limit]
    rows = []
    meta = dict(model=args.model, revision=revision, cpu=platform.processor(), platform=platform.platform(),
                python=platform.python_version(), torch=torch.__version__, transformers=transformers.__version__,
                threads=args.threads, dtype='float32', quantized=False, load_seconds=load_seconds,
                parameter_count=sum(p.numel() for p in model.parameters()),
                parameter_bytes=sum(p.numel()*p.element_size() for p in model.parameters()),
                generation=dict(do_sample=False, max_new_tokens=180),
                context=dict(today='2026-10-08', timezone='America/Regina', default_duration_minutes=60))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Warm up separately so initialization is not charged to the first case.
    warmup = tokenizer('Hello', return_tensors='pt')
    with torch.inference_mode():
        model.generate(**warmup, max_new_tokens=4, do_sample=False, pad_token_id=tokenizer.eos_token_id)
    for style in args.prompts:
        for case in cases:
            messages = [{'role':'system', 'content':SYSTEM}]
            if style == 'few_shot':
                for text, answer in EXAMPLES:
                    messages.extend([{'role':'user', 'content':text}, {'role':'assistant', 'content':json.dumps(answer)}])
            messages.append({'role':'user', 'content':case['text']})
            inputs = tokenizer.apply_chat_template(messages, add_generation_prompt=True, return_tensors='pt', return_dict=True)
            started = time.perf_counter()
            with torch.inference_mode():
                output = model.generate(**inputs, max_new_tokens=180, do_sample=False, pad_token_id=tokenizer.eos_token_id)
            seconds = time.perf_counter() - started
            generated = output[0, inputs['input_ids'].shape[1]:]
            raw = tokenizer.decode(generated, skip_special_tokens=True)
            actual = decode_result(raw)
            fields = matches(actual, case['expected'])
            row = dict(id=case['id'], category=case['category'], prompt=style, text=case['text'],
                       expected=case['expected'], actual=actual, raw=raw, field_matches=fields,
                       passed=all(fields.values()), valid_schema=actual is not None,
                       schedule_correct=all(fields[k] for k in FIELDS if k != 'title'),
                       seconds=seconds, input_tokens=inputs['input_ids'].shape[1], output_tokens=len(generated),
                       hit_token_limit=len(generated) >= 180)
            rows.append(row)
            print(f"{style} {case['id']}: {'PASS' if row['passed'] else 'FAIL'} {seconds:.2f}s {raw[:180]}", flush=True)
            args.output.write_text(json.dumps(dict(metadata=meta, system_prompt=SYSTEM, examples=EXAMPLES, results=rows), indent=2)+'\n')
    summaries = {}
    for style in args.prompts:
        selected = [r for r in rows if r['prompt'] == style]
        summaries[style] = dict(total=len(selected), exact_passes=sum(r['passed'] for r in selected),
            valid_schema=sum(r['valid_schema'] for r in selected), schedule_correct=sum(r['schedule_correct'] for r in selected),
            median_seconds=statistics.median(r['seconds'] for r in selected), p95_seconds=percentile([r['seconds'] for r in selected], .95),
            fields={key:sum(r['field_matches'][key] for r in selected) for key in FIELDS},
            categories={cat:dict(total=sum(r['category']==cat for r in selected), passes=sum(r['passed'] for r in selected if r['category']==cat)) for cat in sorted(set(r['category'] for r in selected))})
    meta['peak_process_rss_bytes'] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024
    args.output.write_text(json.dumps(dict(metadata=meta, system_prompt=SYSTEM, examples=EXAMPLES, summary=summaries, results=rows), indent=2)+'\n')
    print(json.dumps(summaries, indent=2), flush=True)

if __name__ == '__main__':
    main()
