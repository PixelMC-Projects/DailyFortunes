"""Build a cheap alpha-contour overlay and exact hit mask from the unchanged slip texture."""
import json, struct
from PIL import Image, ImageFilter
from bake_runtime_models import ASSETS

image=Image.open(ASSETS/'textures/item/fortune_slip.png').convert('RGBA')
alpha=image.getchannel('A');data=alpha.tobytes();bits=bytearray((len(data)+7)//8)
for i,a in enumerate(data):
    if a>80:bits[i>>3]|=1<<(i&7)
(ASSETS/'slip-mask.bin').write_bytes(struct.pack('>HH',image.width,image.height)+bits)
small=alpha.resize((256,256),Image.Resampling.NEAREST).point(lambda a:255 if a>80 else 0)
expanded=small.filter(ImageFilter.MaxFilter(7))
ring=Image.frombytes('L',small.size,bytes(255 if b>a else 0 for a,b in zip(small.tobytes(),expanded.tobytes())))
outline=Image.new('RGBA',(256,256),(224,185,88,0));outline.putalpha(ring)
outline.save(ASSETS/'textures/item/fortune_slip_outline.png')
model={'textures':{'0':'dailyfortunes:item/fortune_slip_outline','particle':'dailyfortunes:item/fortune_slip_outline'},
       'elements':[{'from':[0,0,8],'to':[16,16,8],'faces':{f:{'uv':[0,0,16,16],'texture':'#0'} for f in ('north','south')}}]}
(ASSETS/'models/item/fortune_slip_outline.json').write_text(json.dumps(model,separators=(',',':')),'utf-8')
