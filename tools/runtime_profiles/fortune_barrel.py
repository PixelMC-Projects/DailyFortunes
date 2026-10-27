"""Approved light-loss barrel reductions. Immutable masters stay read-only."""
import numpy as np



def early(ctx):
    knot = [e for e in ctx.es if e['name'] in {'系绳', '盘长结上环', '盘长结心'}]
    ctx.take(knot, 'profile-red-knot-card', card_axis=2)
    floor = [e for e in ctx.es if e['name'].startswith('桶底拼板') or e['name'] == '底板暗色衬底']
    if floor:
        template = ctx.normalize(ctx.proxy_box(floor, 'baked-floor-card'))
        # Keep the visible upper surface at its old height; only the hidden thickness is lost.
        top = max(e['to'][1] for e in floor)
        template['from'][1] = template['to'][1] = top
        ctx.take(floor, 'baked-floor-card', template=template, card_axis=1)

    tassel = [e for e in ctx.es if e['name'] in ('穗绳', '玉色束珠', '流苏束帽')
              or e['name'].startswith('朱红丝穗 ')]
    ctx.take(tassel, 'baked-lower-tassel-card', card_axis=2)


def late(ctx):
    normals = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0),
               'west': (-1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0)}
    count = 0
    for element in ctx.output:
        hoop = element['name'].startswith(('baked-上箍 ', 'baked-下箍 '))
        foot = element['name'].startswith('baked-relief-底部足圈 ')
        rim = element['name'].startswith('baked-分段顶木 ')
        wall = element['name'].startswith('榆木桶壁 ')
        if not (hoop or foot or rim or wall):
            continue
        transform = ctx.rotation(element.get('rotation', [0, 0, 0]))
        origin = np.array(element.get('origin', [8., 0., 8.]))
        center = (np.array(element['from']) + element['to']) / 2
        world_center = (center - origin) @ transform.T + origin
        radial = world_center - np.array([8., 0., 8.])
        radial[1] = 0
        radial /= np.linalg.norm(radial)
        # A ring sector's radial outer wall is the visible surface. Keep its exact location,
        # texture and 16-sided silhouette; omit inner/end/top/bottom microscopic walls.
        face = max(element['faces'], key=lambda name: float((transform @ normals[name]) @ radial))
        if rim or wall:
            inside = min(element['faces'], key=lambda name: float((transform @ normals[name]) @ radial))
            keep = {face, inside} | ({'up'} if rim else set())
            element['faces'] = {name: f for name, f in element['faces'].items() if name in keep}
        else:
            element['faces'] = {face: element['faces'][face]}
        if hoop: count += 1
    if count != 32:
        raise AssertionError(f'Expected 32 preserved outer hoop sectors, found {count}')
