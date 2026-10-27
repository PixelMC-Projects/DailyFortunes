"""Approved mild-loss shrine profile; never edits the Blockbench master.

Raised fruit fittings, tiny front ornament and door hardware are baked onto
their existing supporting surfaces. Roof, brackets, cat and foliage stay 3D.
"""
import numpy as np



def _center(ctx, element):
    lo, hi = ctx.bounds(element)
    return (lo + hi) / 2


def _template(ctx, parents, label):
    return ctx.normalize(ctx.proxy_box(parents, label))


def _components(ctx, elements, gap=.11):
    pending = list(elements)
    result = []
    while pending:
        group = [pending.pop(0)]
        lo, hi = ctx.bounds(group[0])
        changed = True
        while changed:
            changed = False
            for other in pending[:]:
                a, b = ctx.bounds(other)
                distance = np.maximum(0, np.maximum(a - hi, lo - b))
                if np.linalg.norm(distance) <= gap:
                    group.append(other)
                    pending.remove(other)
                    lo, hi = np.minimum(lo, a), np.maximum(hi, b)
                    changed = True
        result.append(group)
    return result


def early(ctx):
    elements = ctx.es

    # Omit the concealed upper roof frame and tiny inner-mouth fittings.
    # The exterior eaves, tiled slopes, bracket sets and main beams remain.
    hidden = [e for e in elements if e['name'] in {
        '口内护角', '护角铆钉', '盘内铜压边', '内柱',
        '梁上短柱', '二层短梁', '脊瓜柱', '脊柱叉手', '脊檩',
        '纵向檩木'}]
    ctx.consumed.update(e['uuid'] for e in hidden)

    # Each corner bracket remains a small three-dimensional assembly. Its tiny
    # stacked blocks and recesses become alpha/color on that local envelope.
    bracket_names = {'斗拱柱头方斗', '斗口承盘', '第一层横栱', '栱端升斗',
                     '第二层纵栱', '纵栱升斗'}
    for sx in (False, True):
        for sz in (False, True):
            members = [e for e in elements if e['name'] in bracket_names
                       and (_center(ctx, e)[0] > 8) == sx
                       and (_center(ctx, e)[2] > 8) == sz]
            ctx.take(members, f'profile-local-bracket-{int(sx)}-{int(sz)}')
    tassel = [e for e in elements if e['name'] in {'挂绳', '红绳盘结', '束珠'}]
    ctx.take(tassel, 'profile-red-tassel-card', card_axis=2)

    # Tiny side-frame relief is represented by the same colors on its support.
    for side in (False, True):
        parents = [e for e in elements if e['name'] == '侧板'
                   and (_center(ctx, e)[0] > 8) == side]
        details = [e for e in elements if e['name'] in {
            '侧板内凹', '侧榫竖框', '侧榫横框', '柱外削边', '板面榫钉'}
            and (_center(ctx, e)[0] > 8) == side]
        if parents:
            ctx.take(parents + details, f'profile-flat-side-panel-{int(side)}',
                     template=_template(ctx, parents, 'side-support'))
        window = [e for e in elements if e['name'] == '格窗暗底'
                  and (_center(ctx, e)[0] > 8) == side]
        frames = [e for e in elements if e['name'] in {'格窗横框', '格窗竖框'}
                  and (_center(ctx, e)[0] > 8) == side]
        if window:
            ctx.take(window + frames, f'profile-flat-window-frame-{int(side)}',
                     template=_template(ctx, window, 'window-support'))
        ring = [e for e in elements if e['name'] in {'拉环背板', '拉环横边', '拉环竖边'}
                and (_center(ctx, e)[0] > 8) == side]
        if ring:
            ctx.take(ring, f'profile-cutout-side-ring-{int(side)}', card_axis=0)
    back_ring = [e for e in elements if e['name'] in {'背门拉环横', '背门拉环竖'}]
    ctx.take(back_ring, 'profile-cutout-back-ring', card_axis=2)

    # Keep the four paired fruit bodies' current low-poly envelope. Their green
    # calyx, pointed bottom and pale raised strip become pixels on that envelope.
    bodies = [e for e in elements if e['name'] == '灯实果体']
    fruit_details = [e for e in elements
                     if e['name'] in ('灯实亮瓣', '灯实果蒂', '果底小尖')]
    assigned = {e['uuid']: [] for e in bodies}
    for detail in fruit_details:
        parent = min(bodies, key=lambda e: np.linalg.norm(_center(ctx, e) - _center(ctx, detail)))
        assigned[parent['uuid']].append(detail)
    for index, group in enumerate(_components(ctx, bodies)):
        label = f'profile-fruit-pair-{index}'
        details = [detail for body in group for detail in assigned[body['uuid']]]
        ctx.take(group + details, label, template=_template(ctx, group, label))

    # These five slats are one coplanar facade. Preserve their seams/UV colors
    # and copper corner marks while removing the tiny corner blocks themselves.
    facade = [e for e in elements if e['name'].startswith('正面木板_')]
    corners = [e for e in elements if e['name'] in ('面板铜角横', '面板铜角竖', '角钉')]
    if facade and corners:
        label = 'profile-front-panel-ornament'
        ctx.take(facade + corners, label, template=_template(ctx, facade, label))

    # Beads are a front-facing relief on the existing tray lip, not its outline.
    tray = [e for e in elements if e['name'] == '托盘前唇']
    beads = [e for e in elements if e['name'] in ('盘沿木珠', '托盘前铜线')]
    if tray and beads:
        ctx.take(tray + beads, 'profile-tray-ornament', template=tray[0])

    # Each side door is a separate supporting surface; never span both sides of
    # the cabinet with one box. The original door openings and frames remain.
    for side in (False, True):
        panels = [e for e in elements if e['name'].startswith('检修门内板_')
                  and (_center(ctx, e)[0] > 8) == side]
        fittings = [e for e in elements if e['name'] in ('铜合页', '合页铆钉', '检修门压边', '检修门竖边')
                    and (_center(ctx, e)[0] > 8) == side]
        if panels and fittings:
            label = 'profile-side-door-' + str(int(side))
            ctx.take(panels + fittings, label, template=_template(ctx, panels, label))

    rear = [e for e in elements if e['name'] == '后门拼板']
    rear_hardware = [e for e in elements
                     if e['name'] in ('后门铜合页', '后合页铆钉', '背门锁扣', '后门横撑', '后门竖边')]
    if rear and rear_hardware:
        label = 'profile-rear-door-hardware'
        ctx.take(rear + rear_hardware, label, template=_template(ctx, rear, label))


def late(ctx):
    # Roof tiles hide these rafter upper faces; the visible underside and ends stay.
    for element in ctx.output:
        if element['name'] == 'baked-rafter-array':
            element['faces'].pop('up', None)
