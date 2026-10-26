"""Render matched views from the masters and derived runtime geometry for inspection."""
import base64, io, json, math
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
from bake_runtime_models import ROOT, DERIVED, mesh


def render(model, view, bounds):
    texture=np.array(Image.open(io.BytesIO(base64.b64decode(model['textures'][0]['source'].split(',',1)[1]))).convert('RGBA'))
    forward=np.array(view,dtype=float); forward/=np.linalg.norm(forward)
    right=np.cross([0,1,0],forward); right/=np.linalg.norm(right)
    up=np.cross(forward,right); basis=np.array([right,up,forward])
    lo,hi=bounds; center=(lo+hi)/2
    corners=np.array([[x,y,z] for x in (lo[0],hi[0]) for y in (lo[1],hi[1]) for z in (lo[2],hi[2])])
    projected=(corners-center)@basis.T
    width,height=480,520
    scale=min((width-48)/np.ptp(projected[:,0]),(height-54)/np.ptp(projected[:,1]))
    rgb=np.zeros((height,width,4),dtype=np.uint8); rgb[:,:,:3]=(37,40,37)
    depth=np.full((height,width),-1e30)
    light=np.array([-.45,.85,.7]);light/=np.linalg.norm(light)
    for e in model['elements']:
        for points,uv,normal in mesh(e):
            if np.dot(normal,forward)<=0:continue
            p=(points-center)@basis.T;p[:,0]=width/2+p[:,0]*scale;p[:,1]=height/2-p[:,1]*scale
            shade=.70+.28*max(0,np.dot(normal,light))
            for inds in ((0,1,2),(0,2,3)):
                t=p[list(inds)];ut=uv[list(inds)]
                xa=max(0,math.floor(t[:,0].min()));xb=min(width,math.ceil(t[:,0].max())+1)
                ya=max(0,math.floor(t[:,1].min()));yb=min(height,math.ceil(t[:,1].max())+1)
                if xa>=xb or ya>=yb:continue
                yy,xx=np.mgrid[ya:yb,xa:xb];xx=xx+.5;yy=yy+.5
                ax,ay=t[0,:2];bx,by=t[1,:2];cx,cy=t[2,:2]
                denominator=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy)
                if abs(denominator)<1e-10:continue
                a=((by-cy)*(xx-cx)+(cx-bx)*(yy-cy))/denominator
                b=((cy-ay)*(xx-cx)+(ax-cx)*(yy-cy))/denominator;c=1-a-b
                z=a*t[0,2]+b*t[1,2]+c*t[2,2]
                mask=(a>=-1e-7)&(b>=-1e-7)&(c>=-1e-7)&(z>depth[ya:yb,xa:xb])
                if not mask.any():continue
                u=np.clip((a*ut[0,0]+b*ut[1,0]+c*ut[2,0]).astype(int),0,texture.shape[1]-1)
                v=np.clip((a*ut[0,1]+b*ut[1,1]+c*ut[2,1]).astype(int),0,texture.shape[0]-1)
                texels=texture[v,u];mask&=texels[:,:,3]>100
                region=rgb[ya:yb,xa:xb];region[:,:,:3][mask]=(texels[:,:,:3][mask]*shade).astype(np.uint8);region[:,:,3][mask]=255
                depth[ya:yb,xa:xb][mask]=z[mask]
    return rgb


if __name__=='__main__':
    output=ROOT/'build/verification/model-comparison';output.mkdir(parents=True,exist_ok=True)
    scores=[]
    for stem in ('fortune_barrel','interpretation_shrine','collection_chest'):
        original=json.loads((ROOT/'art-source'/f'{stem}.bbmodel').read_text('utf-8'))
        derived=json.loads((DERIVED/f'{stem}.bbmodel').read_text('utf-8'))
        vertices=np.concatenate([p for e in original['elements'] for p,u,n in mesh(e)])
        bounds=(vertices.min(axis=0),vertices.max(axis=0))
        sheet=Image.new('RGB',(960,3*550),(37,40,37));draw=ImageDraw.Draw(sheet)
        for row,view in enumerate(((.67,.45,1),(-.67,.45,-1),(-.3,1.4,1))):
            a=render(original,view,bounds);b=render(derived,view,bounds)
            for col,pixels in enumerate((a,b)):
                sheet.paste(Image.fromarray(pixels).convert('RGB'),(col*480,row*550+25))
            draw.text((12,row*550+6),'MASTER - original geometry',fill='white')
            draw.text((492,row*550+6),'RUNTIME - baked geometry',fill='white')
            am=a[:,:,3]>0;bm=b[:,:,3]>0
            iou=float((am&bm).sum()/max(1,(am|bm).sum()))
            scores.append({'model':stem,'view':row,'silhouetteIoU':round(iou,4)})
        sheet.save(output/f'{stem}.png')
    (output/'metrics.json').write_text(json.dumps(scores,indent=2),'utf-8')
    print(json.dumps(scores))
