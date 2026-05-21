"""
Generates permission setup guide images for Tutti.

Each guide is a vertical infographic: a title, a numbered step (or two),
mock-phone panels showing the target HyperOS / Android system screen,
the relevant row/toggle highlighted in accent color with a side arrow.

Run from project root: python3 scripts/draw_permission_guides.py
Output: android-app/app/src/main/res/drawable-xxhdpi/guide_<key>.png
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
FONT_BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
W = 1080
PAD = 60
PANEL_W = W - 2 * PAD - 70
# Path relative to project root (where this script is intended to be invoked from).
OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "android-app/app/src/main/res/drawable-xxhdpi",
)

# Material 3-ish light palette
BG = (250, 250, 252)
INK = (28, 27, 31)
INK_DIM = (110, 110, 115)
RULE = (220, 220, 225)
PANEL_BG = (255, 255, 255)
PANEL_BORDER = (215, 215, 222)
ACCENT = (255, 112, 67)
TOGGLE_ON = (52, 199, 89)
TOGGLE_OFF = (210, 210, 215)
BOX_RADIUS = 28


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT, size)


def draw_panel(draw, x, y, w, title, items):
    """Mock HyperOS settings panel. Each item is (label, kind):
    kind ∈ {'plain', 'highlight-row',
            'toggle-on', 'toggle-off',
            'highlight-toggle-on', 'highlight-toggle-off'}.
    Returns (panel_height, highlight_row_center_y_or_None)."""
    row_h = 86
    head_h = 110
    h = head_h + row_h * len(items) + 20
    draw.rounded_rectangle([(x, y), (x + w, y + h)], radius=BOX_RADIUS,
                           fill=PANEL_BG, outline=PANEL_BORDER, width=3)
    draw.text((x + 36, y + 32), title, font=font(44, bold=True), fill=INK)
    draw.line([(x + 36, y + head_h - 12), (x + w - 36, y + head_h - 12)],
              fill=RULE, width=2)
    ry = y + head_h
    highlight_y = None
    for label, kind in items:
        if kind.startswith("highlight"):
            draw.rounded_rectangle([(x + 18, ry + 6), (x + w - 18, ry + row_h - 6)],
                                   radius=18, fill=ACCENT)
            fg = (255, 255, 255)
            highlight_y = ry + row_h // 2
        else:
            fg = INK
        draw.text((x + 40, ry + 22), label, font=font(40), fill=fg)
        if "toggle" in kind:
            on = "on" in kind
            tx, ty = x + w - 140, ry + (row_h - 50) // 2
            tw, th = 90, 50
            draw.rounded_rectangle([(tx, ty), (tx + tw, ty + th)], radius=25,
                                   fill=TOGGLE_ON if on else TOGGLE_OFF)
            knob_x = tx + (tw - th) if on else tx
            draw.ellipse([(knob_x, ty), (knob_x + th, ty + th)], fill=(255, 255, 255))
        elif "row" in kind or kind == "plain":
            chev_x = x + w - 60
            chev_color = (255, 255, 255) if "highlight" in kind else INK_DIM
            draw.polygon([(chev_x, ry + row_h // 2 - 14),
                          (chev_x + 16, ry + row_h // 2),
                          (chev_x, ry + row_h // 2 + 14)], fill=chev_color)
        ry += row_h
    return h, highlight_y


def draw_pointer(draw, panel_x, panel_w, center_y):
    if center_y is None:
        return
    tip_x = panel_x + panel_w + 8
    end_x = tip_x + 40
    draw.line([(end_x, center_y), (tip_x, center_y)], fill=ACCENT, width=6)
    draw.polygon([(tip_x, center_y - 12), (tip_x, center_y + 12),
                  (tip_x - 18, center_y)], fill=ACCENT)


def draw_arrow_down(draw, cx, y, length=80):
    draw.line([(cx, y), (cx, y + length - 20)], fill=INK_DIM, width=4)
    draw.polygon([(cx - 16, y + length - 22), (cx + 16, y + length - 22),
                  (cx, y + length)], fill=INK_DIM)


def draw_modal(draw, x, y, w, title, body, primary_label, primary_highlighted=True):
    """A centered system-style confirmation dialog with two buttons."""
    h = 320
    draw.rounded_rectangle([(x, y), (x + w, y + h)], radius=BOX_RADIUS,
                           fill=PANEL_BG, outline=PANEL_BORDER, width=3)
    draw.text((x + 40, y + 36), title, font=font(40, bold=True), fill=INK)
    # body lines
    body_y = y + 100
    for line in body:
        draw.text((x + 40, body_y), line, font=font(32), fill=INK_DIM)
        body_y += 48
    # buttons
    btn_h = 80
    btn_w = (w - 80 - 24) // 2
    btn_y = y + h - btn_h - 30
    cancel_x = x + 40
    primary_x = cancel_x + btn_w + 24
    draw.rounded_rectangle([(cancel_x, btn_y), (cancel_x + btn_w, btn_y + btn_h)],
                           radius=20, fill=(238, 238, 244))
    draw.text((cancel_x + btn_w // 2, btn_y + btn_h // 2),
              "取消", font=font(34, bold=True), fill=INK_DIM, anchor="mm")
    if primary_highlighted:
        draw.rounded_rectangle([(primary_x, btn_y), (primary_x + btn_w, btn_y + btn_h)],
                               radius=20, fill=ACCENT)
        text_fill = (255, 255, 255)
    else:
        draw.rounded_rectangle([(primary_x, btn_y), (primary_x + btn_w, btn_y + btn_h)],
                               radius=20, fill=(238, 238, 244))
        text_fill = INK
    draw.text((primary_x + btn_w // 2, btn_y + btn_h // 2),
              primary_label, font=font(34, bold=True), fill=text_fill, anchor="mm")
    return h, btn_y + btn_h // 2  # button center for pointer


def step_header(draw, y, num, title, subtitle):
    draw.ellipse([(PAD, y), (PAD + 56, y + 56)], fill=ACCENT)
    draw.text((PAD + 28, y + 28), str(num), font=font(36, bold=True),
              fill=(255, 255, 255), anchor="mm")
    draw.text((PAD + 80, y + 8), title, font=font(40, bold=True), fill=INK)
    if subtitle:
        draw.text((PAD + 80, y + 60), subtitle, font=font(28), fill=INK_DIM)
        return 140
    return 80


def render_guide(out_name, header_title, header_sub, blocks):
    """blocks is a list of dicts:
      {"type": "step", "num": int, "title": str, "sub": str}
      {"type": "panel", "title": str, "items": [(label, kind), ...]}
      {"type": "arrow"}
      {"type": "modal", "title": str, "body": [str, ...],
                       "primary": str, "primary_highlighted": bool}
    """
    img = Image.new("RGB", (W, 3200), BG)
    draw = ImageDraw.Draw(img)
    y = PAD
    draw.text((W // 2, y), header_title, font=font(64, bold=True), fill=INK, anchor="ma")
    y += 92
    draw.text((W // 2, y), header_sub, font=font(30), fill=INK_DIM, anchor="ma")
    y += 80

    for b in blocks:
        t = b["type"]
        if t == "step":
            y += step_header(draw, y, b["num"], b["title"], b.get("sub"))
        elif t == "panel":
            h, hl = draw_panel(draw, PAD, y, PANEL_W, b["title"], b["items"])
            draw_pointer(draw, PAD, PANEL_W, hl)
            y += h + 40
        elif t == "arrow":
            draw_arrow_down(draw, W // 2, y)
            y += 100
        elif t == "modal":
            h, center = draw_modal(draw, PAD, y, PANEL_W, b["title"], b["body"],
                                   b["primary"], b.get("primary_highlighted", True))
            # arrow points at the primary (right) button — its x is panel right side
            pointer_x_start = PAD + PANEL_W + 8
            draw.line([(pointer_x_start + 40, center), (pointer_x_start, center)],
                      fill=ACCENT, width=6)
            draw.polygon([(pointer_x_start, center - 12),
                          (pointer_x_start, center + 12),
                          (pointer_x_start - 18, center)], fill=ACCENT)
            y += h + 40
        elif t == "spacer":
            y += b.get("h", 40)
        else:
            raise ValueError(f"unknown block type: {t}")

    draw.text((W // 2, y), "完成后返回 Tutti 即可", font=font(32, bold=True),
              fill=INK, anchor="ma")
    y += 60

    final = img.crop((0, 0, W, y + PAD))
    path = f"{OUT_DIR}/guide_{out_name}.png"
    final.save(path, "PNG", optimize=True)
    print(f"Wrote {path}, size {final.size}")


# ----- guide specs -----

GUIDES = {
    "wakepath": dict(
        title="手动开启「链式启动」",
        sub="HyperOS 不允许 App 自助开启此项，需在系统设置中手动确认一次",
        blocks=[
            dict(type="step", num=1, title="点击下方「前往设置」", sub="Tutti 会跳转到 HyperOS 权限管理页"),
            dict(type="panel", title="Tutti  权限管理", items=[
                ("位置", "plain"),
                ("短信", "plain"),
                ("电话", "plain"),
                ("其他权限", "highlight-row"),
            ]),
            dict(type="arrow"),
            dict(type="step", num=2, title="找到「链式启动」并开启", sub="其余其他权限保持原样即可"),
            dict(type="panel", title="其他权限", items=[
                ("桌面快捷方式", "toggle-off"),
                ("锁屏显示", "toggle-off"),
                ("链式启动", "highlight-toggle-on"),
                ("后台弹出界面", "toggle-off"),
            ]),
        ],
    ),
    "overlay": dict(
        title="开启「悬浮窗」权限",
        sub="允许 Tutti 在其他 App 上方显示浮窗，用于播放控制",
        blocks=[
            dict(type="step", num=1, title="在列表中找到「Tutti」并点击",
                 sub="HyperOS 会先展示所有 App 列表（按字母排序）"),
            dict(type="panel", title="显示在其他应用的上层", items=[
                ("百度网盘", "plain"),
                ("Tutti / 管乐", "highlight-row"),
                ("微信", "plain"),
            ]),
            dict(type="arrow"),
            dict(type="step", num=2, title="打开「允许显示悬浮窗」开关",
                 sub=""),
            dict(type="panel", title="显示在其他应用的上层  ·  Tutti", items=[
                ("允许显示悬浮窗", "highlight-toggle-on"),
            ]),
        ],
    ),
    "notification_listener": dict(
        title="开启「通知监听」权限",
        sub="用于监听其他音乐 App 的播放状态，自动切歌依赖该权限",
        blocks=[
            dict(type="step", num=1, title="在「不允许」分组中找到 Tutti",
                 sub="HyperOS 把 App 分成「已允许」和「不允许」两组"),
            dict(type="panel", title="读取、回复和控制通知", items=[
                ("已允许：弹幕通知", "plain"),
                ("不允许：Tutti / 管乐", "highlight-row"),
                ("不允许：其他 App", "plain"),
            ]),
            dict(type="arrow"),
            dict(type="step", num=2, title="在确认弹窗中点击「允许」",
                 sub="系统会要求确认是否信任此权限"),
            dict(type="modal", title="允许 Tutti 使用通知?",
                 body=["此 App 将可以读取所有通知，", "包括姓名和消息内容等个人信息。"],
                 primary="允许"),
        ],
    ),
    "accessibility": dict(
        title="开启「无障碍服务」",
        sub="用于 QQ 音乐播放页跳转、可拖动小窗、链式启动自动确认（可选）",
        blocks=[
            dict(type="step", num=1, title="滚动到「权限管控」分组",
                 sub="在系统辅助功能页底部"),
            dict(type="panel", title="辅助功能", items=[
                ("快捷功能", "plain"),
                ("关怀辅助", "plain"),
                ("权限管控 ▸ 已下载的应用", "highlight-row"),
            ]),
            dict(type="arrow"),
            dict(type="step", num=2, title="在列表中找到 Tutti 并打开开关",
                 sub="可能位于列表底部"),
            dict(type="panel", title="已下载的应用", items=[
                ("Talkback", "toggle-off"),
                ("小米闻声", "toggle-off"),
                ("Tutti / 管乐", "highlight-toggle-on"),
            ]),
            dict(type="arrow"),
            dict(type="step", num=3, title="在系统警告弹窗中确认",
                 sub="HyperOS 会再次提示是否信任"),
            dict(type="modal", title="使用 Tutti / 管乐?",
                 body=["开启后此 App 将可以查看屏幕内容、", "执行手势操作等。仅授信 App 开启。"],
                 primary="允许"),
        ],
    ),
    "write_settings": dict(
        title="开启「修改系统设置」权限",
        sub="用于网易云横屏播放兼容方案（短暂切换屏幕旋转设置）",
        blocks=[
            dict(type="step", num=1, title="打开「允许修改系统设置」开关",
                 sub="点击下方「前往设置」直接到达"),
            dict(type="panel", title="可修改系统设置", items=[
                ("允许修改系统设置", "highlight-toggle-on"),
            ]),
        ],
    ),
    "usage_stats": dict(
        title="开启「应用使用情况」权限",
        sub="用于浮窗双击智能跳转（识别上一个使用的 App）",
        blocks=[
            dict(type="step", num=1, title="在列表中找到「Tutti」并点击",
                 sub="按字母排序，可能位于列表底部"),
            dict(type="panel", title="应用使用情况数据", items=[
                ("Google Play 服务", "plain"),
                ("Tutti / 管乐", "highlight-row"),
                ("超级小爱", "plain"),
            ]),
            dict(type="arrow"),
            dict(type="step", num=2, title="打开「允许使用情况访问权限」开关", sub=""),
            dict(type="panel", title="Tutti / 管乐", items=[
                ("允许使用情况访问权限", "highlight-toggle-on"),
            ]),
        ],
    ),
}


if __name__ == "__main__":
    for key, spec in GUIDES.items():
        render_guide(key, spec["title"], spec["sub"], spec["blocks"])
