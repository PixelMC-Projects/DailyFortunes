"""Check production asset structure and optional pre-optimization cost bounds."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image
from bake_runtime_models import ROOT, ASSETS
from measure_runtime_cost import measure


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    current = measure(ROOT / 'art-runtime')
    classic_assets = ROOT / 'resourcepacks/classic/assets/dailyfortunes'
    total = 0
    for row in current:
        name = row['model']
        report = json.loads((ROOT / 'art-runtime' / (name + '-report.json')).read_text('utf-8'))
        master = ROOT / 'art-source' / (name + '.bbmodel')
        assert hashlib.sha256(master.read_bytes()).hexdigest() == report['masterSha256'], name
        model = json.loads((classic_assets / 'models/block' / (name + '.json')).read_text('utf-8'))
        quads = sum(len(element['faces']) for element in model['elements'])
        assert quads == row['quads'] == report['afterQuads'], name
        with Image.open(classic_assets / 'textures/block' / (name + '.png')) as image:
            assert list(image.size) == report['atlas']
        for element in model['elements']:
            assert all(lo <= hi for lo, hi in zip(element['from'], element['to']))
            for face in element['faces'].values():
                assert all(0 <= value <= 16 for value in face['uv']), (name, face)
        total += quads
    full = next(row['quads'] for row in current if row['model'] == 'fortune_barrel')
    shell = json.loads((classic_assets / 'models/item/barrel_shell.json').read_text('utf-8'))
    split = sum(len(element['faces']) for element in shell['elements'])
    for index in range(7):
        stick = json.loads((classic_assets / 'models/item' / f'barrel_stick_{index}.json').read_text('utf-8'))
        faces = sum(len(element['faces']) for element in stick['elements'])
        assert faces == 6, 'All seven sticks retain their independent closed mesh'
        split += faces
    assert split == full, 'Animated shell and sticks must match the production model'
    if args.baseline:
        baseline = {row['model']: row for row in measure(args.baseline)}
        for row in current:
            prior = baseline[row['model']]
            assert row['quads'] < prior['quads']
            assert row['quadArea'] <= prior['quadArea'] * 1.05, row['model']
            assert row['transparentArea'] <= prior['transparentArea'] * 1.05, row['model']
            assert row['atlasPixels'] <= prior['atlasPixels'], row['model']
    defaults=json.loads((ROOT/'docs/default-models.json').read_text('utf-8'))
    for row in defaults['models']:
        source=ROOT/row['source']
        assert hashlib.sha256(source.read_bytes()).hexdigest()==row['sha256']
        model=json.loads((ASSETS/'models/block'/(row['model']+'.json')).read_text('utf-8'))
        assert sum(len(e['faces']) for e in model['elements']) == row['quads']
        with Image.open(ASSETS/'textures/block'/(row['model']+'.png')) as image:
            assert list(image.size)==row['atlas']
    shell=json.loads((ASSETS/'models/item/barrel_shell.json').read_text('utf-8'))
    split=sum(len(e['faces']) for e in shell['elements'])
    profile=json.loads((ASSETS/'visuals/model_profile.json').read_text('utf-8'))
    for i in range(7):
        stick=json.loads((ASSETS/'models/item'/f'barrel_stick_{i}.json').read_text('utf-8'))
        assert len(stick['elements'])==1 and len(stick['elements'][0]['faces'])==6
        element=stick['elements'][0]
        if 'rotation' in element:
            assert [v/16 for v in element['rotation']['origin']] == profile['pivots'][i]
        split+=6
    assert split == 123 and profile['style']=='vanilla'
    print(json.dumps({'passed': True, 'defaultQuads':sum(r['quads'] for r in defaults['models']),
                      'classicQuads': total, 'classicModels': current}, indent=2))


if __name__ == '__main__':
    main()
