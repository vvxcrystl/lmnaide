"""Summarize saved runs without running inference again."""
import json
from pathlib import Path

root=Path(__file__).parent
runs={size:json.loads((root/'results'/f'{size}m.json').read_text()) for size in (135,360)}
extractions={size:json.loads((root/'results'/f'{size}m-extraction.json').read_text()) for size in (135,360)}
lines=['# SmolLM2 feasibility results','',
'Executed locally on October 8, 2026, on an AMD Ryzen 7 7700 (8 cores / 16 threads), with four CPU inference threads and float32 weights. All inference was local. See README.md for rerun instructions and evaluation policy.','',
'## Full event creation','',
'Exact success requires the correct title, dates, times, recurrence, and clarification decision. The fixed reference date was October 8, 2026. These are manually specified expected answers, not another model judging the output.','',
'| Model | Prompt | Exact events | Correct schedule excluding title | Structured objects* | Median | P95 |',
'|---|---|---:|---:|---:|---:|---:|']
for size,run in runs.items():
    for prompt,summary in run['summary'].items():
        s=summary
        lines.append(f"| {size}M | {prompt} | {s['exact_passes']}/{s['total']} | {s['schedule_correct']}/{s['total']} | {s['valid_schema']}/{s['total']} | {s['median_seconds']:.2f}s | {s['p95_seconds']:.2f}s |")
lines += ['', '*Structured objects means valid JSON with all seven expected keys and their basic types. It does not imply valid ISO dates, permitted recurrence values, or correct event details. Those errors fail exact-event scoring.', '',
'## Easier extraction task','',
'This asks only for title and literal date/time phrases, with three worked examples. It removes date arithmetic, recurrence, and end-time calculation. Exact span scoring includes prepositions, so an otherwise correct `4pm` instead of `at 4pm` fails. This is a stricter metric than semantic equivalence.','',
'| Model | Exact extraction | Median | P95 |','|---|---:|---:|---:|']
for size,run in extractions.items():
    s=run['summary']
    lines.append(f"| {size}M | {s['exact_passes']}/{s['total']} | {s['median_seconds']:.2f}s | {s['p95_seconds']:.2f}s |")
audit=json.loads((root/'results'/'extraction-semantic-review.json').read_text())
lines += ['', '### Manual semantic review', '', audit['method'], '',
          '| Model | Semantically correct phrase extraction |', '|---|---:|']
for size in (135,360):
    entry=audit[f'{size}M']
    lines.append(f"| {size}M | {entry['semantic_passes']}/{entry['total']} |")
lines += ['', 'The 360M model is more promising as a phrase extractor: its only semantic failure here placed `at 7pm` into the date field and left time null. Its response to your Work example extracted Work / tomorrow / from 3pm to 5pm correctly. This narrow subset does not test ambiguity, recurrence, duration, overnight handling, or informal abbreviations, and needs a larger unseen evaluation before shipping. The audit is saved separately in results/extraction-semantic-review.json.', '']
lines += ['', '## Your example','', '`Work from 3 to 5pm tomorrow` should become Work, October 9, 2026, 15:00–17:00, no repeat.', '']
for size,run in runs.items():
    row=next(r for r in run['results'] if r['prompt']=='few_shot' and r['id']=='case_01')
    lines += [f'### {size}M response with examples','', '```json',row['raw'],'```','']
lines += ['## Desktop resource measurements','',
'| Model | Downloaded weights | Float32 parameter tensors | Peak whole-process RSS |','|---|---:|---:|---:|']
for size,run in runs.items():
    m=run['metadata']
    p=Path.home()/'.cache'/'huggingface'/'hub'/f'models--HuggingFaceTB--SmolLM2-{size}M-Instruct'/'snapshots'/m['revision']/'model.safetensors'
    lines.append(f"| {size}M | {p.stat().st_size/1024**2:.1f} MiB | {m['parameter_bytes']/1024**2:.1f} MiB | {m['peak_process_rss_bytes']/1024**2:.1f} MiB |")
lines += ['', 'Downloaded weights are unquantized model files; loading converts them to float32 for this CPU experiment. Peak RSS includes Python, PyTorch, model tensors, and transient allocations. Neither number estimates a mobile quantized runtime. Loading/downloading and warmup are excluded from per-request latencies. Other desktop applications were running.', '',
'## Assessment','',
'These stock models with the tested prompts are not reliable enough to create calendar events automatically. A structured response alone is insufficient; the observed failures include wrong dates, wrong AM/PM, invented repeats, and fabricated times. Even excluding the title does not rescue most results.','',
'A focused offline parser remains the smallest option for common phrases. SmolLM2-360M may be useful for phrase extraction followed by deterministic date/time code, based on the limited semantic review above. A stronger optional local model is also a reasonable separate experiment if the user accepts its download and memory requirements. Gemma 4 E2B was not tested here. Fine-tuning or a different prompt could improve SmolLM2; these small test sets do not establish a universal limit on the model family.','',
'## Reproducibility and checks','',
'Six scoring unit tests passed. Every generation, reference answer, field comparison, elapsed time, prompt, and model revision is saved in results/*.json. Full pinned Python dependencies are in requirements-lock.txt. The Android app has no new model dependency from this experiment.','']
(root/'REPORT.md').write_text('\n'.join(lines))
print('\n'.join(lines))
