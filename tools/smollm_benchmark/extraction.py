#!/usr/bin/env python3
"""Simpler semantic extraction experiment; code would resolve dates afterward."""
import argparse
import json
import statistics
import time
from pathlib import Path

PROMPT = '''Extract the event title and the date/time expressions from the user's text. Return only JSON with keys title, date_text, time_text. Copy date/time words from the input exactly; do not calculate dates. Use null for missing date or time. Remove polite request words from the title.'''
EXAMPLES = [
    ('Yoga tomorrow at 8am', {'title':'Yoga','date_text':'tomorrow','time_text':'at 8am'}),
    ('Movie Saturday from 8pm to 10pm', {'title':'Movie','date_text':'Saturday','time_text':'from 8pm to 10pm'}),
    ('Birthday on November 5', {'title':'Birthday','date_text':'on November 5','time_text':None}),
]
# Separate explicit expected spans; no model-generated reference answers.
CASES = [
    ('Work from 3 to 5pm tomorrow', 'Work', 'tomorrow', 'from 3 to 5pm'),
    ('Dentist tomorrow at 10am', 'Dentist', 'tomorrow', 'at 10am'),
    ('Lunch today at noon', 'Lunch', 'today', 'at noon'),
    ('Gym today from 6pm to 7pm', 'Gym', 'today', 'from 6pm to 7pm'),
    ('Meeting on October 20 at 2pm', 'Meeting', 'on October 20', 'at 2pm'),
    ('Call Alex on 2026-10-12 at 14:30', 'Call Alex', 'on 2026-10-12', 'at 14:30'),
    ('Coffee tomorrow at 9:30am', 'Coffee', 'tomorrow', 'at 9:30am'),
    ('Study from 9 to 11am tomorrow', 'Study', 'tomorrow', 'from 9 to 11am'),
    ('Dinner at 7pm', 'Dinner', None, 'at 7pm'),
    ('Birthday party tomorrow', 'Birthday party', 'tomorrow', None),
    ('Haircut next Friday at 4pm', 'Haircut', 'next Friday', 'at 4pm'),
    ('Soccer on Monday at 6pm', 'Soccer', 'on Monday', 'at 6pm'),
    ('Inspection in 3 days at 11am', 'Inspection', 'in 3 days', 'at 11am'),
    ('Visit mom the day after tomorrow at noon', 'Visit mom', 'the day after tomorrow', 'at noon'),
    ('Please add a team meeting tomorrow from 2pm to 3pm', 'team meeting', 'tomorrow', 'from 2pm to 3pm'),
]

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--model', default='HuggingFaceTB/SmolLM2-135M-Instruct')
    p.add_argument('--revision', default='main')
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    import torch
    from huggingface_hub import model_info
    from transformers import AutoTokenizer, AutoModelForCausalLM
    from benchmark import percentile
    torch.set_num_threads(4)
    torch.set_num_interop_threads(1)
    revision = model_info(args.model, revision=args.revision).sha
    tokenizer = AutoTokenizer.from_pretrained(args.model, revision=revision)
    model = AutoModelForCausalLM.from_pretrained(args.model, revision=revision, dtype=torch.float32).to('cpu').eval()
    results=[]
    for text, title, date, clock in CASES:
        messages=[{'role':'system', 'content':PROMPT}]
        for example, answer in EXAMPLES:
            messages.extend([{'role':'user','content':example}, {'role':'assistant','content':json.dumps(answer)}])
        messages.append({'role':'user','content':text})
        inputs=tokenizer.apply_chat_template(messages, add_generation_prompt=True, return_tensors='pt', return_dict=True)
        started=time.perf_counter()
        with torch.inference_mode():
            output=model.generate(**inputs, max_new_tokens=100, do_sample=False, pad_token_id=tokenizer.eos_token_id)
        seconds=time.perf_counter()-started
        raw=tokenizer.decode(output[0,inputs['input_ids'].shape[1]:],skip_special_tokens=True)
        expected=dict(title=title,date_text=date,time_text=clock)
        try:
            actual=json.loads(raw)
        except ValueError:
            actual=None
        passed=isinstance(actual,dict) and set(actual)==set(expected) and all(
            isinstance(actual[k],str) and actual[k].strip().casefold()==v.casefold() if isinstance(v,str)
            else actual[k] is None for k,v in expected.items())
        results.append(dict(text=text,expected=expected,actual=actual,raw=raw,passed=passed,seconds=seconds))
        print(f"{'PASS' if passed else 'FAIL'} {seconds:.2f}s {raw[:200]}",flush=True)
        args.output.write_text(json.dumps(dict(model=args.model,revision=revision,prompt=PROMPT,examples=EXAMPLES,results=results),indent=2)+'\n')
    summary=dict(total=len(results),exact_passes=sum(r['passed'] for r in results),median_seconds=statistics.median(r['seconds'] for r in results),p95_seconds=percentile([r['seconds'] for r in results],.95))
    args.output.write_text(json.dumps(dict(model=args.model,revision=revision,prompt=PROMPT,examples=EXAMPLES,summary=summary,results=results),indent=2)+'\n')
    print(json.dumps(summary),flush=True)

if __name__=='__main__':
    main()
