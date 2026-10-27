"""Author a separate, editable 16x-style barrel study. Never alters shipped assets.

The pixel maps and cuboids are the artwork source, not a reduction of a high-poly
model. One model unit equals one texture pixel (16 pixels per Minecraft block).
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import uuid
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from bake_runtime_models import mesh
from preview_runtime_models import render

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "art-prototypes/fortune_barrel_vanilla16"
STEM = "fortune_barrel_vanilla16"
NAMESPACE = uuid.UUID("3e8567d5-e566-4f5d-b878-2eb9f9b02e33")


def uid(name):
    return str(uuid.uuid5(NAMESPACE, name))


def rgba(color):
    return tuple(bytes.fromhex(color.lstrip("#"))) + (255,)


# Warm honey timber, green iron hoops, fresh foliage and cinnabar accents.
# Saturation is intentional; texture changes still use connected pixel clusters.
WOOD = [rgba(s) for s in ("805429", "996832", "b27d3c", "c28e49", "d3a358", "dfb772")]
GREEN = [rgba(s) for s in ("3d6c32", "598d3d", "78aa49", "a0c361")]
JADE = [rgba(s) for s in ("2c594e", "3c7a64", "72a581")]
RED = [rgba(s) for s in ("a2372b", "ce513c", "e77c56")]
BAMBOO = [rgba(s) for s in ("c29a4e", "e0bd6d", "f1d78e")]


def surface(material, width, height, variant=0):
    """Pixel-authored surfaces, with grouped marks rather than per-pixel noise."""
    w, h = int(np.ceil(width)), int(np.ceil(height))
    assert w > 0 and h > 0
    im = Image.new("RGBA", (w, h), WOOD[3])
    d = ImageDraw.Draw(im)
    if material in ("wood", "wall", "inside", "endgrain"):
        for x in range(w):
            plank = (x + variant) // 4
            d.line((x, 0, x, h - 1), fill=WOOD[2 + plank % 2])
            if (x + variant) % 4 == 3 and w > 3:
                d.line((x, 0, x, h - 1), fill=WOOD[1])
        # A few longer, connected grain marks, each close to the surrounding wood.
        for x, y, length, color in ((1, 2, 3, 3), (5, 5, 2, 1), (8, 1, 3, 4)):
            if x < w and y < h:
                d.line((x, y, x, min(y + length - 1, h - 1)), fill=WOOD[color])
        if material == "endgrain":
            d.rectangle((0, 0, w - 1, h - 1), fill=WOOD[3])
            if h > 1:
                d.line((0, h - 1, w - 1, h - 1), fill=WOOD[2])
            for x in range(3, w, 4):
                d.line((x, 0, x, h - 1), fill=WOOD[2])
        if material == "inside":
            a = np.array(im)
            a[:, :, :3] = (a[:, :, :3].astype(float) * .83).astype(np.uint8)
            im = Image.fromarray(a)
    elif material == "bamboo":
        d.rectangle((0, 0, w - 1, h - 1), fill=BAMBOO[1])
        if w > 1:
            d.line((0, 0, 0, h - 1), fill=BAMBOO[2])
        if h >= 5:
            # Bamboo continues above the colored wrap, avoiding a match-head cap.
            # The same two rows wrap all four sides of every flat fortune stick.
            d.line((0, 2, w - 1, 2), fill=RED[1])
            d.line((0, 3, w - 1, 3), fill=RED[0])
            # A single subdued joint, no tiny writing or decorative tally marks.
            d.line((0, h - 4, w - 1, h - 4), fill=BAMBOO[0])
    elif material == "bamboo_end":
        d.rectangle((0, 0, w - 1, h - 1), fill=BAMBOO[2] if variant else BAMBOO[0])
    elif material in ("metal", "metal_edge"):
        d.rectangle((0, 0, w - 1, h - 1), fill=JADE[1 if material == "metal" else 0])
        if material == "metal" and w >= 6:
            for x in (2, w - 3):
                d.point((x, 0), fill=JADE[2])
    elif material == "red":
        d.rectangle((0, 0, w - 1, h - 1), fill=RED[1])
        d.line((0, h - 1, w - 1, h - 1), fill=RED[0])
        if w > 1:
            d.point((0, 0), fill=RED[2])
    elif material == "leaves":
        # Original cutout cards with stepped edges and connected clusters.
        # No solid leaf cubes, random noise, tiny veins or copied game textures.
        patterns = (
            ("000230", "002343", "023432", "002320", "022200",
             "234320", "344230", "232200", "002330", "000220"),
            ("023000", "234300", "343200", "023230", "002343",
             "002432", "023220", "234300", "023000", "002000"),
            ("000000", "023000", "234320", "343432", "023432", "002320"),
        )
        pattern = patterns[variant % len(patterns)]
        for y in range(h):
            for x in range(w):
                value = int(pattern[y % len(pattern)][x % 6])
                d.point((x, y), fill=(0, 0, 0, 0) if value == 0 else GREEN[value - 1])
    else:
        raise ValueError(material)
    return im


class Atlas:
    def __init__(self):
        self.tiles = []
        self.cache = {}

    def add(self, image):
        key = (image.size, image.tobytes())
        if key not in self.cache:
            self.cache[key] = len(self.tiles)
            self.tiles.append(image)
        return self.cache[key]

    def pack(self):
        size = 64
        while True:
            positions = {}
            x = y = row = 0
            for i in sorted(range(len(self.tiles)), key=lambda j: -self.tiles[j].height):
                w, h = self.tiles[i].size
                if x + w + 2 > size:
                    x, y, row = 0, y + row, 0
                positions[i] = (x + 1, y + 1)
                x += w + 2
                row = max(row, h + 2)
            if y + row <= size:
                break
            size *= 2
        atlas = Image.new("RGBA", (size, size))
        for i, (x, y) in positions.items():
            tile = self.tiles[i]
            w, h = tile.size
            # One pixel of edge padding protects the actual UV rectangles.
            atlas.paste(tile, (x, y))
            atlas.paste(tile.crop((0, 0, 1, h)), (x - 1, y))
            atlas.paste(tile.crop((w - 1, 0, w, h)), (x + w, y))
            atlas.paste(tile.crop((0, 0, w, 1)), (x, y - 1))
            atlas.paste(tile.crop((0, h - 1, w, h)), (x, y + h))
        return atlas, positions


def make_model():
    atlas = Atlas()
    elements, groups = [], {"木桶 / Timber": [], "铁箍 / Hoops": [], "叶片 / Foliage": [], "红绳 / Cord": []}

    def cube(name, lo, hi, faces, group="木桶 / Timber", rotation=None, origin=None):
        dx, dy, dz = [hi[i] - lo[i] for i in range(3)]
        sizes = dict(north=(dx, dy), south=(dx, dy), east=(dz, dy), west=(dz, dy),
                     up=(dx, dz), down=(dx, dz))
        e = {"name": name, "type": "cube", "uuid": uid(name), "from": lo, "to": hi,
             "origin": origin or [8, 0, 8], "rotation": rotation or [0, 0, 0],
             "shade": True, "rescale": False, "faces": {}}
        for f, spec in faces.items():
            material, variant = spec if isinstance(spec, tuple) else (spec, 0)
            w, h = sizes[f]
            assert w > 0 and h > 0, (name, f, sizes[f])
            tile = atlas.add(surface(material, w, h, variant))
            # Thin hoop ledges use fractional UVs at the same 16x density.
            e["faces"][f] = {"texture": 0, "_tile": tile, "_uv_size": [w, h]}
        for f in sizes:
            if f not in e["faces"]:
                e["faces"][f] = {"texture": None, "uv": [0, 0, 0, 0]}
        elements.append(e)
        groups.setdefault(group, []).append(e["uuid"])
        return e

    def card(name, lo, hi, faces, variant=0):
        e = cube(name, lo, hi, {f: ("leaves", variant) for f in faces}, "叶片 / Foliage")
        # Opposite faces must have identical opaque pixels in world space.
        # Otherwise a plant's shape would change when viewed from behind.
        reverse = next(f for f in faces if f in ("north", "west", "down"))
        e["faces"][reverse]["_flip"] = "v" if reverse == "down" else "u"
        return e

    # Hollow square body. Coplanar contacts have no redundant cap faces.
    cube("底座与内底 / Base", [2, 0, 2], [14, 1, 14],
         dict(north="endgrain", south="endgrain", east="endgrain", west="endgrain", up="wood", down="wood"))
    cube("前板 / Front", [3, 1, 12], [13, 11, 13], dict(south="wall", north="inside", west="wood", east="wood"))
    cube("后板 / Back", [3, 1, 3], [13, 11, 4], dict(north=("wall", 1), south="inside", west="wood", east="wood"))
    cube("左板 / Left", [3, 1, 4], [4, 11, 12], dict(west=("wall", 1), east="inside"))
    cube("右板 / Right", [12, 1, 4], [13, 11, 12], dict(east="wall", west="inside"))
    cube("前木沿 / Front rim", [2, 11, 12], [14, 12, 14],
         dict(up="endgrain", south="endgrain", north="inside", west="endgrain", east="endgrain", down="endgrain"))
    cube("后木沿 / Back rim", [2, 11, 2], [14, 12, 4],
         dict(up="endgrain", north="endgrain", south="inside", west="endgrain", east="endgrain", down="endgrain"))
    cube("左木沿 / Left rim", [2, 11, 4], [4, 12, 12],
         dict(up="endgrain", west="endgrain", east="inside", down="endgrain"))
    cube("右木沿 / Right rim", [12, 11, 4], [14, 12, 12],
         dict(up="endgrain", east="endgrain", west="inside", down="endgrain"))

    # Raised square iron hoops, half a model unit proud of the timber.
    # Outer corner faces are continuous; no duplicate coplanar corner faces.
    for y, label in ((2, "下"), (9, "上")):
        for z0, z1, side in ((13, 13.5, "south"), (2.5, 3, "north")):
            cube(f"{label}箍 {side}", [2.5, y, z0], [13.5, y + 1, z1],
                 {side: "metal", "up": "metal_edge", "down": "metal_edge"}, "铁箍 / Hoops")
        for x0, x1, side in ((2.5, 3, "west"), (13, 13.5, "east")):
            cube(f"{label}箍 {side} 上下缘", [x0, y, 3], [x1, y + 1, 13],
                 dict(up="metal_edge", down="metal_edge"), "铁箍 / Hoops")
            x = x0 if side == "west" else x1
            cube(f"{label}箍 {side} 外缘", [x, y, 2.5], [x, y + 1, 13.5],
                 {side: "metal"}, "铁箍 / Hoops")

    # Five small double-sided sheets, staggered around two corners.
    # Plant cards sit outside the hoops/wood; only their natural roots touch them.
    card("左前叶片 / Front leaves", [0, 6, 14.05], [6, 16, 14.05], ("south", "north"))
    card("左侧叶片 / Side leaves", [1.95, 7, 9], [1.95, 16, 15], ("east", "west"), 1)
    card("沿口叶片 / Rim leaves", [0, 12.05, 10], [6, 12.05, 16], ("up", "down"), 2)
    card("后侧叶片 / Back leaves", [10, 8, 1.95], [16, 16, 1.95], ("south", "north"), 1)
    card("右侧叶片 / Right leaves", [14.05, 8, 1], [14.05, 16, 7], ("east", "west"))

    cube("红绳结 / Cinnabar knot", [7, 7, 13], [9, 9, 14],
         {f: "red" for f in ("south", "east", "west", "up", "down")}, "红绳 / Cord", [0, 0, 45], [8, 8, 13.5])
    cube("绳尾 / Cord tail", [7.5, 4, 13], [8.5, 7, 14],
         {f: "red" for f in ("south", "east", "west", "down")}, "红绳 / Cord")

    # Seven independent cuboids with native Java-model rotations and their own pivots.
    rig = []
    for i, (x, z, top, axis, angle) in enumerate((
        (8, 8, 21, "z", 0), (6.3, 8, 19, "z", 22.5), (9.7, 8, 20, "z", -22.5),
        (8, 6.2, 20, "x", -22.5), (8, 9.8, 18, "x", 22.5),
        (6.3, 6.2, 18, "z", 22.5), (9.7, 9.8, 19, "z", -22.5),
    )):
        rot = [0, 0, 0]
        rot["xyz".index(axis)] = angle
        group = f"签 {i + 1} / Stick {i + 1}"
        pivot = [x, 9.3, z]
        e = cube(group, [x - 1, 6, z - .5], [x + 1, top, z + .5],
                 dict(north="bamboo", south="bamboo", east="bamboo", west="bamboo",
                      up=("bamboo_end", 1), down="bamboo_end"), group, rot, pivot)
        rig.append({"index": i, "element": e["uuid"], "origin": pivot, "rotation": rot})

    texture, positions = atlas.pack()
    for e in elements:
        for f in e["faces"].values():
            if f["texture"] is None:
                continue
            i = f.pop("_tile")
            x, y = positions[i]
            w, h = f.pop("_uv_size")
            f["uv"] = [x, y, x + w, y + h]
            flip = f.pop("_flip", None)
            if flip == "u":
                f["uv"][0], f["uv"][2] = f["uv"][2], f["uv"][0]
            elif flip == "v":
                f["uv"][1], f["uv"][3] = f["uv"][3], f["uv"][1]
    stream = io.BytesIO()
    texture.save(stream, format="PNG")
    model = {
        "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
        "name": "问津签筒 · 原版像素试作二版", "model_identifier": STEM,
        "resolution": {"width": texture.width, "height": texture.height},
        "java_block_version": "1.9", "ambientocclusion": True,
        "elements": elements,
        "outliner": [{"name": name, "origin": [8, 0, 8], "uuid": uid("group:" + name),
                      "export": True, "isOpen": False, "children": children} for name, children in groups.items()],
        "textures": [{"name": STEM + ".png", "id": "0", "uuid": uid("texture"),
                      "mode": "bitmap", "saved": True, "width": texture.width, "height": texture.height,
                      "uv_width": texture.width, "uv_height": texture.height,
                      "source": "data:image/png;base64," + base64.b64encode(stream.getvalue()).decode()}],
        "display": {
            "gui": {"rotation": [25, 35, 0], "translation": [0, -2, 0], "scale": [.72, .72, .72]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [.4, .4, .4]},
            "fixed": {"rotation": [0, 180, 0], "translation": [0, -1, 0], "scale": [.65, .65, .65]},
            "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 1, 0], "scale": [.45, .45, .45]},
            "firstperson_righthand": {"rotation": [0, -25, 0], "translation": [1, 1, 0], "scale": [.5, .5, .5]},
        },
    }
    return model, texture, rig


def export_java(model, elements):
    size = model["resolution"]["width"]
    out = []
    for e in elements:
        item = {"name": e["name"], "from": e["from"], "to": e["to"], "faces": {}}
        for side, f in e["faces"].items():
            if f["texture"] is None:
                continue
            item["faces"][side] = {"texture": "#0", "uv": [v * 16 / size for v in f["uv"]]}
        if any(e["rotation"]):
            a = next(i for i, v in enumerate(e["rotation"]) if v)
            item["rotation"] = {"axis": "xyz"[a], "angle": e["rotation"][a], "origin": e["origin"], "rescale": False}
        out.append(item)
    return {"credit": "Original PixelMC Projects art study; AGPL-3.0-only",
            "textures": {"0": "dailyfortunes:block/" + STEM, "particle": "dailyfortunes:block/" + STEM},
            "texture_size": [size, size], "ambientocclusion": True, "render_type": "minecraft:cutout",
            "display": model["display"], "elements": out}


def verify(model, texture, rig, immutable):
    assert len(rig) == 7
    faces = [entry for e in model["elements"] for entry in mesh(e)]
    assert len(faces) <= 128, len(faces)
    assert texture.width <= 64 and texture.height <= 64
    for points, uv, normal in faces:
        # Every visible surface has one texel per model unit, also after rotation.
        world_w, world_h = np.linalg.norm(points[1] - points[0]), np.linalg.norm(points[3] - points[0])
        uv_w, uv_h = np.linalg.norm(uv[1] - uv[0]), np.linalg.norm(uv[3] - uv[0])
        assert abs(world_w - uv_w) < 1e-6 and abs(world_h - uv_h) < 1e-6
        assert np.all(uv >= 0) and np.all(uv <= texture.width)
    for e in model["elements"]:
        rot = [v for v in e["rotation"] if v]
        assert len(rot) <= 1 and all(v in (-45, -22.5, 22.5, 45) for v in rot)
    for path, before in immutable.items():
        assert hashlib.sha256(path.read_bytes()).hexdigest() == before, f"Changed shipped asset: {path}"
    return {"status": "local art prototype; not installed or released", "revision": 2, "elements": len(model["elements"]),
            "quads": len(faces), "triangles": len(faces) * 2, "texture": list(texture.size),
            "texelsPerBlock": 16, "stickGroups": len(rig),
            "foliageQuads": sum(sum(f["texture"] is not None for f in e["faces"].values()) for e in model["elements"] if "leaves" in e["name"].lower()),
            "hoopQuads": sum(sum(f["texture"] is not None for f in e["faces"].values()) for e in model["elements"] if "箍" in e["name"]),
            "previousPrototypeQuads": 106,
            "sourceAndRuntimePreserved": True,
            "baselineRuntimeQuads": 372, "reductionAgainstRuntimePercent": round(100 * (1 - len(faces) / 372), 1),
            "performance": "Static geometry only; no in-game FPS claim."}


def previews(model, report):
    all_points = np.concatenate([p for e in model["elements"] for p, uv, n in mesh(e)])
    bounds = (all_points.min(axis=0), all_points.max(axis=0))
    bg = (37, 40, 37)
    sheet = Image.new("RGB", (1440, 650), bg)
    draw = ImageDraw.Draw(sheet)
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 23) if font_path.exists() else ImageFont.load_default()
    small = ImageFont.truetype(str(font_path), 17) if font_path.exists() else ImageFont.load_default()
    draw.text((35, 22), "问津签筒  /  原版像素试作 · 二版", font=font, fill=(224, 226, 215))
    draw.text((35, 58), f"16× 像素密度   ·   {report['quads']} 个四边面   ·   7 支独立签", font=small, fill=(156, 167, 149))
    for i, (view, label) in enumerate((((.7, .42, 1), "正面"), ((-.75, .45, -1), "背面"), ((.35, 1.3, 1), "俯视"))):
        pixels = render(model, view, bounds)
        sheet.paste(Image.fromarray(pixels).convert("RGB"), (i * 480, 90))
        draw.text((i * 480 + 32, 610), label, font=small, fill=(178, 185, 169))
    sheet.save(OUT / "preview.png")
    Image.fromarray(render(model, (.7, .42, 1), bounds)).save(OUT / "front.png")


def main():
    immutable_paths = list((ROOT / "art-source").glob("*.bbmodel")) + list((ROOT / "art-runtime").glob("*.bbmodel"))
    immutable_paths += list((ROOT / "src/main/resources/assets/dailyfortunes").rglob("*"))
    immutable = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in immutable_paths if p.is_file()}
    OUT.mkdir(parents=True, exist_ok=True)
    model, texture, rig = make_model()
    report = verify(model, texture, rig, immutable)
    (OUT / (STEM + ".bbmodel")).write_text(json.dumps(model, ensure_ascii=False, indent=2), "utf-8")
    texture.save(OUT / (STEM + ".png"))
    export_dir = OUT / "java-models"
    export_dir.mkdir(exist_ok=True)
    (export_dir / (STEM + ".json")).write_text(json.dumps(export_java(model, model["elements"]), ensure_ascii=False, indent=2), "utf-8")
    sticks = {r["element"] for r in rig}
    (export_dir / "barrel_shell.json").write_text(json.dumps(export_java(model, [e for e in model["elements"] if e["uuid"] not in sticks]), ensure_ascii=False, indent=2), "utf-8")
    for r in rig:
        (export_dir / f"barrel_stick_{r['index']}.json").write_text(json.dumps(export_java(model, [e for e in model["elements"] if e["uuid"] == r["element"]]), ensure_ascii=False, indent=2), "utf-8")
    (OUT / "rig.json").write_text(json.dumps({"units": "Minecraft model units, 16 per block", "sticks": rig}, ensure_ascii=False, indent=2), "utf-8")
    (OUT / "model-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), "utf-8")
    previews(model, report)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
