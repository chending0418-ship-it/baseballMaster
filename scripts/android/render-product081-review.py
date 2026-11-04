#!/usr/bin/env python3
"""Render a verified Android product review manifest into PDF and an original-image index.

No PDF is written with --check-only. The caller must run the PDF artifact-operation
marker exactly once before the first actual authoring invocation, then inspect the
Poppler renders before declaring the report visually approved.

Usage (use the bundled Python runtime):
  render-product081-review.py --input review-manifest.json --check-only
  render-product081-review.py --input review-manifest.json
  render-product081-review.py --input review-manifest.json --allow-draft

Manifest paths are absolute or relative to the manifest's directory. Final mode
requires passed=true, successful validation rows, exact screenshot hashes, XML
companions, an explicit data_scope, and phone + expanded coverage for every
REQUIRED_CATEGORIES entry below. --allow-draft labels incomplete coverage/status
and hash discrepancies as draft; it never fabricates a missing UI screenshot.
Optional reference_images are a separate product-reference/source-image appendix, excluded
from Android coverage/counts. Their image hashes must match even in draft mode;
identify source, app version/device and any user-supplied partial image in title/viewport_label/notes.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import itertools
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime
from urllib.parse import quote
from zoneinfo import ZoneInfo

from PIL import Image
from pypdf import PdfReader
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A3, A4, landscape
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, Spacer, Table, TableStyle

REPO = Path(__file__).resolve().parents[2]
RUNTIME = Path.home() / '.cache/codex-runtimes/codex-primary-runtime/dependencies'
DEFAULT_OUTPUT = REPO / 'output/releases/android/0.8.1'
DEFAULT_RENDER = REPO / 'output/validation/android/0.8.1/report-render'
REQUIRED_CATEGORIES = (
    'games', 'teams', 'statistics', 'profile', 'settings', 'setup',
    'pregame_lineup', 'lineup', 'substitution', 'recording', 'results',
)
CATEGORY_LABELS = {
    'games': '比赛首页', 'teams': '球队', 'statistics': '统计', 'profile': '我的',
    'settings': '设置', 'setup': '新建比赛', 'pregame_lineup': '赛前阵容确认',
    'lineup': '赛中阵容', 'substitution': '换人与守位', 'recording': '现场记录',
    'results': '比赛结果', 'pro_contact': 'Pro 击球记录', 'basic_recording': '基础版记录',
    'runner': '跑垒核对', 'history': '历史纠错', 'special_roles': '特殊阵容',
}
CATEGORY_ALIASES = {
    'home': 'games', '比赛': 'games', '比赛首页': 'games', '球队': 'teams',
    'stats': 'statistics', '统计': 'statistics', 'mine': 'profile', '我的': 'profile',
    '设置': 'settings', '建赛': 'setup', 'new-game': 'setup', '赛前阵容': 'pregame_lineup',
    'lineup-confirmation': 'pregame_lineup', '赛中阵容': 'lineup',
    '换人': 'substitution', 'substitutions': 'substitution', '记分': 'recording',
    'scoring': 'recording', '结果': 'results', 'game-results': 'results',
}
VIEWPORT_ALIASES = {'phone': 'phone', 'compact': 'phone', 'cover': 'phone',
                    'expanded': 'expanded', 'unfolded': 'expanded', 'fold-expanded': 'expanded'}
VIEWPORT_LABELS = {'phone': '手机', 'expanded': '折叠屏展开'}
DEVICE_SCOPE = '设备边界：API31 手机与折叠模拟器；实体 vivo 尚未实测。'
REFERENCE_NOTICE = '参考来源以各页版本／设备说明为准；非最终 Android 测试证据，不计 Android 覆盖。'
NAVY = colors.HexColor('#0B1933')
GREEN = colors.HexColor('#217A57')
SECONDARY = colors.HexColor('#526574')
LINE = colors.HexColor('#DCE3DF')
BG = colors.HexColor('#F5F3EB')
RED = colors.HexColor('#9C392F')
SHA_RE = re.compile(r'^[0-9a-fA-F]{64}$')
ID_RE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$')
REGULAR = 'ReviewBody'
BOLD = 'ReviewBold'


def clean(value) -> str:
    text = str(value if value is not None else '')
    return re.sub('[\u2010-\u2015\u2212]', '-', text).replace('\x00', '')


def sequence(value) -> list[str]:
    if value is None:
        return []
    return [clean(x) for x in value] if isinstance(value, list) else [clean(value)]


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            sha.update(block)
    return sha.hexdigest()


def resolve(base: Path, value) -> Path:
    path = Path(str(value)).expanduser()
    return (path if path.is_absolute() else base / path).resolve()


def viewport_family(value) -> str:
    if isinstance(value, dict):
        value = value.get('family', '')
    return VIEWPORT_ALIASES.get(str(value), str(value))


def viewport_label(screen: dict) -> str:
    raw = screen.get('viewport', '')
    if isinstance(raw, dict):
        return clean(raw.get('label') or raw.get('name') or VIEWPORT_LABELS.get(screen['_family'], raw['family']))
    return VIEWPORT_LABELS.get(screen['_family'], clean(raw))


def load_manifest(path: Path, allow_draft: bool) -> tuple[dict, list[str]]:
    data = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(data, dict):
        raise ValueError('Manifest must be a JSON object')
    warnings: list[str] = []
    def require(condition, message):
        if not condition:
            warnings.append(message)
    require(data.get('passed') is True, 'passed 必须明确为 true，不能把未通过结果作为最终报告')
    require(data.get('version') == '0.8.1', '本渲染器的最终报告版本必须为 0.8.1')
    require(isinstance(data.get('build'), (str, int)) and str(data['build']).strip(), '缺少 build')
    require(bool(SHA_RE.fullmatch(str(data.get('apk_sha', '')))), 'apk_sha 必须为完整 SHA-256')
    require(bool(clean(data.get('data_scope')).strip()), '必须明确 data_scope，说明真实界面与测试数据边界')
    data['limitations'] = sequence(data.get('limitations'))
    rows = data.get('validation', data.get('validation_rows', []))
    if not isinstance(rows, list):
        raise ValueError('validation 必须为数组')
    require(bool(rows), '缺少实际验证矩阵 validation')
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            raise ValueError(f'validation[{index}] 必须为对象')
        require(row.get('passed') is True, f'验证未通过或状态不明确：{row.get("id", index)}')
        require(bool(row.get('title')), f'validation[{index}] 缺少 title')
        evidence = row.get('evidence')
        require(bool(evidence), f'validation[{index}] 缺少实际 evidence 文件')
        if evidence:
            evidence_path = resolve(path.parent, evidence)
            require(evidence_path.is_file(), f'验证证据不存在：{evidence_path}')
    data['validation'] = rows
    findings = data.get('findings', [])
    if not isinstance(findings, list):
        raise ValueError('findings 必须为数组')
    for index, finding in enumerate(findings):
        if not isinstance(finding, dict):
            raise ValueError(f'findings[{index}] 必须为对象')
        for field in ('title', 'before', 'after'):
            require(isinstance(finding.get(field), str) and bool(finding[field].strip()),
                    f'findings[{index}] 缺少非空 {field}')
            finding[field] = clean(finding.get(field))
        finding['validation'] = sequence(finding.get('validation'))
        require(bool(finding['validation']), f'findings[{index}] 缺少实际验证／边界说明 validation')
    data['findings'] = findings
    if data.get('apk'):
        apk = resolve(path.parent, data['apk'])
        if not apk.is_file():
            raise ValueError(f'APK does not exist: {apk}')
        require(digest(apk) == str(data.get('apk_sha', '')).lower(), 'APK 文件哈希与 apk_sha 不符')
    screens = data.get('screens')
    if not isinstance(screens, list) or not screens:
        raise ValueError('screens 必须为非空数组；不能生成没有实际截图的报告')
    seen: set[str] = set()
    seen_hashes: dict[str, tuple[str,str]] = {}
    for index, screen in enumerate(screens):
        if not isinstance(screen, dict):
            raise ValueError(f'screens[{index}] 必须为对象')
        screen_id = str(screen.get('id', ''))
        if not ID_RE.fullmatch(screen_id) or screen_id in seen:
            raise ValueError(f'Invalid or duplicate screenshot id: {screen_id!r}')
        seen.add(screen_id)
        if not screen.get('title') or not screen.get('category') or not screen.get('viewport'):
            raise ValueError(f'{screen_id}: title/category/viewport are required')
        image_path = resolve(path.parent, screen.get('image', ''))
        if not image_path.is_file():
            raise ValueError(f'{screen_id}: screenshot does not exist: {image_path}')
        with Image.open(image_path) as image:
            image.verify()
        with Image.open(image_path) as image:
            screen['_size'] = image.size
        if min(screen['_size']) < 200:
            raise ValueError(f'{screen_id}: expected a full original screen, not a thumbnail: {screen["_size"]}')
        actual = digest(image_path)
        claimed = str(screen.get('sha256', ''))
        require(bool(SHA_RE.fullmatch(claimed)), f'{screen_id}: 缺少完整截图 sha256')
        require(actual == claimed.lower(), f'{screen_id}: 原图 sha256 不符，实际为 {actual}')
        screen['_actual_sha'] = actual
        screen['_image'] = image_path
        screen['_family'] = viewport_family(screen['viewport'])
        screen['_category'] = CATEGORY_ALIASES.get(str(screen['category']), str(screen['category']))
        identity = (screen['_family'], screen['_category'])
        require(actual not in seen_hashes or seen_hashes[actual] == identity,
                f'{screen_id}: 同一张原图不能充当不同视口或不同页面类别的覆盖证据')
        seen_hashes[actual] = identity
        screen['checks'] = sequence(screen.get('checks'))
        screen['notes'] = sequence(screen.get('notes'))
        require(bool(screen['checks']), f'{screen_id}: 缺少本页核查项 checks')
        require(bool(screen['notes']), f'{screen_id}: 缺少本页数据／范围说明 notes')
        xml = screen.get('xml')
        screen['_xml'] = resolve(path.parent, xml) if xml else None
        require(bool(xml) and screen['_xml'].is_file(), f'{screen_id}: 缺少配套原始 UI 层级 XML')
        if screen['_xml'] and screen['_xml'].is_file():
            screen['_xml_sha'] = digest(screen['_xml'])
            if screen.get('xml_sha256'):
                require(screen['_xml_sha'] == str(screen['xml_sha256']).lower(), f'{screen_id}: XML hash 不符')
    references = data.get('reference_images', [])
    if not isinstance(references, list):
        raise ValueError('reference_images 必须为数组')
    for index, reference in enumerate(references):
        if not isinstance(reference, dict):
            raise ValueError(f'reference_images[{index}] 必须为对象')
        reference_id = str(reference.get('id', ''))
        if not ID_RE.fullmatch(reference_id) or reference_id in seen:
            raise ValueError(f'Invalid or duplicate reference id: {reference_id!r}')
        seen.add(reference_id)
        for field in ('title', 'viewport_label', 'image'):
            if not isinstance(reference.get(field), str) or not reference[field].strip():
                raise ValueError(f'{reference_id}: reference {field} is required')
        image_path = resolve(path.parent, reference['image'])
        if not image_path.is_file():
            raise ValueError(f'{reference_id}: reference image does not exist: {image_path}')
        with Image.open(image_path) as image:
            image.verify()
        with Image.open(image_path) as image:
            reference['_size'] = image.size
        if min(reference['_size']) < 200:
            raise ValueError(f'{reference_id}: reference must be a full original screenshot')
        actual = digest(image_path)
        claimed = str(reference.get('sha256', ''))
        # Reference provenance is strict even in a draft: never misattribute a changed image.
        if not SHA_RE.fullmatch(claimed) or actual != claimed.lower():
            raise ValueError(f'{reference_id}: reference image SHA-256 mismatch; actual {actual}')
        reference.update(_image=image_path, _actual_sha=actual, _family='reference',
                         _category='reference', _xml=None, category='产品参考与问题原图',
                         viewport={'family': 'reference', 'label': reference['viewport_label']})
        reference['checks'] = sequence(reference.get('checks'))
        reference['notes'] = sequence(reference.get('notes'))
        if not reference['checks'] and not reference['notes']:
            raise ValueError(f'{reference_id}: reference notes or checks must identify source/version and scope')
        reference['notes'].insert(0, REFERENCE_NOTICE)
    data['reference_images'] = references
    for category, family in itertools.product(REQUIRED_CATEGORIES, ('phone', 'expanded')):
        require(any(s['_category'] == category and s['_family'] == family for s in screens),
                f'缺少必需对照：{VIEWPORT_LABELS[family]} / {CATEGORY_LABELS[category]}')
    if data.get('expected_screen_count') is not None:
        require(data['expected_screen_count'] == len(screens), 'expected_screen_count 与实际截图数量不符')
    if warnings and not allow_draft:
        raise ValueError('拒绝生成最终报告：\n- ' + '\n- '.join(warnings))
    data['_draft'] = bool(allow_draft)
    data['_manifest'] = path
    data['_warnings'] = warnings
    data['_created'] = clean(data.get('generated_at') or datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds'))
    # Pair phone and expanded screenshots before moving to the next category/state.
    category_order = ['games', 'teams', 'statistics', 'profile', 'settings', 'setup',
                      'pregame_lineup', 'lineup', 'substitution', 'recording',
                      'basic_recording', 'pro_contact', 'runner', 'history', 'special_roles', 'results']
    category_order += list(dict.fromkeys(s['_category'] for s in screens if s['_category'] not in category_order))
    ordered = []
    for category in category_order:
        group = [s for s in screens if s['_category'] == category]
        pairs = list(dict.fromkeys(str(s.get('pair_id', 'default')) for s in group))
        for pair in pairs:
            pair_group = [s for s in group if str(s.get('pair_id', 'default')) == pair]
            phone = [s for s in pair_group if s['_family'] == 'phone']
            expanded = [s for s in pair_group if s['_family'] == 'expanded']
            for a, b in itertools.zip_longest(phone, expanded):
                ordered.extend(s for s in (a, b) if s is not None)
            ordered.extend(s for s in pair_group if s['_family'] not in ('phone', 'expanded'))
    data['screens'] = ordered
    return data, warnings


def font_candidates(explicit, bold=False):
    if explicit:
        return [Path(explicit).expanduser()]
    return [Path('/System/Library/Fonts/STHeiti Medium.ttc' if bold else '/System/Library/Fonts/STHeiti Light.ttc'),
            Path('/System/Library/Fonts/Supplemental/Arial Unicode.ttf')]


def all_report_text(data: dict) -> str:
    fields = [data.get('title', ''), data.get('version', ''), data.get('build', ''), data.get('apk_sha', ''),
              data.get('data_scope', ''), data['_created'], *data['limitations'], *data['_warnings']]
    fields += list(CATEGORY_LABELS.values()) + list(VIEWPORT_LABELS.values())
    fields += ['棒球比赛记录大师 Android 逐页实际截图评审报告 最终验证通过 草稿 版本 构建 截图 验证矩阵 目录 手机与展开相邻对照 原始完整界面未裁切未重绘 核查项 数据边界与说明 原图哈希 配套层级 本页说明续页 限制与发布边界 证据文件 结果 通过 未通过 来源 与采集清单一致 页面']
    for row in data['validation']:
        fields.extend(str(row.get(k, '')) for k in ('id', 'title', 'device', 'count', 'evidence', 'notes', 'kind'))
    fields += ['产品差异与整改说明', '整改前', '整改后', '验证与边界', '快速阅读', '实际截图目录', '个人统计首屏', '展开现场工作台', '数据与设备边界', DEVICE_SCOPE]
    for finding in data.get('findings', []):
        fields.extend([finding['title'], finding['before'], finding['after'], *finding['validation']])
    fields += ['产品参考与问题原图', '参考来源与版本', '打开完整参考原图 参考完整原图 非最终 Android 测试证据 不计 Android 覆盖', REFERENCE_NOTICE]
    for screen in data['screens'] + data.get('reference_images', []):
        fields.extend([screen['id'], screen['title'], screen['category'], viewport_label(screen), *screen['checks'], *screen['notes'], str(screen['_size']), screen['_actual_sha'], screen['_image'].name])
    return '\n'.join(clean(x) for x in fields)


def register_fonts(data: dict, regular=None, bold=None) -> dict:
    chars = {ord(c) for c in all_report_text(data) if not c.isspace()}
    result = {}
    for name, explicit, is_bold in ((REGULAR, regular, False), (BOLD, bold, True)):
        failures = []
        for path in font_candidates(explicit, is_bold):
            if not path.is_file():
                failures.append(f'{path}: missing')
                continue
            try:
                font = TTFont(name, str(path), subfontIndex=0)
                missing = sorted(chars - set(font.face.charToGlyph))
                if missing:
                    failures.append(f'{path}: missing glyphs ' + ''.join(chr(c) for c in missing[:30]))
                    continue
                pdfmetrics.registerFont(font)
                result[name] = str(path)
                break
            except Exception as error:
                failures.append(f'{path}: {error}')
        else:
            raise ValueError('No embeddable TrueType font covers the report text.\n' + '\n'.join(failures))
    pdfmetrics.registerFontFamily(REGULAR, normal=REGULAR, bold=BOLD, italic=REGULAR, boldItalic=BOLD)
    return result


def styles():
    return {
        'body': ParagraphStyle('body', fontName=REGULAR, fontSize=10, leading=15, textColor=NAVY, wordWrap='CJK', spaceAfter=6),
        'small': ParagraphStyle('small', fontName=REGULAR, fontSize=8.5, leading=12, textColor=SECONDARY, wordWrap='CJK', spaceAfter=4),
        'check': ParagraphStyle('check', fontName=REGULAR, fontSize=9, leading=13, textColor=NAVY, wordWrap='CJK', spaceAfter=3),
        'label': ParagraphStyle('label', fontName=BOLD, fontSize=10, leading=14, textColor=GREEN, wordWrap='CJK', spaceAfter=4, keepWithNext=True),
        'title': ParagraphStyle('title', fontName=BOLD, fontSize=18, leading=24, textColor=NAVY, wordWrap='CJK', spaceAfter=8),
        'cover': ParagraphStyle('cover', fontName=BOLD, fontSize=31, leading=42, textColor=NAVY, wordWrap='CJK', spaceAfter=12),
        'table': ParagraphStyle('table', fontName=REGULAR, fontSize=8.5, leading=12, textColor=NAVY, wordWrap='CJK'),
    }


def paragraph(text, style):
    return Paragraph(html.escape(clean(text)).replace('\n', '<br/>'), style)


def split_flow(flowables, width, height):
    remaining = list(flowables)
    pages = []
    while remaining:
        page, used = [], 0.0
        while remaining:
            item = remaining[0]
            _, h = item.wrap(width, height)
            extra = item.getSpaceAfter()
            if page and item.getKeepWithNext() and len(remaining) > 1:
                keep_height = h + extra
                for following in remaining[1:]:
                    keep_height += following.wrap(width, height)[1] + following.getSpaceAfter()
                    if not following.getKeepWithNext():
                        break
                if keep_height <= height and keep_height > height - used:
                    break
            if h + extra <= height - used + .1:
                page.append(item); used += h + extra; remaining.pop(0)
            else:
                if not page:
                    pieces = item.split(width, height - used)
                    if not pieces:
                        raise ValueError('A report paragraph cannot fit the page; split this manifest text')
                    page.append(pieces[0]); remaining = pieces[1:] + remaining[1:]
                break
        pages.append(page)
    return pages


def table_chunks(rows, widths, available_height):
    table = Table(rows, colWidths=widths, repeatRows=1, hAlign='LEFT')
    table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#E7EFE8')),
        ('LINEBELOW', (0, 0), (-1, 0), 1, GREEN),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 7), ('RIGHTPADDING', (0, 0), (-1, -1), 7),
        ('TOPPADDING', (0, 0), (-1, -1), 8), ('BOTTOMPADDING', (0, 0), (-1, -1), 8),
        ('LINEBELOW', (0, 1), (-1, -1), .4, LINE),
    ]))
    chunks = []
    pending = [table]
    while pending:
        current = pending.pop(0)
        _, h = current.wrap(sum(widths), available_height)
        if h <= available_height:
            chunks.append(current)
        else:
            pieces = current.split(sum(widths), available_height)
            if len(pieces) < 2:
                raise ValueError('Validation/contents row too tall for one page')
            chunks.append(pieces[0]); pending = pieces[1:] + pending
    return chunks


def screen_notes(screen, st):
    notes = [paragraph('核查项', st['label'])]
    notes.extend(paragraph(f'{i}. {text}', st['check']) for i, text in enumerate(screen['checks'], 1))
    notes.append(paragraph('数据边界与说明', st['label']))
    notes.extend(paragraph(text, st['check']) for text in screen['notes'])
    return notes


def screenshot_size(screen):
    base = A3 if screen['_family'] in ('expanded', 'reference') else A4
    return landscape(base) if screen['_size'][0] / screen['_size'][1] > 1.15 else base


def build_plan(data, st):
    plan = [{'kind': 'cover', 'size': A4}]
    w, h = A4
    rows = [[paragraph(x, st['table']) for x in ('验证项目', '设备／数量', '结果', '实际证据与说明')]]
    for row in data['validation']:
        rows.append([paragraph(f'{row.get("id", "")}\n{row["title"]}', st['table']),
                     paragraph(f'{row.get("device", row.get("kind", ""))}\n{row.get("count", "")}', st['table']),
                     paragraph('通过' if row.get('passed') is True else '未通过', st['table']),
                     paragraph(f'{Path(str(row.get("evidence", ""))).name}\n{row.get("notes", "")}', st['table'])])
    for chunk in table_chunks(rows, [145, 95, 50, w - 72 - 290], h - 150):
        plan.append({'kind': 'matrix', 'size': A4, 'table': chunk})
    finding_flow = []
    for index, finding in enumerate(data.get('findings', []), 1):
        finding_flow += [paragraph(f'{index}. {finding["title"]}', st['label']),
                         paragraph('整改前', st['label']), paragraph(finding['before'], st['body']),
                         paragraph('整改后', st['label']), paragraph(finding['after'], st['body']),
                         paragraph('验证与边界', st['label'])]
        finding_flow += [paragraph(f'{item_index}. {text}', st['check'])
                         for item_index, text in enumerate(finding['validation'], 1)]
        finding_flow.append(Spacer(1, 8))
    for flow in split_flow(finding_flow, w - 72, h - 155):
        plan.append({'kind': 'findings', 'size': A4, 'flow': flow})
    boundary = [paragraph('数据边界', st['label']), paragraph(data.get('data_scope', ''), st['body'])]
    boundary.append(paragraph('限制与发布边界', st['label']))
    boundary += [paragraph(f'{i}. {text}', st['body']) for i, text in enumerate(data['limitations'], 1)]
    if not data['limitations']:
        boundary.append(paragraph('清单未登记额外限制；该字段不代表设备、分发或发布获得额外验证。', st['body']))
    if data['_warnings']:
        boundary += [paragraph('草稿缺项（不得作为最终验收）', st['label'])]
        boundary += [paragraph(text, st['body']) for text in data['_warnings']]
    for flow in split_flow(boundary, w - 72, h - 155):
        plan.append({'kind': 'boundary', 'size': A4, 'flow': flow})
    toc_rows = [[paragraph(x, st['table']) for x in ('截图 ID', '实际页面', '视口', '页')]]
    for screen in data['screens']:
        toc_rows.append([paragraph(screen['id'], st['table']), paragraph(screen['title'], st['table']),
                         paragraph(viewport_label(screen), st['table']), paragraph('000', st['table'])])
    for chunk in table_chunks(toc_rows, [150, w - 72 - 150 - 95 - 32, 95, 32], h - 150):
        plan.append({'kind': 'contents', 'size': A4, 'table': chunk})
    for screen in data['screens']:
        size = screenshot_size(screen)
        pieces = split_flow(screen_notes(screen, st), size[0] - 72, 180)
        plan.append({'kind': 'screen', 'size': size, 'screen': screen, 'flow': pieces[0]})
        remaining_notes = list(itertools.chain.from_iterable(pieces[1:]))
        for flow in split_flow(remaining_notes, A4[0] - 72, A4[1] - 155):
            plan.append({'kind': 'screen-notes', 'size': A4, 'screen': screen, 'flow': flow})
    for screen in data.get('reference_images', []):
        size = screenshot_size(screen)
        pieces = split_flow(screen_notes(screen, st), size[0] - 72, 180)
        plan.append({'kind': 'reference', 'size': size, 'screen': screen, 'flow': pieces[0]})
        remaining_notes = list(itertools.chain.from_iterable(pieces[1:]))
        for flow in split_flow(remaining_notes, A4[0] - 72, A4[1] - 155):
            plan.append({'kind': 'reference-notes', 'size': A4, 'screen': screen, 'flow': flow})
    # UI screenshots come first; keep each screenshot's note continuations adjacent.
    order = {'cover': 0, 'contents': 1, 'screen': 2, 'screen-notes': 2, 'findings': 3,
             'boundary': 4, 'matrix': 5, 'reference': 6, 'reference-notes': 6}
    plan.sort(key=lambda page: order[page['kind']])  # Stable within each section.
    # Fill actual forward links only after the final section order is fixed.
    page_lookup = {p['screen']['id']: i for i, p in enumerate(plan, 1) if p['kind'] == 'screen'}
    toc_rows = [[paragraph(x, st['table']) for x in ('截图 ID', '实际页面', '视口', '页')]]
    for screen in data['screens']:
        linked = Paragraph(f'<link href="#screen-{screen["id"]}" color="#217A57">{html.escape(clean(screen["title"]))}</link>', st['table'])
        toc_rows.append([paragraph(screen['id'], st['table']), linked, paragraph(viewport_label(screen), st['table']), paragraph(page_lookup[screen['id']], st['table'])])
    toc_chunks = iter(table_chunks(toc_rows, [150, w - 72 - 150 - 95 - 32, 95, 32], h - 150))
    for page in plan:
        if page['kind'] == 'contents':
            page['table'] = next(toc_chunks)
    page_lookup.update({'reference-' + p['screen']['id']: i for i, p in enumerate(plan, 1) if p['kind'] == 'reference'})
    return plan, page_lookup


def draw_flow(c, flow, x, top, width):
    y = top
    for item in flow:
        _, height = item.wrap(width, 100000)
        item.drawOn(c, x, y - height)
        y -= height + item.getSpaceAfter()
    return y


def draw_frame(c, data, number, total, width, height, reference=False):
    c.setFillColor(colors.white); c.rect(0, 0, width, height, stroke=0, fill=1)
    c.setStrokeColor(LINE); c.line(36, height - 35, width - 36, height - 35)
    c.setFont(REGULAR, 8); c.setFillColor(SECONDARY)
    c.drawString(36, height - 25, 'BaseballMaster | 产品参考与问题原图' if reference else f'BaseballMaster | Android {clean(data.get("version"))} ({clean(data.get("build"))})')
    c.drawRightString(width - 36, height - 25, '草稿 - 不作为最终验收' if data['_draft'] else '实际截图评审')
    c.line(36, 32, width - 36, 32)
    c.drawString(36, 19, '来源以各页说明为准 · 非最终 Android 测试证据 · 不计 Android 覆盖' if reference else '完整原图独立展示 · 手机与展开视口相邻编排')
    c.drawRightString(width - 36, 19, f'{number} / {total}')


def write_pdf(data, output: Path, plan, st):
    c = canvas.Canvas(str(output), pagesize=A4, pageCompression=1)
    c.setTitle(clean(data.get('title') or 'Android 0.8.1 逐页实际截图评审报告'))
    c.setAuthor('BaseballMaster')
    sections_seen = set()
    for index, page in enumerate(plan, 1):
        w, h = page['size']; c.setPageSize((w, h)); draw_frame(c, data, index, len(plan), w, h, page['kind'].startswith('reference'))
        kind = page['kind']
        section_names = {'contents': '实际截图目录', 'findings': '产品差异与整改说明',
                         'matrix': '验证矩阵', 'boundary': '数据与设备边界'}
        if kind in section_names and kind not in sections_seen:
            sections_seen.add(kind)
            c.bookmarkPage('review-' + kind)
            c.addOutlineEntry(section_names[kind], 'review-' + kind, level=0)
        if kind == 'cover':
            c.setFillColor(BG); c.roundRect(36, h - 325, w - 72, 240, 18, stroke=0, fill=1)
            draw_flow(c, [paragraph('Android 0.8.1', st['cover']), paragraph('逐页实际截图\n产品评审与验证报告', st['cover'])], 58, h - 110, w - 116)
            y = h - 356
            rows = [f'版本 {data.get("version")} · Build {data.get("build")}',
                    '草稿：验证状态或覆盖范围尚未满足最终条件' if data['_draft'] else '最终验证通过（以清单中的实际验证记录为依据）',
                    f'{len(data["screens"])} 张完整截图 · {len(data["validation"])} 行验证记录',
                    f'生成时间：{data["_created"]}', f'APK SHA-256：\n{data.get("apk_sha", "未登记")}',
                    DEVICE_SCOPE, f'数据边界：{data.get("data_scope", "未登记")}',
                    '每张完整原图单独一页；展开屏使用较大纸面保持可读。核查项、限制和原图链接随页提供。']
            links = [('review-contents', '实际截图目录'), ('review-matrix', '验证矩阵')]
            if data.get('findings'):
                links.insert(1, ('review-findings', '产品差异与整改说明'))
            known_ids = {screen['id'] for screen in data['screens']}
            for target, label in [('phone-player-statistics-overview', '个人统计首屏'), ('expanded-live-pro', '展开现场工作台')]:
                if target in known_ids:
                    links.append(('screen-' + target, label))
            quick = Paragraph('快速阅读：' + ' · '.join(f'<link href="#{target}" color="#217A57">{label}</link>' for target, label in links), st['body'])
            flow = [quick] + [paragraph(row, st['body']) for row in rows]
            needed = sum(item.wrap(w - 100, h)[1] + item.getSpaceAfter() for item in flow)
            if needed > y - 55:
                raise ValueError('Cover metadata is too long; shorten data_scope and use per-page notes/limitations')
            draw_flow(c, flow, 50, y, w - 100)
        elif kind in ('matrix', 'contents'):
            title = '验证矩阵' if kind == 'matrix' else '逐页目录 · 手机与展开相邻对照'
            draw_flow(c, [paragraph(title, st['title'])], 36, h - 58, w - 72)
            table = page['table']; _, th = table.wrap(w - 72, h - 150); table.drawOn(c, 36, h - 102 - th)
        elif kind == 'findings':
            draw_flow(c, [paragraph('产品差异与整改说明', st['title'])] + page['flow'], 36, h - 58, w - 72)
        elif kind == 'boundary':
            draw_flow(c, [paragraph('数据、设备与发布边界', st['title'])] + page['flow'], 36, h - 58, w - 72)
        elif kind in ('screen', 'reference'):
            screen = page['screen']; bookmark = ('reference-' if kind == 'reference' else 'screen-') + screen['id']
            c.bookmarkPage(bookmark)
            c.addOutlineEntry(f'{viewport_label(screen)} / {screen["title"]}', bookmark, level=0)
            title = paragraph(screen['title'], st['title']); _, title_h = title.wrap(w - 72, 100)
            title.drawOn(c, 36, h - 54 - title_h)
            label = f'{screen["id"]} | {viewport_label(screen)} | {CATEGORY_LABELS.get(screen["_category"], screen["category"])} | {screen["_size"][0]} × {screen["_size"][1]} px'
            meta = paragraph(label, st['small']); _, meta_h = meta.wrap(w - 72, 100); meta.drawOn(c, 36, h - 60 - title_h - meta_h)
            image_top = h - 70 - title_h - meta_h
            # Fit this page's actual notes above the checksum, retaining the
            # usual image area for short notes and avoiding one-line note pages.
            notes_height = sum(item.wrap(w - 72, 100000)[1] + item.getSpaceAfter() for item in page['flow'])
            notes_top = max(168, 52 + notes_height)
            notes_y = notes_top + 16
            image_height = image_top - notes_y - 14
            image_width = w - 72
            iw, ih = screen['_size']; scale = min(image_width / iw, image_height / ih)
            dw, dh = iw * scale, ih * scale
            x, y = (w - dw) / 2, notes_y + (image_height - dh) / 2
            c.drawImage(ImageReader(str(screen['_image'])), x, y, width=dw, height=dh, preserveAspectRatio=True, mask='auto')
            c.linkURL(screen['_asset_url'], (x, y, x + dw, y + dh), relative=0, thickness=0)
            draw_flow(c, page['flow'], 36, notes_top, w - 72)
            checksum = paragraph(f'原图 SHA-256: {screen["_actual_sha"]}', st['small'])
            checksum.wrap(w - 72, 24); checksum.drawOn(c, 36, 37)
        elif kind in ('screen-notes', 'reference-notes'):
            screen = page['screen']
            draw_flow(c, [paragraph(f'{screen["title"]} · 本页说明续页', st['title']), paragraph(f'截图 {screen["id"]} | {viewport_label(screen)}', st['small'])] + page['flow'], 36, h - 58, w - 72)
        c.showPage()
    c.save()
    reader = PdfReader(str(output))
    if len(reader.pages) != len(plan):
        raise ValueError('Generated PDF page count differs from the verified plan')
    # The Chinese title/body fonts must be embedded TrueType subsets, not device-only CID references.
    embedded = set()
    for page in reader.pages:
        resources = page['/Resources'].get_object()
        font_refs = resources.get('/Font')
        font_refs = font_refs.get_object() if font_refs is not None else {}
        for ref in font_refs.values():
            font = ref.get_object()
            desc = font.get('/FontDescriptor')
            if desc and desc.get_object().get('/FontFile2'):
                embedded.add(str(font.get('/BaseFont', '')))
    if len(embedded) < 2:
        raise ValueError('Expected embedded body and heading TrueType fonts in the PDF')
    return sorted(embedded)


def copy_assets(data, output_dir, name):
    asset_dir = output_dir / (name + '.assets'); asset_dir.mkdir(parents=True, exist_ok=True)
    for index, screen in enumerate(data['screens'] + data.get('reference_images', []), 1):
        prefix = 'reference-' if screen['_family'] == 'reference' else ''
        stem = f'{prefix}{index:03}-{screen["id"]}'
        image = asset_dir / (stem + screen['_image'].suffix.lower())
        shutil.copy2(screen['_image'], image)
        if digest(image) != screen['_actual_sha']:
            raise ValueError(f'Copied image hash changed: {screen["id"]}')
        screen['_asset_url'] = quote(str(image.relative_to(output_dir)))
        if screen['_xml'] and screen['_xml'].is_file():
            xml = asset_dir / (stem + '.xml'); shutil.copy2(screen['_xml'], xml)
            screen['_xml_url'] = quote(str(xml.relative_to(output_dir)))


def write_html(data, path: Path, pdf_name: str, pages):
    esc = lambda v: html.escape(clean(v), quote=True)
    cards = []
    for screen in data['screens']:
        checks = ''.join(f'<li>{esc(x)}</li>' for x in screen['checks'])
        notes = ''.join(f'<p>{esc(x)}</p>' for x in screen['notes'])
        xml = f'<a href="{screen["_xml_url"]}">原始 UI 层级 XML</a>' if screen.get('_xml_url') else '<span>缺 XML（草稿）</span>'
        cards.append(f'''<article id="{esc(screen['id'])}"><header><small>{esc(CATEGORY_LABELS.get(screen['_category'], screen['category']))} · {esc(viewport_label(screen))} · PDF 第 {pages[screen['id']]} 页</small><h2>{esc(screen['title'])}</h2><code>{esc(screen['id'])}</code></header><a class="original" href="{screen['_asset_url']}" target="_blank" rel="noopener"><img loading="lazy" src="{screen['_asset_url']}" alt="{esc(screen['title'])} 原始完整截图"></a><p><a href="{screen['_asset_url']}" target="_blank" rel="noopener">打开完整原图</a> · {xml}</p><h3>核查项</h3><ul>{checks}</ul><h3>数据边界与说明</h3>{notes}<footer><code>SHA-256 {screen['_actual_sha']}</code></footer></article>''')
    reference_cards = []
    for screen in data.get('reference_images', []):
        checks = ''.join(f'<li>{esc(x)}</li>' for x in screen['checks'])
        notes = ''.join(f'<p>{esc(x)}</p>' for x in screen['notes'])
        reference_cards.append(f'''<article id="reference-{esc(screen['id'])}"><header><small>参考来源与版本：{esc(viewport_label(screen))} · PDF 第 {pages['reference-' + screen['id']]} 页</small><h2>{esc(screen['title'])}</h2><code>{esc(screen['id'])}</code></header><p>{esc(REFERENCE_NOTICE)}</p><a href="{screen['_asset_url']}" target="_blank" rel="noopener"><img loading="lazy" src="{screen['_asset_url']}" alt="{esc(screen['title'])} 参考完整原图"></a><p><a href="{screen['_asset_url']}" target="_blank" rel="noopener">打开完整参考原图</a></p><ul>{checks}</ul>{notes}<footer><code>SHA-256 {screen['_actual_sha']}</code></footer></article>''')
    reference_appendix = ('<section><h2>产品参考与问题原图</h2><p>' + esc(REFERENCE_NOTICE) + '</p>' + ''.join(reference_cards) + '</section>') if reference_cards else ''
    validations = ''.join(f'<tr><td>{esc(r.get("id", ""))}<br>{esc(r["title"])}</td><td>{esc(r.get("device", r.get("kind", "")))}<br>{esc(r.get("count", ""))}</td><td>{"通过" if r.get("passed") is True else "未通过"}</td><td>{esc(Path(str(r.get("evidence", ""))).name)}<br>{esc(r.get("notes", ""))}</td></tr>' for r in data['validation'])
    warnings = ''.join(f'<li>{esc(x)}</li>' for x in data['_warnings'])
    limitations = ''.join(f'<li>{esc(x)}</li>' for x in data['limitations'])
    findings = ''.join(f'<section class="boundary"><h3>{number}. {esc(f["title"])}</h3><h4>整改前</h4><p>{esc(f["before"])}</p><h4>整改后</h4><p>{esc(f["after"])}</p><h4>验证与边界</h4><ul>' + ''.join(f'<li>{esc(v)}</li>' for v in f['validation']) + '</ul></section>' for number, f in enumerate(data.get('findings', []), 1))
    findings = '<section id="findings"><h2>产品差异与整改说明</h2>' + findings + '</section>' if findings else ''
    toc = ''.join(f'<li><a href="#{esc(s["id"])}">{esc(viewport_label(s))} · {esc(s["title"])}</a></li>' for s in data['screens'])
    quicklinks = '<nav aria-label="快速阅读"><a href="#screens">实际截图</a> · <a href="#contents">截图目录</a> · <a href="#validation">验证矩阵</a>'
    if data.get('findings'):
        quicklinks += ' · <a href="#findings">产品差异与整改说明</a>'
    known_ids = {screen['id'] for screen in data['screens']}
    for target, label in [('phone-player-statistics-overview', '个人统计首屏'), ('expanded-live-pro', '展开现场工作台')]:
        if target in known_ids:
            quicklinks += f' · <a href="#{target}">{label}</a>'
    quicklinks += '</nav>'
    document = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Android 0.8.1 逐页截图评审</title><style>:root{{color-scheme:light;--navy:#0b1933;--green:#217a57}}*{{box-sizing:border-box}}body{{margin:0;background:#f5f3eb;color:var(--navy);font:16px/1.65 -apple-system,BlinkMacSystemFont,"Arial Unicode MS",sans-serif}}main{{max-width:1480px;margin:auto;padding:32px}}h1{{font-size:36px;line-height:1.2}}h2{{margin:.3em 0}}h3{{font-size:16px;margin-bottom:4px}}a{{color:var(--green)}}code{{font-size:12px;overflow-wrap:anywhere}}.status{{font-weight:700;color:{'#9c392f' if data['_draft'] else '#217a57'}}}.grid{{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px}}article{{background:white;border-radius:18px;padding:22px;min-width:0}}article img{{display:block;max-width:100%;height:auto;max-height:1000px;margin:20px auto;object-fit:contain}}article footer{{border-top:1px solid #dce3df;padding-top:12px}}table{{width:100%;border-collapse:collapse;background:white;margin-bottom:28px}}td,th{{text-align:left;vertical-align:top;border-bottom:1px solid #dce3df;padding:12px}}th{{background:#e7efe8}}nav ul{{columns:2}}.boundary{{background:white;padding:20px;border-radius:14px;margin:20px 0}}@media(max-width:850px){{main{{padding:18px}}.grid{{grid-template-columns:1fr}}nav ul{{columns:1}}#validation table{{display:block;max-width:100%;overflow-x:auto;overscroll-behavior-x:contain}}}}@media print{{article{{break-inside:avoid}}}}</style><main><h1>Android 0.8.1<br>逐页实际截图评审</h1><p class="status">{'草稿 - 不作为最终验收' if data['_draft'] else '验证通过 - 依据登记的实际结果'}</p><p>Build {esc(data.get('build'))} · {len(data['screens'])} 张完整截图 · {esc(data['_created'])}<br><code>APK SHA-256 {esc(data.get('apk_sha', ''))}</code></p><p><a href="{quote(pdf_name)}">打开 PDF 报告</a> · 点击任意截图打开未裁切原图</p>{quicklinks}<p><strong>{esc(DEVICE_SCOPE)}</strong></p><section class="boundary"><h2>数据与范围</h2><p>{esc(data.get('data_scope', '未登记'))}</p><details><summary>限制与发布边界</summary><ul>{limitations}</ul></details>{f'<h3>草稿缺项</h3><ul>{warnings}</ul>' if warnings else ''}</section><nav id="contents"><details><summary>截图目录 · 手机与展开配对</summary><ul>{toc}</ul></details></nav><section id="screens" class="grid">{''.join(cards)}</section>{findings}<section id="validation"><h2>实际验证矩阵</h2><table><thead><tr><th>验证项目</th><th>设备／数量</th><th>结果</th><th>证据与说明</th></tr></thead><tbody>{validations}</tbody></table></section>{reference_appendix}</main></html>'''
    path.write_text(document, encoding='utf-8')


def render_pdf(pdf, directory):
    directory.mkdir(parents=True, exist_ok=True)
    bundled = RUNTIME / 'bin/override/pdftoppm'
    executable = str(bundled) if bundled.is_file() else shutil.which('pdftoppm')
    if not executable:
        raise ValueError('pdftoppm is required for the render-and-inspect workflow')
    prefix = directory / pdf.stem
    for stale in directory.glob(pdf.stem + '-*.png'):
        stale.unlink()  # Only this report's generated render pages, never original captures.
    subprocess.run([executable, '-r', '110', '-png', str(pdf), str(prefix)], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return sorted(str(p) for p in directory.glob(pdf.stem + '-*.png'))


def public_summary(data, warnings, fonts):
    coverage = {category: {family: sum(s['_category'] == category and s['_family'] == family for s in data['screens']) for family in ('phone', 'expanded')} for category in REQUIRED_CATEGORIES}
    return {'version': data.get('version'), 'build': data.get('build'), 'draft': data['_draft'],
            'screen_count': len(data['screens']), 'reference_count': len(data.get('reference_images', [])),
            'validation_rows': len(data['validation']),
            'findings_count': len(data.get('findings', [])),
            'coverage': coverage, 'warnings': warnings, 'fonts': fonts,
            'ordered_screen_ids': [s['id'] for s in data['screens']]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output-dir', type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument('--render-dir', type=Path, default=DEFAULT_RENDER)
    parser.add_argument('--name', default='BaseballMaster-Android-0.8.1-Product-Review')
    parser.add_argument('--allow-draft', action='store_true')
    parser.add_argument('--check-only', action='store_true', help='Validate hashes, coverage, text glyphs and page plan; write nothing')
    parser.add_argument('--font', type=Path)
    parser.add_argument('--bold-font', type=Path)
    args = parser.parse_args(argv)
    if not ID_RE.fullmatch(args.name):
        parser.error('--name must be an ASCII basename without directory components')
    data, warnings = load_manifest(args.input.resolve(), args.allow_draft)
    fonts = register_fonts(data, args.font, args.bold_font)
    st = styles(); plan, pages = build_plan(data, st)
    summary = public_summary(data, warnings, fonts)
    summary['planned_pages'] = len(plan)
    if args.check_only:
        print(json.dumps(summary, ensure_ascii=False, indent=2)); return 0
    output_dir = args.output_dir.resolve(); output_dir.mkdir(parents=True, exist_ok=True)
    name = args.name + ('.draft' if args.allow_draft else '')
    copy_assets(data, output_dir, name)
    pdf = output_dir / (name + '.pdf'); index = output_dir / (name + '.html')
    embedded = write_pdf(data, pdf, plan, st)
    write_html(data, index, pdf.name, pages)
    rendered = render_pdf(pdf, args.render_dir.resolve())
    if len(rendered) != len(plan):
        raise ValueError('Poppler render count differs from the PDF page plan')
    summary.update(pdf=str(pdf), html=str(index), pdf_sha256=digest(pdf), embedded_fonts=embedded,
                   rendered_pages=rendered, visual_review='pending-human-inspection')
    summary['screens'] = [{'id': s['id'], 'page': pages[s['id']], 'image_sha256': s['_actual_sha'],
                           'xml_sha256': s.get('_xml_sha'), 'asset': s['_asset_url'],
                           'original_image': str(s['_image']), 'original_xml': str(s['_xml']) if s['_xml'] else None} for s in data['screens']]
    summary['reference_images'] = [{'id': s['id'], 'title': s['title'], 'viewport_label': viewport_label(s),
                                    'page': pages['reference-' + s['id']], 'image_sha256': s['_actual_sha'],
                                    'asset': s['_asset_url'], 'original_image': str(s['_image']),
                                    'source_scope': REFERENCE_NOTICE} for s in data.get('reference_images', [])]
    manifest = output_dir / (name + '.manifest.json')
    manifest.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2)); return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        raise SystemExit(2)
