# brand-v2 · 微翻标识与界面规格

本目录是**唯一来源**：标志矢量、图标产物、界面规格文档都在这里。

## 标志

`st/orig-w.svg` 是原始矢量（用户提供，233×179）。其余四色版本几何完全一致，只换填充色：

| 文件 | 色值 | 用途 |
|---|---|---|
| `st/w-ink.svg` | `#075E54` | 品牌深绿（默认） |
| `st/w-green.svg` | `#25D366` | 品牌亮绿 |
| `st/w-original.svg` | `#3D7E1A` | 原图绿 |
| `st/w-black.svg` | `#000000` | 单色 |
| `st/w-white.svg` | `#FFFFFF` | 深底反白 |

`out/icons/` 是各尺寸 PNG（16/32/36/48/72/96/128/144/192/512），已分发到
`extension/src/icons/` 与 `android/app/src/main/res/mipmap-*`。

## 重新生成

```bash
# 改色：只改 fill，几何不动
sed 's/fill="#075E54"/fill="#128C7E"/' st/w-ink.svg > st/w-alt.svg

# 出图标（改占空比就改 inset）
python3 ~/.astravia/skills/logo-design/scripts/render_png.py st/icon-16.svg --out-dir out/icons --size 16
```

`st/icon-16.svg` / `icon-32.svg` / `icon-big.svg` 是同一路径的三档占空比
（0.80 / 0.74 / 0.62），小尺寸笔画相对更粗，避免 16px 糊在一起。

## 界面

规格与落地清单见 `../docs/12-品牌与UI重构方案-2026-10-31.md`。
弹窗与设置页的静态预览：`out/popup-preview.png`、`out/popup-preview-dark.png`、`out/options-preview.png`。
