# SmolLM2 feasibility results

Executed locally on October 8, 2026, on an AMD Ryzen 7 7700 (8 cores / 16 threads), with four CPU inference threads and float32 weights. All inference was local. See README.md for rerun instructions and evaluation policy.

## Full event creation

Exact success requires the correct title, dates, times, recurrence, and clarification decision. The fixed reference date was October 8, 2026. These are manually specified expected answers, not another model judging the output.

| Model | Prompt | Exact events | Correct schedule excluding title | Structured objects* | Median | P95 |
|---|---|---:|---:|---:|---:|---:|
| 135M | zero_shot | 0/30 | 0/30 | 0/30 | 5.58s | 6.32s |
| 135M | few_shot | 0/30 | 0/30 | 30/30 | 2.71s | 3.67s |
| 360M | few_shot | 1/30 | 1/30 | 28/30 | 5.70s | 5.90s |

*Structured objects means valid JSON with all seven expected keys and their basic types. It does not imply valid ISO dates, permitted recurrence values, or correct event details. Those errors fail exact-event scoring.

## Easier extraction task

This asks only for title and literal date/time phrases, with three worked examples. It removes date arithmetic, recurrence, and end-time calculation. Exact span scoring includes prepositions, so an otherwise correct `4pm` instead of `at 4pm` fails. This is a stricter metric than semantic equivalence.

| Model | Exact extraction | Median | P95 |
|---|---:|---:|---:|
| 135M | 4/15 | 1.04s | 1.32s |
| 360M | 7/15 | 1.91s | 2.40s |

### Manual semantic review

Manual review of the 15 saved extraction outputs. This is a post-run exploratory audit, not a blinded or automated independent test. Allows equivalent date/time expressions including omission of on/at and propagation of am/pm through a time range. Requires the complete title and correct assignment to date_text and time_text.

| Model | Semantically correct phrase extraction |
|---|---:|
| 135M | 5/15 |
| 360M | 14/15 |

The 360M model is more promising as a phrase extractor: its only semantic failure here placed `at 7pm` into the date field and left time null. Its response to your Work example extracted Work / tomorrow / from 3pm to 5pm correctly. This narrow subset does not test ambiguity, recurrence, duration, overnight handling, or informal abbreviations, and needs a larger unseen evaluation before shipping. The audit is saved separately in results/extraction-semantic-review.json.


## Your example

`Work from 3 to 5pm tomorrow` should become Work, October 9, 2026, 15:00–17:00, no repeat.

### 135M response with examples

```json
{"title": "Work", "date": "2026-10-08", "start": "3:00", "end_date": "2026-10-08", "end": "08:00", "repeat": "daily", "clarify": false}
```

### 360M response with examples

```json
{"title": "Work from 3 to 5pm", "date": "2026-10-08", "start": "03:00", "end_date": "2026-10-08", "end": "04:00", "repeat": "none", "clarify": false}
```

## Desktop resource measurements

| Model | Downloaded weights | Float32 parameter tensors | Peak whole-process RSS |
|---|---:|---:|---:|
| 135M | 256.6 MiB | 513.1 MiB | 1176.6 MiB |
| 360M | 690.2 MiB | 1380.2 MiB | 2423.2 MiB |

Downloaded weights are unquantized model files; loading converts them to float32 for this CPU experiment. Peak RSS includes Python, PyTorch, model tensors, and transient allocations. Neither number estimates a mobile quantized runtime. Loading/downloading and warmup are excluded from per-request latencies. Other desktop applications were running.

## Assessment

These stock models with the tested prompts are not reliable enough to create calendar events automatically. A structured response alone is insufficient; the observed failures include wrong dates, wrong AM/PM, invented repeats, and fabricated times. Even excluding the title does not rescue most results.

A focused offline parser remains the smallest option for common phrases. SmolLM2-360M may be useful for phrase extraction followed by deterministic date/time code, based on the limited semantic review above. A stronger optional local model is also a reasonable separate experiment if the user accepts its download and memory requirements. Gemma 4 E2B was not tested here. Fine-tuning or a different prompt could improve SmolLM2; these small test sets do not establish a universal limit on the model family.

## Reproducibility and checks

Six scoring unit tests passed. Every generation, reference answer, field comparison, elapsed time, prompt, and model revision is saved in results/*.json. Full pinned Python dependencies are in requirements-lock.txt. The Android app has no new model dependency from this experiment.
