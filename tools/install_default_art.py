"""Install approved vanilla models; preserve optimized classic art as an optional pack."""
from __future__ import annotations
import base64, hashlib, io, itertools, json, math, shutil, zipfile
from pathlib import Path
import numpy as np
from PIL import Image
from bake_runtime_models import ROOT, ASSETS, mesh, rotation
from create_vanilla_barrel import export_java
from preview_runtime_models import render

PACK = ROOT / 'resourcepacks/classic'
NAMES = ('fortune_barrel', 'interpretation_shrine', 'collection_chest')


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', 'utf-8')


def icon(model, path):
    vertices=np.concatenate([p for e in model['elements'] for p, _, _ in mesh(e)])
    pixels=render(model,(.67,.45,1),(vertices.min(axis=0),vertices.max(axis=0)))
    card=Image.fromarray(pixels).resize((236,256),Image.Resampling.NEAREST)
    image=Image.new('RGBA',(256,256));image.paste(card,(10,0));image.save(path)


def safe_pose(element, dx, dz, lift):
    """Clip the transformed stick box at the rim; all submerged parts must fit."""
    pivot=np.array(element['origin']) / 16
    corners=np.array(list(itertools.product(*zip(element['from'],element['to']))))/16
    baked=(corners-pivot)@rotation(element['rotation']).T
    rx=rotation([math.degrees(dz*3.5),0,0])
    rz=rotation([0,0,math.degrees(-dx*3.5)])
    moved=baked@(rx@rz).T+pivot+np.array([0,lift,0])
    rim=12/16
    clipped=[p for p in moved if p[1] <= rim]
    for i in range(8):
        for bit in (1,2,4):
            j=i^bit
            if j <= i: continue
            a,b=moved[i],moved[j]
            if (a[1]-rim)*(b[1]-rim) < 0:
                clipped.append(a+(b-a)*((rim-a[1])/(b[1]-a[1])))
    pts=np.array(clipped)
    return bool(len(pts) and pts[:,[0,2]].min() >= 4.005/16 and pts[:,[0,2]].max() <= 11.995/16 and pts[:,1].min() >= 1/16)


def motion_bounds(e):
    bounds=[0.,0.,0.,0.]  # dx min/max, dz min/max, in block units
    assert safe_pose(e,0,0,0), e['name']
    def permitted(b):
        return all(safe_pose(e,x,z,y) for x in (b[0],0,b[1]) for z in (b[2],0,b[3]) for y in (0,.0225,.045))
    for _ in range(65):
        for side,sign in enumerate((-1,1,-1,1)):
            candidate=list(bounds);candidate[side]=round(candidate[side]+sign*.001,6)
            if abs(candidate[side]) <= .065 and permitted(candidate): bounds=candidate
    # Interior samples also verify the angular box and vertical lift, not just tips.
    assert all(safe_pose(e,x,z,y) for x in np.linspace(*bounds[:2],9) for z in np.linspace(*bounds[2:],9) for y in (0,.0225,.045))
    return bounds


def main():
    protected=[p for folder in ('art-source','art-runtime','art-prototypes') for p in (ROOT/folder).rglob('*') if p.is_file()]
    before={p:hashlib.sha256(p.read_bytes()).hexdigest() for p in protected}
    reports=[]
    shapes={}
    for name in NAMES:
        source=ROOT/'art-prototypes'/(name+'_vanilla16')/(name+'_vanilla16.bbmodel')
        model=json.loads(source.read_text('utf-8'))
        def export(elements):
            value=export_java(model,elements)
            value['textures']={'0':'dailyfortunes:block/'+name,'particle':'dailyfortunes:block/'+name}
            value['render_type']='minecraft:cutout'
            return value
        write(ASSETS/'models/block'/f'{name}.json',export(model['elements']))
        write(ASSETS/'models/item'/f'{name}.json',{'parent':'dailyfortunes:block/'+name})
        (ASSETS/'textures/block'/f'{name}.png').write_bytes(base64.b64decode(model['textures'][0]['source'].split(',',1)[1]))
        icon(model,ASSETS/'textures/item'/f'{name}_gui.png')
        reports.append({'model':name,'source':source.relative_to(ROOT).as_posix(),
                        'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                        'quads':sum(1 for e in model['elements'] for _ in mesh(e)),
                        'atlas':[model['resolution']['width'],model['resolution']['height']]})
        if name != 'fortune_barrel':
            shapes[name]=[]
            for e in model['elements']:
                if any(v in e['name'] for v in ('叶片','红绳','绳尾','签笺')): continue
                if any(hi-lo <= 1e-7 for lo,hi in zip(e['from'],e['to'])): continue
                # Subdivide the two sloping roof cuboids only for a close stepped collider.
                count=16 if any(e['rotation']) else 1
                for i in range(count):
                    part={**e,'from':list(e['from']),'to':list(e['to'])}
                    part['from'][0]=e['from'][0]+(e['to'][0]-e['from'][0])*i/count
                    part['to'][0]=e['from'][0]+(e['to'][0]-e['from'][0])*(i+1)/count
                    pts=np.concatenate([p for p,_,_ in mesh(part)])
                    shapes[name].append([round(float(v),6) for v in (*pts.min(axis=0),*pts.max(axis=0))])
        if name == 'fortune_barrel':
            rig=json.loads((source.parent/'rig.json').read_text('utf-8'))['sticks']
            byid={e['uuid']:e for e in model['elements']}
            ids={r['element'] for r in rig}
            write(ASSETS/'models/item/barrel_shell.json',export([e for e in model['elements'] if e['uuid'] not in ids]))
            limits=[]
            for r in rig:
                e=byid[r['element']]
                write(ASSETS/'models/item'/f"barrel_stick_{r['index']}.json",export([e]))
                limits.append(motion_bounds(e))
            profile={'schema':1,'style':'vanilla','pivots':[[v/16 for v in r['origin']] for r in rig],
                     'motionBounds':limits,'roundWall':0,'minimumSeparation':.075,'chestTop':10.5/16}
            write(ASSETS/'visuals/model_profile.json',profile)
    classic=json.loads((ROOT/'docs/barrel-animation-rig.json').read_text('utf-8'))
    write(PACK/'assets/dailyfortunes/visuals/model_profile.json',
          {'schema':1,'style':'classic','pivots':classic['pivots'],
           'motionBounds':[[-.065,.065,-.065,.065] for _ in range(7)],
           'roundWall':.16,'minimumSeparation':.039,'chestTop':9.61/16})
    write(PACK/'pack.mcmeta',{'pack':{'pack_format':15,'description':'DailyFortunes · 精细木作 / Classic detailed models (optional)'}})
    shutil.copy2(ROOT/'LICENSE',PACK/'LICENSE')
    shutil.copy2(ROOT/'NOTICE',PACK/'NOTICE')
    # GUI icons must show the exact optimized meshes rather than the detailed masters.
    for name in NAMES:
        classic_model=json.loads((ROOT/'art-runtime'/f'{name}.bbmodel').read_text('utf-8'))
        icon(classic_model,PACK/'assets/dailyfortunes/textures/item'/f'{name}_gui.png')
    version='0.3.0'
    out=ROOT/'build/distributions'/f'DailyFortunes-Classic-{version}.zip'
    out.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for path in sorted(PACK.rglob('*')):
            if path.is_file():
                info=zipfile.ZipInfo(path.relative_to(PACK).as_posix(),(2026,10,28,0,0,0))
                info.compress_type=zipfile.ZIP_DEFLATED
                z.writestr(info,path.read_bytes())
    for path,digest in before.items():
        assert hashlib.sha256(path.read_bytes()).hexdigest()==digest, path
    write(ROOT/'docs/default-models.json',{'version':version,'default':'vanilla','optional':'classic','models':reports,
         'mastersAndPrototypesPreserved':True,'resourcePackAutoEnabled':False})
    java='package cn.pixelmc.dailyfortunes.forge;\n\n/** Generated from approved solid wood/roof parts. Foliage and cords are excluded. */\nfinal class ModelBounds {\n  private ModelBounds() {}\n'
    for name,boxes in shapes.items():
        field='CHEST' if name=='collection_chest' else 'SHRINE'
        java+='  static final double[][] '+field+' = {\n'
        java+=''.join('    {'+', '.join(str(v) for v in box)+'},\n' for box in boxes)
        java+='  };\n'
    java+='}\n'
    (ROOT/'src/main/java/cn/pixelmc/dailyfortunes/forge/ModelBounds.java').write_text(java,'utf-8')
    print(json.dumps({'models':reports,'classicPack':str(out),'motionBounds':profile['motionBounds']},ensure_ascii=False))


if __name__=='__main__': main()
