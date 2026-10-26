"""Approved mild-loss chest bake. The Blockbench master remains read-only.

Keep the drawer volumes, open interior, structural feet and columns intact.
Only their fine surface fittings become pixels on the existing support.
"""
import numpy as np


def _center(ctx, element):
    lo, hi = ctx.bounds(element)
    return (lo + hi) / 2


def _template(ctx, parents, label):
    return ctx.normalize(ctx.proxy_box(parents, label))


def _attach_nearest(ctx, parents, details, prefix):
    assigned = {e['uuid']: [] for e in parents}
    for detail in details:
        parent = min(parents, key=lambda e: np.linalg.norm(_center(ctx, e) - _center(ctx, detail)))
        assigned[parent['uuid']].append(detail)
    for index, parent in enumerate(parents):
        fittings = assigned[parent['uuid']]
        if fittings:
            ctx.take([parent] + fittings, f'{prefix}-{index}', template=parent)


def early(ctx):
    elements = ctx.es

    # Each formerly eight-piece metal ring is one thin cutout prism. The atlas
    # retains its open center and octagonal contour; axles stay genuinely 3D.
    for prefix in ('左小屉铜环_', '右小屉铜环_', '整幅大屉铜环_'):
        ring = [e for e in elements if e['name'].startswith(prefix)]
        if len(ring) != 8:
            raise AssertionError(f'Expected eight segments for {prefix}, found {len(ring)}')
        ctx.take(ring, 'profile-front-ring-' + prefix.rstrip('_'))

    # Two narrow bronze bands on each column keep their actual colors but lose
    # the 0.035-model-unit raised lip. The columns retain their original shape.
    posts = [e for e in elements if e['name'] == '主框角柱']
    bands = [e for e in elements if e['name'] == '柱铜束']
    _attach_nearest(ctx, posts, bands, 'profile-column-bands')

    # The rear pegs sit in front of this panel rather than on the horizontal
    # rails. Project them to the six boards' footprint, retaining board seams.
    rear = [e for e in elements if e['name'] == '背板榆木拼片']
    pegs = [e for e in elements if e['name'] == '背框榫钉']
    if rear and pegs:
        label = 'profile-rear-panel-pegs'
        ctx.take(rear + pegs, label, template=_template(ctx, rear, label))

    # Keep every short foot and its shoulder. Only the three tiny stepped
    # shoulder tenons become a surface impression on that same shoulder.
    shoulders = [e for e in elements if e['name'] == '足肩']
    tenons = [e for e in elements if e['name'] == '牙角递进榫']
    _attach_nearest(ctx, shoulders, tenons, 'profile-foot-tenons')

    # A drawer's three adjacent front slats are one local panel, never a box
    # across its inner space. Fine borders, jade corners and diamond backplates
    # are projected onto it. The drawer's walls, opening and pull axle remain.
    for prefix in ('左小屉', '右小屉', '整幅大屉'):
        panels = [e for e in elements if e['name'] == prefix + '面板']
        ornaments = [e for e in elements if e['name'].startswith(prefix)
                     and any(word in e['name'] for word in
                             ('收边竖边', '收边横边', '上沿斜光', '八角底座'))]
        if prefix == '整幅大屉':
            ornaments += [e for e in elements if e['name'] in ('大屉玉嵌角竖', '大屉玉嵌角横')]
        if panels and ornaments:
            label = 'profile-drawer-front-' + prefix
            ctx.take(panels + ornaments, label, template=_template(ctx, panels, label))
