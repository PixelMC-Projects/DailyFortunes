"""Derive low-cost game models from immutable Blockbench masters.

This is a geometry-to-texture bake, not a redesign. Masters are read-only.
Requires numpy and Pillow. Run from any working directory.
"""
from __future__ import annotations

import base64
import collections
import copy
import hashlib
import io
import importlib
import importlib.util
from types import SimpleNamespace
import json
import math
from pathlib import Path
import re

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/dailyfortunes"
DERIVED = ROOT / "art-runtime"
FACES = ("north", "south", "east", "west", "up", "down")
NORMALS = dict(north=(0,0,-1), south=(0,0,1), east=(1,0,0), west=(-1,0,0), up=(0,1,0), down=(0,-1,0))


def rotation(v):
    a,b,c=np.radians(v)
    x=np.array([[1,0,0],[0,np.cos(a),-np.sin(a)],[0,np.sin(a),np.cos(a)]])
    y=np.array([[np.cos(b),0,np.sin(b)],[0,1,0],[-np.sin(b),0,np.cos(b)]])
    z=np.array([[np.cos(c),-np.sin(c),0],[np.sin(c),np.cos(c),0],[0,0,1]])
    return z@y@x


def local_faces(e):
    x0,y0,z0=e['from']; x1,y1,z1=e['to']
    return dict(south=[(x0,y1,z1),(x1,y1,z1),(x1,y0,z1),(x0,y0,z1)],
      north=[(x1,y1,z0),(x0,y1,z0),(x0,y0,z0),(x1,y0,z0)],
      east=[(x1,y1,z1),(x1,y1,z0),(x1,y0,z0),(x1,y0,z1)],
      west=[(x0,y1,z0),(x0,y1,z1),(x0,y0,z1),(x0,y0,z0)],
      up=[(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],
      down=[(x0,y0,z1),(x1,y0,z1),(x1,y0,z0),(x0,y0,z0)])


def mesh(e):
    r=rotation(e.get('rotation',[0,0,0])); o=np.array(e.get('origin',[8,0,8]))
    result=[]
    for face,pts in local_faces(e).items():
        f=e['faces'].get(face)
        if f is None or f.get('texture') is None: continue
        pts=(np.array(pts)-o)@r.T+o
        if np.linalg.norm(np.cross(pts[1]-pts[0],pts[3]-pts[0]))<1e-9: continue
        u0,v0,u1,v1=f['uv']; uv=np.array([(u0,v0),(u1,v0),(u1,v1),(u0,v1)])
        if f.get('rotation'): uv=np.roll(uv,int(f['rotation'])//90,axis=0)
        result.append((pts,uv,r@NORMALS[face]))
    return result


def bounds(e):
    points=np.concatenate([p for p,u,n in mesh(e)])
    return points.min(axis=0), points.max(axis=0)


def compact_atlas(elements, atlas):
    """Copy only referenced pixel rectangles; no resampling or color changes."""
    packer = Baker(Image.new('RGBA', (1024, 1)))
    references = []
    for element in elements:
        for face in element['faces'].values():
            if face.get('texture') is None:
                continue
            uv = face['uv']
            left, top = math.floor(min(uv[0], uv[2])), math.floor(min(uv[1], uv[3]))
            right, bottom = math.ceil(max(uv[0], uv[2])), math.ceil(max(uv[1], uv[3]))
            right, bottom = max(left + 1, right), max(top + 1, bottom)
            assert 0 <= left < right <= atlas.width and 0 <= top < bottom <= atlas.height
            tile = atlas.crop((left, top, right, bottom))
            key = (tile.size, hashlib.sha256(tile.tobytes()).digest())
            if key not in packer.cache:
                packer.cache[key] = len(packer.tiles)
                packer.tiles.append(tile)
            references.append((face, list(uv), left, top, packer.cache[key]))
    compact, locations = packer.atlas()
    for face, uv, left, top, index in references:
        x, y, width, height = locations[index]
        assert compact.crop((x, y, x + width, y + height)).tobytes() == packer.tiles[index].tobytes()
        face['uv'] = [uv[0] - left + x, uv[1] - top + y,
                      uv[2] - left + x, uv[3] - top + y]
    return compact


def local_surface_groups(faces, expansion=1.25):
    """Merge close fragments without large transparent rectangles between them."""
    first = faces[0][2]
    face = faces[0][1]
    axis = 0 if face in ('east', 'west') else 1 if face in ('up', 'down') else 2
    transform = rotation(first.get('rotation', [0, 0, 0]))
    origin = np.array(first['origin'])
    groups = []
    for entry in faces:
        points = np.delete((entry[3] - origin) @ transform + origin, axis, axis=1)
        covered = float(np.linalg.norm(np.cross(entry[3][1] - entry[3][0], entry[3][3] - entry[3][0])))
        groups.append(([entry], points.min(axis=0), points.max(axis=0), covered))
    while True:
        best = None
        for i, (_, lo, hi, covered) in enumerate(groups):
            for j in range(i + 1, len(groups)):
                _, other_lo, other_hi, other_covered = groups[j]
                combined_lo, combined_hi = np.minimum(lo, other_lo), np.maximum(hi, other_hi)
                area = float(np.prod(combined_hi - combined_lo))
                ratio = area / max(covered + other_covered, 1e-9)
                if ratio <= expansion and (best is None or ratio < best[0]):
                    best = (ratio, i, j, combined_lo, combined_hi)
        if best is None:
            return [group[0] for group in groups]
        _, i, j, lo, hi = best
        a, b = groups[i], groups[j]
        groups[i] = (a[0] + b[0], lo, hi, a[3] + b[3])
        groups.pop(j)


class Baker:
    def __init__(self, source):
        self.original=np.array(source.convert('RGBA'))
        self.tiles=[]
        self.cache={}

    def bake(self, sources, proxy, face):
        # Project actual high-poly texture/color into one low-poly face.
        pf=next((p for p,u,n in mesh(proxy) if np.allclose(n,rotation(proxy.get('rotation',[0,0,0]))@NORMALS[face])),None)
        # A proxy can deliberately contain only one of two opposite faces.
        if pf is None: return None
        x=pf[1]-pf[0]; y=pf[3]-pf[0]; w=np.linalg.norm(x); h=np.linalg.norm(y)
        if min(w,h)<1e-6: return None
        nx=x/w; ny=y/h; nz=rotation(proxy.get('rotation',[0,0,0]))@NORMALS[face]
        width=max(4,math.ceil(w*12)); height=max(4,math.ceil(h*12))
        image=np.zeros((height,width,4),dtype=np.uint8); depth=np.full((height,width),-1e30)
        for e in sources:
            for points,uv,normal in mesh(e):
                if np.dot(normal,nz)<.00001: continue
                delta=points-pf[0]
                projected=np.column_stack((delta@nx/w*width,delta@ny/h*height,delta@nz))
                for inds in ((0,1,2),(0,2,3)):
                    t=projected[list(inds)]; ut=uv[list(inds)]
                    xa=max(0,math.floor(t[:,0].min())); xb=min(width,math.ceil(t[:,0].max())+1)
                    ya=max(0,math.floor(t[:,1].min())); yb=min(height,math.ceil(t[:,1].max())+1)
                    if xa>=xb or ya>=yb: continue
                    yy,xx=np.mgrid[ya:yb,xa:xb]; xx=xx+.5; yy=yy+.5
                    ax,ay=t[0,:2]; bx,by=t[1,:2]; cx,cy=t[2,:2]
                    denominator=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy)
                    if abs(denominator)<1e-10: continue
                    a=((by-cy)*(xx-cx)+(cx-bx)*(yy-cy))/denominator
                    b=((cy-ay)*(xx-cx)+(ax-cx)*(yy-cy))/denominator; c=1-a-b
                    z=a*t[0,2]+b*t[1,2]+c*t[2,2]
                    mask=(a>=-1e-7)&(b>=-1e-7)&(c>=-1e-7)&(z>depth[ya:yb,xa:xb])
                    if not mask.any(): continue
                    u=np.clip((a*ut[0,0]+b*ut[1,0]+c*ut[2,0]).astype(int),0,self.original.shape[1]-1)
                    v=np.clip((a*ut[0,1]+b*ut[1,1]+c*ut[2,1]).astype(int),0,self.original.shape[0]-1)
                    texels=self.original[v,u]; mask &= texels[:,:,3]>100
                    image[ya:yb,xa:xb][mask]=texels[mask]
                    depth[ya:yb,xa:xb][mask]=z[mask]
        if not image[:,:,3].any(): return None
        # Color dilation into the 2px gutter prevents dark edges with mipmaps.
        if proxy.get('_relief'):
            valid=image[:,:,3]>0
            if valid.any():
                values=np.where(valid,depth,float(np.median(depth[valid])))
                low=float(values.min());high=float(values.max())
                if high-low>.015:
                    # Height-derived shading carries the little tile/tally relief into the flat texture.
                    encoded=np.clip((values-low)/max(.0001,high-low)*255,0,255).astype(np.uint8)
                    local=np.array(Image.fromarray(encoded).filter(ImageFilter.MaxFilter(5)),dtype=float)/255*(high-low)+low
                    occlusion=np.clip((local-values)*1.4,0,.29)
                    gy,gx=np.gradient(values)
                    gx*=width/max(w,.001);gy*=height/max(h,.001)
                    divisor=np.sqrt(1+gx*gx+gy*gy)
                    diffuse=(.82-.38*gx-.44*gy)/divisor
                    shade=np.clip((.70+.30*diffuse)/(.70+.30*.82)-occlusion,.53,1.10)
                    image[:,:,:3][valid]=np.clip(image[:,:,:3][valid]*shade[valid,None],0,255).astype(np.uint8)
        image=Image.fromarray(image)
        key=(image.size,hashlib.sha256(image.tobytes()).digest())
        if key not in self.cache:
            self.cache[key]=len(self.tiles); self.tiles.append(image)
        return self.cache[key]

    def atlas(self):
        width=max(1024,self.original.shape[1]); x=0; y=self.original.shape[0]; row=0; locations=[None]*len(self.tiles)
        for index in sorted(range(len(self.tiles)),key=lambda i:(-self.tiles[i].height,-self.tiles[i].width)):
            tile=self.tiles[index]
            w,h=tile.size
            if x+w+4>width: x=0; y+=row; row=0
            locations[index]=(x+2,y+2,w,h); x+=w+4; row=max(row,h+4)
        used=y+row; height=1
        while height<used: height*=2
        atlas=Image.new('RGBA',(width,height))
        atlas.paste(Image.fromarray(self.original),(0,0))
        for tile,(x,y,w,h) in zip(self.tiles,locations):
            atlas.paste(tile,(x,y))
            atlas.paste(tile.crop((0,0,1,h)).resize((2,h)),(x-2,y))
            atlas.paste(tile.crop((w-1,0,w,h)).resize((2,h)),(x+w,y))
            atlas.paste(tile.crop((0,0,w,1)).resize((w,2)),(x,y-2))
            atlas.paste(tile.crop((0,h-1,w,h)).resize((w,2)),(x,y+h))
        return atlas,locations


def proxy_box(sources, label, rotation_value=None, origin=None, bounds_override=None):
    r=rotation(rotation_value or [0,0,0]); o=np.array(origin or [8,0,8])
    ps=np.concatenate([p for e in sources for p,u,n in mesh(e)])
    ps=(ps-o)@r+o
    lo,hi=(ps.min(axis=0),ps.max(axis=0)) if bounds_override is None else bounds_override
    return dict(name=label,from_=lo.tolist(),to=hi.tolist(),origin=o.tolist(),rotation=rotation_value or [0,0,0],
      faces={f:{'uv':[0,0,16,16],'texture':0} for f in FACES})


def normalize(e):
    if 'from_' in e: e['from']=e.pop('from_')
    return e


def baked_proxy(sources,baker,label,rotation_value=None,origin=None,template=None,card_axis=None):
    proxy=normalize(proxy_box(sources,label,rotation_value,origin)) if template is None else copy.deepcopy(template)
    proxy['name']=label
    if card_axis is not None:
        midpoint=(proxy['from'][card_axis]+proxy['to'][card_axis])/2
        proxy['from'][card_axis]=proxy['to'][card_axis]=midpoint
        allowed={0:('east','west'),1:('up','down'),2:('north','south')}[card_axis]
        proxy['faces']={f:proxy['faces'][f] for f in allowed}
    proxy['_relief']=label.startswith('baked-roof-')
    faces={}
    for face in list(proxy['faces']):
        index=baker.bake(sources,proxy,face)
        if index is not None: faces[face]={'tile':index,'texture':0}
    proxy['faces']=faces
    return proxy


def family(name): return re.sub(r'[_ ]?\d+$','',name)


def connected(groups, gap=.11):
    result=[]
    for items in groups:
        pending=list(items)
        while pending:
            group=[pending.pop(0)]; lo,hi=bounds(group[0]); changed=True
            while changed:
                changed=False
                for other in pending[:]:
                    a,b=bounds(other)
                    distance=np.maximum(0,np.maximum(a-hi,lo-b))
                    if np.linalg.norm(distance)<=gap:
                        group.append(other); pending.remove(other); lo=np.minimum(lo,a); hi=np.maximum(hi,b); changed=True
            result.append(group)
    return result


def optimize(stem):
    source_path=ROOT/'art-source'/f'{stem}.bbmodel'
    raw=source_path.read_bytes(); master=json.loads(raw)
    original=Image.open(io.BytesIO(base64.b64decode(master['textures'][0]['source'].split(',',1)[1])))
    baseline_quads=sum(1 for e in master['elements'] for f in e['faces'].values() if f.get('texture') is not None)
    baker=Baker(original); es=copy.deepcopy(master['elements']); byid={e['uuid']:e for e in es}
    output=[]; consumed=set(); rigs=[]; operations=[]
    groups={g['name']: [byid[i] for i in g['children'] if isinstance(i,str) and i in byid] for g in master['outliner'] if isinstance(g,dict)}

    def take(items, kind, **options):
        items=[e for e in items if e['uuid'] not in consumed]
        if not items: return
        proxy=baked_proxy(items,baker,kind,**options)
        if proxy['faces']:
            output.append(proxy); consumed.update(e['uuid'] for e in items)
            operations.append({'operation':kind,'sourceElements':len(items),'faces':len(proxy['faces'])})

    profile_name='runtime_profiles.'+stem
    profile=importlib.import_module(profile_name) if importlib.util.find_spec(profile_name) else None
    context=SimpleNamespace(es=es,groups=groups,byid=byid,baker=baker,output=output,consumed=consumed,take=take,
      mesh=mesh,bounds=bounds,rotation=rotation,baked_proxy=baked_proxy,normalize=normalize,proxy_box=proxy_box)
    if profile and hasattr(profile,'early'):profile.early(context)

    # Inertially animated sticks remain seven independent meshes, but each is one baked cuboid.
    for name,items in groups.items():
        if stem=='fortune_barrel' and name.startswith('06 '):
            base=next(e for e in items if e['name'].startswith('竹签芯'))
            take(items,'baked-stick-'+str(len(rigs)),rotation_value=base['rotation'],origin=base['origin'])
            rigs.append(len(output)-1)

    # Dense curved twigs become a few local alpha cards, with existing leaves retained in 3D.
    twigs=[e for e in es if e['name'] in ('曲藤木节','细枝','木质藤节','木质细枝')]
    sectors=collections.defaultdict(list)
    for e in twigs:
        lo,hi=bounds(e); c=(lo+hi)/2
        angle=math.atan2(c[0]-8,c[2]-8)
        sector=int(round(angle/(math.pi/4)))%8
        sectors[(sector,int(c[1]//4))].append(e)
    for (sector,layer),items in sectors.items():
        # The eight sectors use only Java-supported zero/45-degree rotations.
        residual=45 if sector%2 else 0
        card_axis=0 if sector in (2,3,6,7) else 2
        take(items,f'alpha-twigs-{sector}-{layer}',rotation_value=[0,residual,0],card_axis=card_axis)

    if stem=='fortune_barrel':
        # Existing ring profile stays 16-sided; microscopic edge lines are baked into it.
        for prefix in ('04 ','08 '):
            members=[e for n,g in groups.items() if n.startswith(prefix) for e in g]
            if prefix=='04 ': anchors=[e for e in members if re.match(r'(上箍|下箍) \d+$',e['name'])]
            else:
                anchors=[e for e in members if e['name'].startswith('分段顶木')]
                members += [e for n,g in groups.items() if n.startswith('03 ') for e in g]
                members += [e for n,g in groups.items() if n.startswith('02 ') for e in g if e['name'].startswith(('内壁','内侧'))]
            assigned={e['uuid']:[e] for e in anchors}
            for e in members:
                if e in anchors: continue
                c=sum(bounds(e))/2
                anchor=min(anchors,key=lambda a:np.linalg.norm(sum(bounds(a))/2-c))
                assigned[anchor['uuid']].append(e)
            for anchor in anchors:
                take(assigned[anchor['uuid']],'baked-'+anchor['name'],rotation_value=anchor['rotation'],origin=anchor['origin'],template=anchor)

    # The roof retains its stepped cross-section; seams/tiles are projected onto ten long strips.
    if stem=='interpretation_shrine':
        roof=[e for n,g in groups.items() if n.startswith('03 ') for e in g]
        base=collections.defaultdict(list)
        for e in roof:
            if e['name'].startswith('瓦坡台层_'): base[(round(e['from'][0],3),round(e['to'][0],3),round(e['from'][1],3))].append(e)
        ridges=[e for e in roof if e['name']=='瓦垄']
        for name,items in base.items():
            lo=np.min([bounds(e)[0] for e in items],axis=0); hi=np.max([bounds(e)[1] for e in items],axis=0)
            attached=[e for e in ridges if e['uuid'] not in consumed and abs((e['from'][0]+e['to'][0]-lo[0]-hi[0])/2)<.05]
            take(items+attached,'baked-roof-'+str(name),template=normalize(proxy_box(items,name)))
        # Rafters were divided into tiny spans even when their orientation matched.

    # Repeated rafters have the same cross-section; render their array on one alpha prism.
    if stem=='interpretation_shrine':
        rafters=collections.defaultdict(list)
        for e in es:
            if e['name']=='斜椽':
                lo,hi=bounds(e);center=(lo+hi)/2
                rafters[(tuple(e['rotation']),round(float(center[0]),2),round(float(center[1]),2))].append(e)
        for key,items in rafters.items():
            base=items[0]
            take(items,'baked-rafter-array',rotation_value=base['rotation'],origin=base['origin'])
        # Small roof-end discs are cutout panels; retain the actual eave profile behind them.
        tiles=[e for e in es if e['name'] in ('瓦当底座','瓦当凸心')]
        for front in (True,False):
            items=[e for e in tiles if (sum(bounds(e))[2]/2>8)==front]
            take(items,'baked-eave-ends',card_axis=2)
    # Window lattices and ring ornaments are thin alpha geometry with baked openings.
    window_names=('细窗棂','细窗棂横档','后窗竖棂','格栅直棂','格栅横棂')
    panels=collections.defaultdict(list)
    for e in es:
        if e['name'] in window_names and e['uuid'] not in consumed:
            center=sum(bounds(e))/2
            axis=0 if abs(center[0]-8)>abs(center[2]-8) else 2
            panels[(axis,center[axis]>8)].append(e)
    for (axis,side),items in panels.items(): take(items,'baked-window-lattice',card_axis=axis)
    rings=collections.defaultdict(list)
    for e in es:
        if re.search(r'(青铜环|侧提环)_\d+$',e['name']): rings[family(e['name'])].append(e)
    for items in connected(rings.values(),gap=.5): take(items,'baked-ring')

    # Fine relief belongs to the nearest structural face, not hundreds of raised cuboids.
    relief_words=('铆钉','刻档','刻槽','分纹','尾纹','耳内','细金','深线','浅线','柔光','木钉','铜线','金线','纸边','嵌铜','嵌片','榫孔','榫钉','浅金','铜钉','燕尾榫','铜护片')
    remaining=[e for e in es if e['uuid'] not in consumed]
    relief=[e for e in remaining if any(w in e['name'] for w in relief_words)]
    solid=[e for e in remaining if e not in relief and min(np.array(e['to'])-e['from'])>.095]
    assigned=collections.defaultdict(list)
    for detail in relief:
        c=sum(bounds(detail))/2
        candidates=[]
        for parent in solid:
            lo,hi=bounds(parent); d=np.linalg.norm(np.maximum(0,np.maximum(lo-c,c-hi)))
            if d<.28: candidates.append((d+np.linalg.norm((lo+hi)/2-c)*.002,parent))
        if candidates:
            parent=min(candidates,key=lambda pair:pair[0])[1]; assigned[parent['uuid']].append(detail)
    for key,details in assigned.items():
        parent=byid[key]
        take([parent]+details,'baked-relief-'+parent['name'],rotation_value=parent['rotation'],origin=parent['origin'],template=parent)

    # Merge adjacent pieces of the same board/roof/beam; bake the original gaps into alpha.
    families=collections.defaultdict(list)
    for e in es:
        if e['uuid'] in consumed or min(np.array(e['to'])-e['from'])<1e-6 or e['name'].startswith('桶底拼板'): continue
        rot=tuple(e.get('rotation',[0,0,0])); origin=tuple(e['origin']) if any(rot) else (8,0,8)
        families[(family(e['name']),rot,origin)].append(e)
    for items in connected(families.values()):
        if len(items)>1:
            base=items[0]
            take(items,'merged-'+family(base['name']),rotation_value=base['rotation'],origin=base['origin'])
    output.extend(e for e in es if e['uuid'] not in consumed)

    if profile and hasattr(profile,'late'):profile.late(context)

    atlas,locations=baker.atlas()
    for e in output:
        for face,f in e['faces'].items():
            if 'tile' in f:
                x,y,w,h=locations[f.pop('tile')]; f['uv']=[x,y,x+w,y+h]
    # Delete faces completely enclosed by opaque structural cuboids.
    opaque=[]
    image=np.array(atlas)
    for e in output:
        if min(np.array(e['to'])-e['from'])<.001: continue
        okay=True
        for f in e['faces'].values():
            if f.get('texture') is None: continue
            u0,v0,u1,v1=f['uv']; a,b=sorted([int(u0),int(u1)]); c,d=sorted([int(v0),int(v1)])
            if b>a and d>c and image[c:d,a:b,3].min(initial=255)<250: okay=False; break
        if okay: opaque.append(e)
    removed=0
    for e in output:
        r=rotation(e.get('rotation',[0,0,0])); o=np.array(e['origin'])
        for name,points in local_faces(e).items():
            if name not in e['faces'] or e['faces'][name].get('texture') is None: continue
            points=(np.array(points)-o)@r.T+o; normal=r@NORMALS[name]
            samples=np.vstack([points,points.mean(axis=0)])+normal*.002
            for other in opaque:
                if other is e: continue
                ir=rotation(other.get('rotation',[0,0,0])); origin=np.array(other['origin'])
                local=(samples-origin)@ir+origin
                if np.all(local>=np.array(other['from'])-.0001) and np.all(local<=np.array(other['to'])+.0001):
                    del e['faces'][name]; removed+=1; break
    output=[e for e in output if any(f.get('texture') is not None for f in e['faces'].values())]

    # Paper-thin overlays keep their two visual faces, removing four microscopic side walls.
    thin_faces_saved=0
    for e in output:
        dims=np.array(e['to'])-e['from'];order=np.argsort(dims);axis=int(order[0])
        if .001<dims[axis]<.19 and dims[order[1]]>=.42 and dims[order[2]]>=.7:
            allowed={0:('east','west'),1:('up','down'),2:('north','south')}[axis]
            kept={f:v for f,v in e['faces'].items() if f in allowed}
            if not kept:continue
            thin_faces_saved+=len(e['faces'])-len(kept);e['faces']=kept
            middle=(e['from'][axis]+e['to'][axis])/2;e['from'][axis]=e['to'][axis]=middle

    # Merge actual coplanar surfaces, not whole 3D volumes: their alpha silhouette is preserved.
    face_baker=Baker(atlas)
    buckets=collections.defaultdict(list)
    remaining=[copy.deepcopy(e) for e in output]
    for index,e in enumerate(output):
        partition=e['name'] if e['name'].startswith('baked-stick-') else 'shell'
        r=rotation(e.get('rotation',[0,0,0]));o=np.array(e['origin'])
        for face,points in local_faces(e).items():
            if face not in e['faces'] or e['faces'][face].get('texture') is None:continue
            points=(np.array(points)-o)@r.T+o
            if np.linalg.norm(np.cross(points[1]-points[0],points[3]-points[0]))<1e-8:
                remaining[index]['faces'].pop(face,None);continue
            normal=r@NORMALS[face];distance=float(points[0]@normal)
            key=(partition,tuple(np.round(normal,5)),round(distance/.10))
            only=copy.deepcopy(e);only['faces']={face:copy.deepcopy(e['faces'][face])}
            buckets[key].append((index,face,only,points))
    work=[]
    for key, faces in buckets.items():
        if len(faces)<2:continue
        first=faces[0][2];face=faces[0][1]
        proxy=normalize(proxy_box([f[2] for f in faces], 'surface-test', first['rotation'], first['origin']))
        axis=0 if face in ('east','west') else 1 if face in ('up','down') else 2
        area=float(np.prod(np.delete(np.array(proxy['to'])-proxy['from'],axis)))
        covered=sum(float(np.linalg.norm(np.cross(f[3][1]-f[3][0],f[3][3]-f[3][0]))) for f in faces)
        if area<=max(covered*3,6):
            work.append((key,faces))
        else:
            work.extend((key,group) for group in local_surface_groups(faces) if len(group)>1)
    cards=[];merged_faces=0
    for key,faces in work:
        if len(faces)<2:continue
        sources=[f[2] for f in faces];first=faces[0][2];face=faces[0][1]
        proxy=normalize(proxy_box(sources,'merged-surface',first['rotation'],first['origin']))
        axis=0 if face in ('east','west') else 1 if face in ('up','down') else 2
        dims=np.array(proxy['to'])-proxy['from'];area=float(np.prod(np.delete(dims,axis)))
        covered=sum(float(np.linalg.norm(np.cross(f[3][1]-f[3][0],f[3][3]-f[3][0]))) for f in faces)
        middle=(proxy['from'][axis]+proxy['to'][axis])/2;proxy['from'][axis]=proxy['to'][axis]=middle
        proxy['faces']={face:proxy['faces'][face]}
        proxy['name']=key[0] if key[0]!='shell' else 'coplanar-surface-'+str(len(cards))
        tile=face_baker.bake(sources,proxy,face)
        if tile is None:continue
        proxy['faces']={face:{'tile':tile,'texture':0}}
        cards.append(proxy);merged_faces+=len(faces)-1
        for index,name,e,points in faces:remaining[index]['faces'].pop(name,None)
    output=[e for e in remaining if e['faces']]+cards
    atlas,locations=face_baker.atlas()
    for e in cards:
        for f in e['faces'].values():
            x,y,w,h=locations[f.pop('tile')];f['uv']=[x,y,x+w,y+h]

    unpacked_atlas = [atlas.width, atlas.height]
    atlas = compact_atlas(output, atlas)

    DERIVED.mkdir(exist_ok=True)
    derived=copy.deepcopy(master); derived['name']=stem+' optimized game mesh'; derived['elements']=output
    derived['outliner']=[]; derived['resolution']={'width':atlas.width,'height':atlas.height}
    stream=io.BytesIO(); atlas.save(stream,format='PNG')
    derived['textures'][0].update(source='data:image/png;base64,'+base64.b64encode(stream.getvalue()).decode(),width=atlas.width,height=atlas.height,uv_width=atlas.width,uv_height=atlas.height)
    derived['textures'][0].pop('path',None)
    for i,e in enumerate(output):
        e.setdefault('uuid',f'{stem}-derived-{i}'); e.setdefault('type','cube'); e.setdefault('shade',True)
        derived['outliner'].append(e['uuid'])
    (DERIVED/f'{stem}.bbmodel').write_text(json.dumps(derived,ensure_ascii=False,indent=2),'utf-8')
    atlas.save(ASSETS/'textures/block'/f'{stem}.png')

    def export(elements):
        exported=[]
        for e in elements:
            faces={f:{'uv':[round(v*16/(atlas.width if i%2==0 else atlas.height),7) for i,v in enumerate(a['uv'])],'texture':'#0'} for f,a in e['faces'].items() if a.get('texture') is not None}
            if not faces: continue
            out={'name':e['name'],'from':e['from'],'to':e['to'],'faces':faces}
            rot=e.get('rotation',[0,0,0])
            if any(rot):
                axis=next(i for i,v in enumerate(rot) if v)
                assert sum(bool(v) for v in rot)==1 and rot[axis] in (-45,-22.5,0,22.5,45),rot
                out['rotation']={'angle':rot[axis],'axis':'xyz'[axis],'origin':e['origin'],'rescale':False}
            exported.append(out)
        return {'credit':'Derived runtime bake; immutable masters in art-source','texture_size':[atlas.width,atlas.height],
          'textures':{'0':'dailyfortunes:block/'+stem,'particle':'dailyfortunes:block/'+stem},'elements':exported,'display':master.get('display',{}),'render_type':'minecraft:cutout'}
    runtime=export(output)
    (ASSETS/'models/block'/f'{stem}.json').write_text(json.dumps(runtime,ensure_ascii=False,separators=(',',':')),'utf-8')
    if stem=='fortune_barrel':
        (ASSETS/'models/item/barrel_shell.json').write_text(json.dumps(export([e for e in output if not e['name'].startswith('baked-stick-')]),ensure_ascii=False,separators=(',',':')),'utf-8')
        for i in range(7):
            (ASSETS/f'models/item/barrel_stick_{i}.json').write_text(json.dumps(export([e for e in output if e['name']==f'baked-stick-{i}']),ensure_ascii=False,separators=(',',':')),'utf-8')
    assert source_path.read_bytes()==raw,'Master was modified'
    before=baseline_quads; after=sum(len(e['faces']) for e in runtime['elements'])
    report={'model':stem,'masterSha256':hashlib.sha256(raw).hexdigest(),'beforeElements':len(es),'afterElements':len(output),'beforeQuads':before,'afterQuads':after,'reductionPercent':round((1-after/before)*100,2),'hiddenFacesRemoved':removed,'coplanarQuadsSaved':merged_faces,'thinSideQuadsSaved':thin_faces_saved,'atlas':[atlas.width,atlas.height],'unpackedAtlas':unpacked_atlas,'operations':operations}
    (DERIVED/f'{stem}-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='operations'}),flush=True)
    return report


if __name__=='__main__':
    reports=[optimize(stem) for stem in ('fortune_barrel','interpretation_shrine','collection_chest')]
    (DERIVED/'report.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2),'utf-8')
