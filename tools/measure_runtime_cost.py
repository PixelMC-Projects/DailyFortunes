"""Compare geometry and alpha-card area. These are asset metrics, not an FPS benchmark."""
from __future__ import annotations

import base64
import io
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from bake_runtime_models import ROOT, mesh


def measure(folder):
    results = []
    for stem in ('fortune_barrel', 'interpretation_shrine', 'collection_chest'):
        model = json.loads((folder / (stem + '.bbmodel')).read_text('utf-8'))
        image = np.array(Image.open(io.BytesIO(base64.b64decode(
            model['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA'))
        area = opaque = 0.0
        quads = 0
        for element in model['elements']:
            for points, uv, normal in mesh(element):
                size = float(np.linalg.norm(np.cross(points[1] - points[0], points[3] - points[0])))
                left, top = np.floor(uv.min(axis=0)).astype(int)
                right, bottom = np.ceil(uv.max(axis=0)).astype(int)
                pixels = image[max(0, top):min(image.shape[0], max(top + 1, bottom)),
                               max(0, left):min(image.shape[1], max(left + 1, right)), 3]
                area += size
                opaque += size * (float(np.mean(pixels > 100)) if pixels.size else 0)
                quads += 1
        results.append(dict(model=stem, quads=quads, quadArea=round(area, 2),
                            opaqueArea=round(opaque, 2), transparentArea=round(area - opaque, 2),
                            atlasPixels=int(image.shape[0] * image.shape[1])))
    return results


if __name__ == '__main__':
    folders = [Path(p) for p in sys.argv[1:]] or [ROOT / 'art-runtime']
    print(json.dumps({str(folder): measure(folder) for folder in folders}, indent=2))
