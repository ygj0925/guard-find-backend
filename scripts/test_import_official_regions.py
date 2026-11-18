import hashlib
import json
import runpy
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
normalize_name = runpy.run_path(str(ROOT / 'scripts/import-official-regions.py'))['normalize_name']


class OfficialRegionImportTest(unittest.TestCase):
    def test_removes_documented_footnote_only(self):
        self.assertEqual('仙桃市', normalize_name('429004', '仙桃市*', True))
        self.assertEqual('神农架林区', normalize_name('429021', '神农架林区*', True))
        self.assertEqual('北京市', normalize_name('110000', '北京市', True))
        for code, name, note in [('110101', '东城区*', True), ('429004', '仙桃市*', False), ('429004', '仙*桃市', True), ('429004', '仙桃市＊', True)]:
            with self.assertRaises(ValueError):
                normalize_name(code, name, note)

    def test_shipped_snapshot_hash_and_all_names(self):
        resources = ROOT / 'guard-find-backend-system/src/main/resources'
        data = (resources / 'china.json').read_bytes()
        metadata = json.loads((resources / 'china-source.json').read_text(encoding='utf-8'))
        self.assertEqual(metadata['dataSha256'], hashlib.sha256(data).hexdigest())
        self.assertEqual(32, metadata['normalization']['removedFootnoteCount'])
        names = {}

        def walk(nodes):
            for node in nodes:
                names[node['code']] = node['name']
                self.assertNotRegex(node['name'], r'[*＊†‡※①-⑳]')
                self.assertEqual(node['name'], node['fullname'])
                walk(node['children'])

        walk(json.loads(data)['children'])
        self.assertEqual(metadata['entryCount'], len(names))
        self.assertEqual('仙桃市', names[429004])
        self.assertEqual('济源市', names[419001])
        self.assertEqual('神农架林区', names[429021])
        self.assertEqual('白杨市', names[659012])


if __name__ == '__main__':
    unittest.main()
