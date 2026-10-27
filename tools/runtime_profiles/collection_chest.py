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

    # Closed drawer internals and the miniature books are low-visibility costs.
    # Preserve the open right drawer's floor/walls and its simple green lining.
    hidden = [e for e in elements if e['name'] in {
        '左小屉底板', '左小屉侧壁', '左小屉后壁',
        '整幅大屉底板', '整幅大屉侧壁', '整幅大屉后壁',
        '收存签册木夹', '签册纸边', '签册朱绳'}]
    ctx.consumed.update(e['uuid'] for e in hidden)

    tassel = [e for e in elements if e['name'] in {
        '朱绳挂轴', '挂绳', '编绳盘结', '流苏束珠'}]
    ctx.take(tassel, 'profile-red-tassel-card', card_axis=2)

    # Retain four separate wooden feet, with small shoulders and copper shoes
    # baked onto their local envelopes instead of nested cubes.
    feet = [e for e in elements if e['name'] in {'短足', '足底铜套', '足肩', '牙角递进榫'}]
    for sx in (False, True):
        for sz in (False, True):
            members = [e for e in feet if (_center(ctx, e)[0] > 8) == sx
                       and (_center(ctx, e)[2] > 8) == sz]
            ctx.take(members, f'profile-foot-envelope-{int(sx)}-{int(sz)}')

    for side in (False, True):
        hinges = [e for e in elements if e['name'] in {
            '铜合页下扇', '铜合页上扇', '合页铜轴节', '合页铆钉', '合页转轴'}
            and (_center(ctx, e)[0] > 8) == side]
        ctx.take(hinges, f'profile-hinge-card-{int(side)}', card_axis=2)

    # The closed lid is one low stepped slab; project its fine copper corners,
    # hairline trim and board seams onto its existing outer envelope.
    lid_names = {'匣盖包檐', '盖口削边', '盖面暗底', '顶盖木心拼板',
                 '木心嵌铜横线', '木心嵌铜竖线', '盖角回纹嵌铜',
                 '盖铜包角顶', '盖铜包角侧', '盖铜包角沿',
                 '包角顶铆钉', '前盖铜边', '前盖铆钉'}
    lid = [e for e in elements if e['name'] in lid_names]
    outline = [e for e in lid if e['name'] in {'匣盖包檐', '盖口削边'}]
    ctx.take(lid, 'profile-flat-lid-detail', template=_template(ctx, outline, 'lid-support'))

    side_panels = [e for e in elements if e['name'] == '侧面内板']
    side_trim = [e for e in elements if e['name'] in {'侧竖收边', '侧横收边'}]
    _attach_nearest(ctx, side_panels, side_trim, 'profile-flat-side-trim')

    # Each formerly eight-piece metal ring is one thin cutout prism. The atlas
    # retains its open center and octagonal contour; axles stay genuinely 3D.
    for prefix in ('左小屉铜环_', '右小屉铜环_', '整幅大屉铜环_'):
        ring = [e for e in elements if e['name'].startswith(prefix)]
        if len(ring) != 8:
            raise AssertionError(f'Expected eight segments for {prefix}, found {len(ring)}')
        axle = [e for e in elements if e['name'] == prefix.split('铜环_')[0] + '环轴']
        ctx.take(ring + axle, 'profile-front-ring-' + prefix.rstrip('_'), card_axis=2)

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
                             ('收边竖边', '收边横边', '上沿斜光', '八角底座', '面榫钉'))]
        if prefix == '整幅大屉':
            ornaments += [e for e in elements if e['name'] in ('大屉玉嵌角竖', '大屉玉嵌角横')]
        if panels and ornaments:
            label = 'profile-drawer-front-' + prefix
            ctx.take(panels + ornaments, label, template=_template(ctx, panels, label))
