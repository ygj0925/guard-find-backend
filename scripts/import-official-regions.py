"""Convert a downloaded MCA county-or-above HTML table into the shipped region snapshot.

Usage: py -3 scripts/import-official-regions.py downloaded.html --version 2024-12-31 --source https://www.mca.gov.cn/mzsj/xzqh/2025/202401xzqh.html
Downloads are deliberately separate: inspect the official source before replacing a snapshot.
"""
import argparse
import hashlib
import json
import re
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse


class Table(HTMLParser):
    def __init__(self):
        super().__init__()
        self.rows, self.row, self.cell = [], [], None

    def handle_starttag(self, tag, attrs):
        if tag == 'tr':
            self.row = []
        elif tag == 'td':
            self.cell = ''

    def handle_data(self, data):
        if self.cell is not None:
            self.cell += data

    def handle_endtag(self, tag):
        if tag == 'td' and self.cell is not None:
            self.row.append(re.sub(r'\s+', '', self.cell))
            self.cell = None
        elif tag == 'tr':
            self.rows.append(self.row)


def normalize_name(code, name, has_direct_county_note):
    """Remove only the source's documented suffix; never strip arbitrary name characters."""
    if '*' not in name and '＊' not in name:
        return name
    if has_direct_county_note and code[2:4] == '90' and name.endswith('*') and name.count('*') == 1 and '＊' not in name:
        return name[:-1]
    raise ValueError(f'Unrecognized annotation in official name: {code} {name}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('input', type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--source', required=True)
    args = parser.parse_args()
    if urlparse(args.source).hostname not in {'www.mca.gov.cn', 'dmfw.mca.gov.cn'}:
        raise ValueError('Only an official Ministry of Civil Affairs source is accepted')
    raw = args.input.read_bytes()
    table = Table()
    table.feed(raw.decode('utf-8-sig'))
    has_direct_county_note = any('标记“*”' in cell and '直辖县级行政区划' in cell for row in table.rows for cell in row)
    entries = {}
    footnote_count = 0
    for row in table.rows:
        for index, cell in enumerate(row[:-1]):
            if re.fullmatch(r'[1-8]\d{5}', cell) and row[index + 1]:
                if cell in entries:
                    raise ValueError(f'Duplicate code {cell}')
                official_name = row[index + 1]
                entries[cell] = normalize_name(cell, official_name, has_direct_county_note)
                footnote_count += int(entries[cell] != official_name)
    if len(entries) < 3000 or entries.get('110101') != '东城区':
        raise ValueError('Incomplete or unexpected official table')
    nodes = {code: {'code': int(code), 'name': name, 'fullname': name, 'children': []}
             for code, name in entries.items()}
    provinces = []
    for code, node in nodes.items():
        if code.endswith('0000'):
            provinces.append(node)
        else:
            parent = code[:4] + '00'
            if parent == code or parent not in nodes:
                parent = code[:2] + '0000'
            nodes[parent]['children'].append(node)
    if len(provinces) != 34:
        raise ValueError('Expected all 34 province-level entries')
    root = Path(__file__).resolve().parents[1] / 'guard-find-backend-system/src/main/resources'
    payload = {'code': 100000, 'name': '中国', 'children': provinces}
    data = json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    metadata = {'publisher': '中华人民共和国民政部', 'version': args.version,
                'sourceUrl': args.source, 'latestSourceUrl': 'https://dmfw.mca.gov.cn/XzqhVersionPublish.html',
                'sourceSha256': hashlib.sha256(raw).hexdigest(), 'dataSha256': hashlib.sha256(data).hexdigest(),
                'normalization': {'removedFootnoteCount': footnote_count,
                                  'rule': '仅移除原表注2说明的省直辖县级区划名称末尾脚注*；代码与行政名称正文不变'},
                'entryCount': len(entries), 'scope': '县级及以上；台湾省、香港特别行政区和澳门特别行政区暂缺地市和区县信息'}
    (root / 'china.json').write_bytes(data)
    (root / 'china-source.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == '__main__':
    main()
