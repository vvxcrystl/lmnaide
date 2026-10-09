# SmolLM2 calendar extraction benchmark

This experiment runs real Hugging Face SmolLM2 inference on the local CPU. It does not change the Android app or save calendar events. The only network operations are downloading public model files and looking up the model revision; prompts are evaluated locally.

## Run

From the repository root:

```sh
uv venv --python 3.12 .venv-smollm-bench
uv pip install --python .venv-smollm-bench/bin/python torch==2.14.1+cpu --index-url https://download.pytorch.org/whl/cpu
uv pip install --python .venv-smollm-bench/bin/python transformers==4.57.6
.venv-smollm-bench/bin/python -m unittest discover -s tools/smollm_benchmark -v
.venv-smollm-bench/bin/python tools/smollm_benchmark/benchmark.py --output tools/smollm_benchmark/results/135m.json
.venv-smollm-bench/bin/python tools/smollm_benchmark/extraction.py --output tools/smollm_benchmark/results/135m-extraction.json
.venv-smollm-bench/bin/python tools/smollm_benchmark/benchmark.py --model HuggingFaceTB/SmolLM2-360M-Instruct --prompts few_shot --output tools/smollm_benchmark/results/360m.json
.venv-smollm-bench/bin/python tools/smollm_benchmark/extraction.py --model HuggingFaceTB/SmolLM2-360M-Instruct --output tools/smollm_benchmark/results/360m-extraction.json
.venv-smollm-bench/bin/python tools/smollm_benchmark/make_report.py
```

Use `--model HuggingFaceTB/SmolLM2-360M-Instruct` to compare the next size. To reproduce a particular run, pass its recorded `--revision` commit. Model files are kept in the standard Hugging Face cache, outside the repository. The Python environment is ignored by git.

## Method

- 30 manually specified test cases covering ordinary events, relative dates, durations, overnight ranges, repetition, informal wording, and clarification.
- Fixed context: Thursday October 8, 2026, America/Regina; default duration 60 minutes. This makes relative-date results deterministic.
- Two prompts: explicit instructions without examples, and the same instructions with four worked examples that are distinct from the test cases.
- CPU, float32, four inference threads, greedy decoding, up to 180 output tokens. No sampling, quantization, fine-tuning, constrained decoding, or output repair.
- Output must contain all seven fields with correct types. Harmless Markdown fences are allowed. Titles are compared without case or surrounding whitespace; dates and times must match exactly.
- Exact accuracy includes title, start/end dates, start/end times, recurrence, and the clarification decision. Scheduling accuracy excludes only title. Valid JSON alone does not count as a correct event.
- Cases requiring clarification must return null event fields, rather than inventing a schedule. The ambiguous-case policy is part of the prompt, not a universal interpretation of English.
- Timing includes prompt prefill and generation, excludes model loading and warmup. Latencies are measured while other desktop applications may be running. Peak RSS is for the entire Python process, including PyTorch; it is not model-only memory.

This is a small feasibility benchmark, not a statistically representative production evaluation. Prompt variants share a test set and are compared experimentally; final production quality would require a separate unseen evaluation set. Desktop float32 results do not establish mobile speed, quantized model size, or quantized accuracy. A poor unquantized result is a reason to reconsider bundling the model, rather than evidence that every possible fine-tuned version must fail.

Raw outputs, expected values, per-field scoring, revision, timings, and summary counts are in `results/*.json`. See `REPORT.md` for observed results.

The separate `extraction.py` experiment asks for only literal date/time phrases and a title on 15 cases, with three examples. It removes date calculation and recurrence to probe a simpler model-assisted parser. Its exact-span metric is stricter than semantic equivalence (e.g. `at 4pm` and `4pm` differ), so inspect the raw outputs too.

Models: [135M](https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct), [360M](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct).
