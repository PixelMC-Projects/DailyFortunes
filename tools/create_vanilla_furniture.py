"""Author matching 16x shrine/chest studies; never replace production assets.

Uses the approved barrel's palette, pixel surfaces and atlas packing. Geometry
is authored at its final budget instead of reducing the old detailed models.
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import create_vanilla_barrel as style
from bake_runtime_models import mesh
from preview_runtime_models import render

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "art-prototypes"
SIDES = ("north", "south", "east", "west", "up", "down")


def surface(kind, width, height, variant=0):
    w, h = int(np.ceil(width)), int(np.ceil(height))
    if kind not in ("tiles", "tile_edge", "paper", "drawer", "brass"):
        return style.surface(kind, width, height, variant)
    im = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(im)
    if kind == "tiles":
        colors = [style.rgba(c) for c in ("2d6655", "39846a", "4a9474", "61a583")]
        d.rectangle((0, 0, w - 1, h - 1), fill=colors[1])
        # Broad connected tile courses. No individually modelled roof tiles.
        for x in range(w):
            for y in range(h):
                course = x // 3
                seam = (y + (course % 2) * 2) % 4
                color = colors[0] if seam == 3 else colors[2 if x % 3 == 0 else 1]
                d.point((x, y), fill=color)
        if variant:
            im = im.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    elif kind == "tile_edge":
        d.rectangle((0, 0, w - 1, h - 1), fill=style.JADE[0])
        d.line((0, 0, w - 1, 0), fill=style.JADE[1])
    elif kind == "paper":
        paper = [style.rgba(c) for c in ("d4b778", "eed59a", "f6e4b7")]
        d.rectangle((0, 0, w - 1, h - 1), fill=paper[1])
        if w > 2 and h > 2:
            d.rectangle((1, 0, w - 2, h - 2), fill=paper[2])
            for x in (1, 3):
                if x < w - 1:
                    d.line((x, 1, x, min(2, h - 2)), fill=style.WOOD[1])
            d.point((w - 2, h - 2), fill=style.RED[1])
    elif kind == "drawer":
        im = style.surface("wood", width, height, variant)
        d = ImageDraw.Draw(im)
        d.line((0, 0, w - 1, 0), fill=style.WOOD[4])
        d.line((0, h - 1, w - 1, h - 1), fill=style.WOOD[1])
    elif kind == "brass":
        d.rectangle((0, 0, w - 1, h - 1), fill=style.BAMBOO[0])
        d.line((0, 0, w - 1, 0), fill=style.BAMBOO[2])
    return im


class Builder:
    def __init__(self, name, stem):
        self.name, self.stem = name, stem
        self.atlas = style.Atlas()
        self.elements, self.groups = [], {}

    def cube(self, name, lo, hi, faces, group="木作 / Timber", rotation=None, origin=None):
        dx, dy, dz = [hi[i] - lo[i] for i in range(3)]
        sizes = dict(north=(dx, dy), south=(dx, dy), east=(dz, dy), west=(dz, dy), up=(dx, dz), down=(dx, dz))
        e = {"name": name, "type": "cube", "uuid": style.uid(self.stem + ":" + name),
             "from": lo, "to": hi, "origin": origin or [8, 0, 8],
             "rotation": rotation or [0, 0, 0], "shade": True, "rescale": False, "faces": {}}
        if isinstance(faces, str):
            faces = {f: faces for f in SIDES}
        for f in SIDES:
            if f not in faces:
                e["faces"][f] = {"texture": None, "uv": [0, 0, 0, 0]}
                continue
            material, variant = faces[f] if isinstance(faces[f], tuple) else (faces[f], 0)
            w, h = sizes[f]
            assert w > 0 and h > 0
            if material in ("wood", "inside") and f in ("north", "south", "east", "west"):
                # One continuous world-space pixel grid across posts, boards and
                # gable fillers. Independent parts must not restart the grain.
                tile_image = Image.new("RGBA", (16, 32))
                repeat = style.surface(material, 16, 16)
                tile_image.paste(repeat, (0, 0))
                tile_image.paste(repeat, (0, 16))
                tile = self.atlas.add(tile_image)
                if f == "south":
                    u0, u1 = lo[0], hi[0]
                elif f == "north":
                    u0, u1 = 16 - hi[0], 16 - lo[0]
                elif f == "east":
                    u0, u1 = 16 - hi[2], 16 - lo[2]
                else:
                    u0, u1 = lo[2], hi[2]
                e["faces"][f] = {"texture": 0, "_tile": tile, "_rect": [u0, 32 - hi[1], u1, 32 - lo[1]]}
                continue
            tile = self.atlas.add(surface(material, w, h, variant))
            e["faces"][f] = {"texture": 0, "_tile": tile, "_size": [w, h]}
        self.elements.append(e)
        self.groups.setdefault(group, []).append(e["uuid"])
        return e

    def leaves(self, name, lo, hi, faces, variant=0):
        e = self.cube(name, lo, hi, {f: ("leaves", variant) for f in faces}, "叶片 / Foliage")
        reverse = next(f for f in faces if f in ("north", "west", "down"))
        e["faces"][reverse]["_flip"] = "v" if reverse == "down" else "u"

    def knot(self, x, y, z, side="front"):
        group = "红绳 / Cord"
        if side == "front":
            self.cube("红绳结", [x - 1, y - 1, z], [x + 1, y + 1, z + 1],
                      {f: "red" for f in ("south", "east", "west", "up", "down")}, group, [0, 0, 45], [x, y, z + .5])
            self.cube("绳尾", [x - .5, y - 4, z], [x + .5, y - 1, z + 1],
                      {f: "red" for f in ("south", "east", "west", "down")}, group)
        else:
            self.cube("红绳结", [x, y - 1, z - 1], [x + 1, y + 1, z + 1],
                      {f: "red" for f in ("east", "north", "south", "up", "down")}, group, [45, 0, 0], [x + .5, y, z])
            self.cube("绳尾", [x, y - 4, z - .5], [x + 1, y - 1, z + .5],
                      {f: "red" for f in ("east", "north", "south", "down")}, group)

    def finish(self):
        texture, positions = self.atlas.pack()
        for e in self.elements:
            for f in e["faces"].values():
                if f["texture"] is None:
                    continue
                i = f.pop("_tile")
                x, y = positions[i]
                if "_rect" in f:
                    a, b, c, d = f.pop("_rect")
                    f["uv"] = [x + a, y + b, x + c, y + d]
                else:
                    w, h = f.pop("_size")
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
            "name": self.name + " · 原版像素试作", "model_identifier": self.stem,
            "resolution": {"width": texture.width, "height": texture.height},
            "java_block_version": "1.9", "ambientocclusion": True, "elements": self.elements,
            "outliner": [{"name": name, "origin": [8, 0, 8], "uuid": style.uid(self.stem + ":group:" + name),
                          "export": True, "isOpen": False, "children": children} for name, children in self.groups.items()],
            "textures": [{"name": self.stem + ".png", "id": "0", "uuid": style.uid(self.stem + ":texture"),
                          "mode": "bitmap", "saved": True, "width": texture.width, "height": texture.height,
                          "uv_width": texture.width, "uv_height": texture.height,
                          "source": "data:image/png;base64," + base64.b64encode(stream.getvalue()).decode()}],
            "display": {
                "gui": {"rotation": [25, 35, 0], "translation": [0, -1, 0], "scale": [.76, .76, .76]},
                "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [.4, .4, .4]},
                "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [.65, .65, .65]},
                "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 1, 0], "scale": [.45, .45, .45]},
                "firstperson_righthand": {"rotation": [0, -25, 0], "translation": [1, 1, 0], "scale": [.5, .5, .5]},
            },
        }
        return model, texture


def shrine():
    b = Builder("解签龛", "interpretation_shrine_vanilla16")
    b.cube("底座", [1, 0, 2], [15, 1, 14], "endgrain")
    b.cube("内台地板", [2, 1, 3], [14, 2, 13], {f: "wood" for f in SIDES if f != "down"})
    for x, z, name in ((2, 3, "左后柱"), (12, 3, "右后柱"), (2, 11, "左前柱"), (12, 11, "右前柱")):
        b.cube(name, [x, 2, z], [x + 2, 13, z + 2], {f: "wood" for f in SIDES if f not in ("up", "down")})
    b.cube("背板", [4, 2, 3], [12, 13, 4], dict(south="inside", north="wood", up="endgrain"))
    b.cube("左侧围板", [2, 2, 5], [3, 12, 11], dict(west="wood", east="inside"))
    b.cube("右侧围板", [13, 2, 5], [14, 12, 11], dict(east="wood", west="inside"))
    for z, label in ((3, "后"), (11, "前")):
        b.cube(label + "横梁", [2, 13, z], [14, 14, z + 2], dict(north="endgrain", south="endgrain", east="endgrain", west="endgrain", down="wood"))
        # Step intervals fit inside the pitched roof's thickness. The visible
        # triangle is continuous, with no daylight between the roof and gable.
        for i, (x, top) in enumerate(((2, 15.35), (4, 16.18), (6, 17.01), (8, 17.01), (10, 16.18), (12, 15.35))):
            faces = dict(north="wood", south="wood")
            if i == 0:
                faces["west"] = "wood"
            elif i == 5:
                faces["east"] = "wood"
            b.cube(f"{label}山墙 {i + 1}", [x, 14, z], [x + 2, top, z + 2], faces)
    for x, name in ((2, "左"), (12, "右")):
        b.cube(name + "檐下梁", [x, 12, 5], [x + 2, 15.35, 11], dict(east="wood", west="wood", down="wood"))
    for name, lo, hi, angle in (("左屋面", [0, 17, 1], [8, 18, 15], 22.5),
                               ("右屋面", [8, 17, 1], [16, 18, 15], -22.5)):
        b.cube(name, lo, hi, {f: ("tiles", 1 if angle < 0 else 0) if f == "up" else "wood" if f == "down" else "tile_edge" for f in SIDES},
               "屋顶 / Roof", [0, 0, angle], [8, 18, 8])
    b.cube("屋脊", [7, 17, .5], [9, 19, 15.5], "tile_edge", "屋顶 / Roof")
    # Low table and a large readable paper shape; the niche stays hollow.
    b.cube("解签台座", [5, 2, 8], [11, 4, 11], {f: "wood" for f in SIDES if f not in ("up", "down")}, "解签台 / Reading desk")
    b.cube("解签台沿", [4, 4, 7], [12, 5, 12], dict(up="endgrain", north="metal", south="metal", west="metal", east="metal", down="wood"), "解签台 / Reading desk")
    b.cube("签笺", [5, 5.05, 8], [11, 5.05, 12], dict(up="paper"), "解签台 / Reading desk")
    for z, label in ((7, "上"), (12, "下")):
        b.cube(label + "纸轴", [5, 5.05, z], [11, 6.05, z + 1], dict(up="bamboo_end", north="brass", south="brass", east="brass", west="brass"), "解签台 / Reading desk")
    b.knot(13, 10, 13)
    b.leaves("左前叶片", [0, 4, 14.05], [6, 14, 14.05], ("south", "north"))
    b.leaves("左侧叶片", [.95, 5, 8], [.95, 14, 14], ("east", "west"), 1)
    b.leaves("后右叶片", [10, 3, 1.95], [16, 13, 1.95], ("south", "north"), 1)
    b.leaves("右侧叶片", [15.05, 4, 2], [15.05, 13, 8], ("east", "west"))
    return b.finish()


def chest():
    b = Builder("藏签匣", "collection_chest_vanilla16")
    b.cube("底座", [1, 0, 2], [15, 1, 14], "endgrain")
    b.cube("柜体", [2, 1, 3], [14, 9, 13], {f: "wood" for f in SIDES if f not in ("up", "down")})
    # Three solid fronts; hidden drawer boxes/interiors are deliberately absent.
    for name, lo, hi in (("左上抽屉", [3, 5, 13], [7, 8, 13.25]),
                         ("右上抽屉", [9, 5, 13], [13, 8, 13.25]),
                         ("下层抽屉", [3, 1.5, 13], [13, 4.5, 13.25])):
        b.cube(name, lo, hi, {f: "drawer" if f == "south" else "endgrain" for f in SIDES if f != "north"}, "抽屉 / Drawers")
    for x, label in ((4.5, "左"), (10.5, "右")):
        b.cube(label + "铜扣", [x, 6, 13.25], [x + 1, 7, 13.75],
               {f: "brass" for f in SIDES if f != "north"}, "铜件 / Hardware")
    b.cube("下层铜把手", [6.5, 2.5, 13.25], [9.5, 3.5, 13.75],
           {f: "brass" for f in SIDES if f != "north"}, "铜件 / Hardware")
    b.cube("匣盖", [1, 9, 2], [15, 10, 14], {f: "wood" if f in ("up", "down") else "endgrain" for f in SIDES}, "匣盖 / Lid")
    for z0, z1, side in ((2, 3, "north"), (13, 14, "south")):
        b.cube("盖框 " + side, [1, 10, z0], [15, 10.5, z1],
               dict(up="metal", north="metal_edge", south="metal_edge", east="metal_edge", west="metal_edge"), "匣盖 / Lid")
    for x0, x1, side in ((1, 2, "west"), (14, 15, "east")):
        b.cube("盖框 " + side, [x0, 10, 3], [x1, 10.5, 13], dict(up="metal", east="metal_edge", west="metal_edge"), "匣盖 / Lid")
    # A thin closed bottom hoop, rather than unsupported floating exterior cards.
    for z0, z1, side in ((13, 13.3, "south"), (2.75, 3, "north")):
        b.cube("护条 " + side, [1.75, 1, z0], [14.25, 2, z1],
               {side: "metal", "up": "metal_edge", "down": "metal_edge"}, "铜件 / Hardware")
    for x0, x1, side in ((1.75, 2, "west"), (14, 14.25, "east")):
        b.cube("护条 " + side + " 上下缘", [x0, 1, 3], [x1, 2, 13],
               dict(up="metal_edge", down="metal_edge"), "铜件 / Hardware")
        x = x0 if side == "west" else x1
        b.cube("护条 " + side + " 外缘", [x, 1, 2.75], [x, 2, 13.3], {side: "metal"}, "铜件 / Hardware")
    b.knot(14, 7, 8, "side")
    b.leaves("右侧叶片", [15.05, 4, 3], [15.05, 13, 9], ("east", "west"), 1)
    b.leaves("后侧叶片", [10, 3, 1.95], [16, 13, 1.95], ("south", "north"))
    b.leaves("盖沿叶片", [10, 10.55, 1], [16, 10.55, 7], ("up", "down"), 2)
    b.leaves("左侧叶片", [.95, 2, 3], [.95, 10, 9], ("east", "west"), 1)
    return b.finish()


def validate(model, texture, budget, old_quads):
    faces = [f for e in model["elements"] for f in mesh(e)]
    assert len(faces) <= budget
    assert max(texture.size) <= 128
    for points, uv, normal in faces:
        for i in (1, 3):
            assert abs(np.linalg.norm(points[i] - points[0]) - np.linalg.norm(uv[i] - uv[0])) < 1e-6
        assert np.all(uv >= 0) and np.all(uv <= texture.width)
    for e in model["elements"]:
        rot = [v for v in e["rotation"] if v]
        assert len(rot) <= 1 and all(v in (-45, -22.5, 22.5, 45) for v in rot)
    return {"status": "art study, not integrated or installed", "elements": len(model["elements"]),
            "quads": len(faces), "triangles": len(faces) * 2, "faceBudget": budget,
            "texture": list(texture.size), "texelsPerBlock": 16,
            "previousRuntimeQuads": old_quads, "reductionPercent": round((1 - len(faces) / old_quads) * 100, 1),
            "styleReference": "fortune_barrel_vanilla16 revision 2, ec8209d",
            "runtimeAssetsPreserved": True, "performance": "No measured FPS claim."}


def font(size):
    path = Path("C:/Windows/Fonts/msyh.ttc")
    return ImageFont.truetype(str(path), size) if path.exists() else ImageFont.load_default()


def save(model, texture, report, label):
    stem = model["model_identifier"]
    dest = OUT / stem
    dest.mkdir(parents=True, exist_ok=True)
    (dest / (stem + ".bbmodel")).write_text(json.dumps(model, ensure_ascii=False, indent=2), "utf-8")
    texture.save(dest / (stem + ".png"))
    exported = style.export_java(model, model["elements"])
    exported["textures"] = {"0": "dailyfortunes:block/" + stem, "particle": "dailyfortunes:block/" + stem}
    (dest / (stem + ".json")).write_text(json.dumps(exported, ensure_ascii=False, indent=2), "utf-8")
    (dest / "model-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), "utf-8")
    points = np.concatenate([p for e in model["elements"] for p, _, _ in mesh(e)])
    bounds = points.min(axis=0), points.max(axis=0)
    sheet = Image.new("RGB", (1440, 650), (37, 40, 37))
    d = ImageDraw.Draw(sheet)
    d.text((35, 22), label + "  /  原版像素试作", font=font(23), fill=(224, 226, 215))
    d.text((35, 58), f"16× 像素密度   ·   {report['quads']} 个四边面   ·   二维像素植物", font=font(17), fill=(156, 167, 149))
    for i, (view, caption) in enumerate((((.7, .42, 1), "正面"), ((-.75, .45, -1), "背面"), ((.35, 1.3, 1), "俯视"))):
        pixels = render(model, view, bounds)
        sheet.paste(Image.fromarray(pixels).convert("RGB"), (i * 480, 90))
        d.text((i * 480 + 32, 610), caption, font=font(17), fill=(178, 185, 169))
    sheet.save(dest / "preview.png")


def lineup(models, reports):
    sheet = Image.new("RGB", (1440, 650), (37, 40, 37))
    d = ImageDraw.Draw(sheet)
    d.text((35, 22), "问津系列  /  统一原版像素风格", font=font(23), fill=(224, 226, 215))
    d.text((35, 58), "16× 像素密度 · 鲜明配色 · 少量立体结构 · 二维植物", font=font(17), fill=(156, 167, 149))
    shared_bounds = (np.array([-1, 0, 0]), np.array([17, 22, 17]))
    for i, (model, report, label) in enumerate(zip(models, reports, ("问津签筒", "解签龛", "藏签匣"))):
        pixels = render(model, (.7, .42, 1), shared_bounds)
        sheet.paste(Image.fromarray(pixels).convert("RGB"), (i * 480, 90))
        d.text((i * 480 + 32, 610), f"{label}  ·  {report['quads']} 面", font=font(17), fill=(199, 205, 188))
    sheet.save(OUT / "vanilla-set-preview.png")


def main():
    protected = list((ROOT / "art-source").glob("*.bbmodel")) + list((ROOT / "art-runtime").glob("*.bbmodel"))
    protected += list((ROOT / "src/main/resources/assets/dailyfortunes").rglob("*"))
    protected += list((OUT / "fortune_barrel_vanilla16").rglob("*"))
    before = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in protected if p.is_file()}
    models, reports = [], []
    for generate, budget, old_quads, label in ((shrine, 160, 613, "解签龛"), (chest, 128, 402, "藏签匣")):
        model, texture = generate()
        report = validate(model, texture, budget, old_quads)
        save(model, texture, report, label)
        models.append(model)
        reports.append(report)
        print(json.dumps({"model": label, **report}, ensure_ascii=False))
    barrel = json.loads((OUT / "fortune_barrel_vanilla16/fortune_barrel_vanilla16.bbmodel").read_text("utf-8"))
    barrel_report = json.loads((OUT / "fortune_barrel_vanilla16/model-report.json").read_text("utf-8"))
    lineup([barrel] + models, [barrel_report] + reports)
    for path, digest in before.items():
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, f"Protected artwork changed: {path}"


if __name__ == "__main__":
    main()
