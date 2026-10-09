import unittest
import json
from benchmark import decode_result, matches, FIELDS

class ScoringTests(unittest.TestCase):
    def setUp(self):
        self.event = dict(title='Work', date='2026-10-09', start='15:00', end_date='2026-10-09', end='17:00', repeat='none', clarify=False)

    def test_valid_json_is_not_enough(self):
        wrong = dict(self.event, date='2026-10-10')
        self.assertIsNotNone(decode_result(json.dumps(wrong)))
        self.assertFalse(all(matches(wrong, self.event).values()))

    def test_prose_and_missing_fields_fail(self):
        self.assertIsNone(decode_result('Here is the event: ' + json.dumps(self.event)))
        self.assertIsNone(decode_result('{"title":"Work"}'))
        self.assertIsNone(decode_result('[]'))

    def test_wrong_types_fail(self):
        self.assertIsNone(decode_result(json.dumps(dict(self.event, clarify='false'))))
        self.assertIsNone(decode_result(json.dumps(dict(self.event, start=1500))))

    def test_fences_and_title_case_allowed(self):
        self.assertEqual(decode_result('```json\n'+json.dumps(self.event)+'\n```'), self.event)
        self.assertTrue(all(matches(dict(self.event, title=' work '), self.event).values()))

    def test_clarification_does_not_hide_invented_values(self):
        expected = dict.fromkeys(FIELDS)
        expected['clarify'] = True
        self.assertFalse(all(matches(dict(self.event, clarify=True), expected).values()))
        self.assertTrue(all(matches(expected, expected).values()))

    def test_cases_have_unique_ids_and_schema(self):
        from pathlib import Path
        cases = json.loads(Path(__file__).with_name('cases.json').read_text())
        self.assertEqual(len(cases), len({c['id'] for c in cases}))
        for case in cases:
            self.assertIsNotNone(decode_result(json.dumps(case['expected'])))

if __name__ == '__main__':
    unittest.main()
