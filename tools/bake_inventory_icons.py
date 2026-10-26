"""Bake GUI-only isometric cards; hand, dropped items and placed blocks remain 3D."""
import base64, io, json
from pathlib import Path
import numpy as np
from PIL import Image
from bake_runtime_models import ROOT, ASSETS, mesh
from preview_runtime_models import render

for stem in ('fortune_barrel','interpretation_shrine','collection_chest'):
    master=json.loads((ROOT/'art-source'/f'{stem}.bbmodel').read_text('utf-8'))
    vertices=np.concatenate([p for e in master['elements'] for p,u,n in mesh(e)])
    pixels=render(master,(.67,.45,1),(vertices.min(axis=0),vertices.max(axis=0)))
    icon=Image.fromarray(pixels).resize((236,256),Image.Resampling.NEAREST)
    canvas=Image.new('RGBA',(256,256));canvas.paste(icon,(10,0))
    path=ASSETS/'textures/item'/f'{stem}_gui.png';canvas.save(path)
    quad={'textures':{'0':f'dailyfortunes:item/{stem}_gui','particle':f'dailyfortunes:item/{stem}_gui'},
      'ambientocclusion':False,'gui_light':'front','elements':[{'from':[0,0,8],'to':[16,16,8],'shade':False,
      'faces':{'north':{'uv':[0,0,16,16],'texture':'#0'},'south':{'uv':[0,0,16,16],'texture':'#0'}}}],
      'display':{'gui':{'rotation':[0,180,0]}}}
    (ASSETS/'models/item'/f'{stem}_gui.json').write_text(json.dumps(quad,separators=(',',':')),'utf-8')
    # The benchmark loads the immutable master as a separate additional model.
    test=ROOT/'src/visual/resources/assets/dailyfortunes'
    (test/'models/item').mkdir(parents=True,exist_ok=True);(test/'textures/benchmark').mkdir(parents=True,exist_ok=True)
    source=base64.b64decode(master['textures'][0]['source'].split(',',1)[1])
    (test/'textures/benchmark'/f'{stem}.png').write_bytes(source)
    es=[]
    for e in master['elements']:
        faces={name:{'uv':[v/32 for v in f['uv']],'texture':'#0'} for name,f in e['faces'].items() if f.get('texture') is not None}
        out={'from':e['from'],'to':e['to'],'faces':faces}
        if any(e['rotation']):
            axis=next(i for i,v in enumerate(e['rotation']) if v)
            out['rotation']={'angle':e['rotation'][axis],'axis':'xyz'[axis],'origin':e['origin'],'rescale':False}
        es.append(out)
    model={'textures':{'0':f'dailyfortunes:benchmark/{stem}','particle':f'dailyfortunes:benchmark/{stem}'},'elements':es,'display':master['display'],'render_type':'minecraft:cutout'}
    (test/'models/item'/f'baseline_{stem}.json').write_text(json.dumps(model,separators=(',',':')),'utf-8')
print('Baked 3 GUI cards and isolated benchmark masters.')
