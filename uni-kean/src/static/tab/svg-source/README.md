# tabBar 细线条描边图标（本轮新增，源文件目录）

**用途**：存放本轮「照消息页重构设计图」手绘的 4 枚 tabBar 图标 SVG 源文件。
**版权**：8 个 SVG 全部为本次手绘，未引用任何第三方图标库 / 网站 / 网图的路径数据，可自由商用。
**只读参考**：本目录不被 uni-app 打包引用（`pages.json` 引用的是 `static/tab/v2/*.png`）。若不需要保留源文件，可整目录删除，不影响运行。

---

## 1. 统一规范（8 个文件完全一致，仅 `stroke` 颜色不同）

| 项目 | 值 |
| --- | --- |
| 画布 / viewBox | `24 × 24`，`viewBox="0 0 24 24"` |
| 线宽 | `stroke-width="2"`（24 网格 ≈ 1.75~2px 规范区间） |
| 端点 | `stroke-linecap="round"` |
| 连接 | `stroke-linejoin="round"` |
| 填充 | `fill="none"`（纯描边，无填充；四枚一致） |
| 未选中描边色 | `#7A7E83`（= `pages.json` 的 `tabBar.color`，非新增颜色） |
| 选中描边色 | `#4D80F0`（= `pages.json` 的 `tabBar.selectedColor`，非新增颜色） |
| 视觉重心 | 四枚均居中于 x=12，横向占位 3.5→20.5，纵向 2.5→21 |
| 语义 | home=房子（尖顶+屋身+门）· publish=方框内加号 · message=圆角气泡+左下尾巴（内部留空）· mine=圆头+肩弧（无五官） |

> ⚠️ `#7A7E83` / `#4D80F0` 与现有旧素材 PNG 里烘焙的颜色不完全相同
> （旧占位图实测约 灰 `#C9CDD4` / 蓝 `#4D80F0`）。选 `#7A7E83` 是因为它**与 `pages.json` 里
> `tabBar.color` 完全一致**，也符合"从现有配置取色、不引入新颜色"的要求。

---

## 2. 需要生成的 8 个 PNG（源 → 目标）

| # | 源 SVG | 目标 PNG | 尺寸 | 描边色 |
| --- | --- | --- | --- | --- |
| 1 | `svg-source/home.svg` | `v2/home.png` | 72×72 | `#7A7E83` |
| 2 | `svg-source/home-active.svg` | `v2/home-active.png` | 72×72 | `#4D80F0` |
| 3 | `svg-source/publish.svg` | `v2/publish.png` | 72×72 | `#7A7E83` |
| 4 | `svg-source/publish-active.svg` | `v2/publish-active.png` | 72×72 | `#4D80F0` |
| 5 | `svg-source/message.svg` | `v2/message.png` | 72×72 | `#7A7E83` |
| 6 | `svg-source/message-active.svg` | `v2/message-active.png` | 72×72 | `#4D80F0` |
| 7 | `svg-source/mine.svg` | `v2/mine.png` | 72×72 | `#7A7E83` |
| 8 | `svg-source/mine-active.svg` | `v2/mine-active.png` | 72×72 | `#4D80F0` |

绝对路径前缀：`uni-kean/src/static/tab/`
（例：源 `D:\CursorWorkSpace\课安\uni-kean\src\static\tab\svg-source\home.svg`
→ 目标 `D:\CursorWorkSpace\课安\uni-kean\src\static\tab\v2\home.png`）

**尺寸依据**：旧素材实测均为 **81×81 px**（3x 于 27px 槽位；uni-app tabBar 图标常见 81/48/24 等）。
`24 × 3 = 72px`，按 24 网格 3 倍导出、透明背景；如需与旧素材像素级同尺寸，改导出为 81×81 亦可
（矢量等比缩放，视觉一致）。

---

## 3. 导出方式（任选一条；PowerShell，Windows 本机）

### 路线 A：Edge / Chrome headless（无需额外安装，最省事）

原理：把 SVG 内联进一张 24×24、零边距、`overflow:hidden` 的临时 HTML，
再用 `--force-device-scale-factor=3` 截图 → **24 CSS px × 3 = 72×72 物理像素**。
`--default-background-color=00000000` 让截图保留透明底（较新版 Chromium 支持；
若得到白底，属可接受差异——tabBar 背景本身就是 `#FFFFFF`）。

```powershell
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
if (!(Test-Path $edge)) { $edge = "C:\Program Files\Microsoft\Edge\Application\msedge.exe" }
$root = "D:\CursorWorkSpace\课安\uni-kean\src\static\tab"
New-Item -ItemType Directory -Force -Path "$root\v2" | Out-Null
$names = @("home","home-active","publish","publish-active","message","message-active","mine","mine-active")
foreach ($n in $names) {
  $svg  = "$root\svg-source\$n.svg"
  $tmp  = Join-Path $env:TEMP "kean-icon-$n.html"
  $out  = "$root\v2\$n.png"
  $body = Get-Content -Raw -Encoding UTF8 $svg
  "<!doctype html><meta charset=utf-8><style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}svg{display:block;width:24px;height:24px}</style>$body" |
    Set-Content -Path $tmp -Encoding UTF8
  & $edge --headless --disable-gpu --hide-scrollbars --no-sandbox `
          --default-background-color=00000000 --force-device-scale-factor=3 `
          --window-size=24,24 --screenshot="$out" ("file:///" + $tmp.Replace('\','/'))
  Remove-Item $tmp -Force
}
```

> 若 `--screenshot` 出来的不是 72×72，去掉 `--force-device-scale-factor=3`
> 改为 `--window-size=72,72`（把 SVG 的 `width/height` 临时改成 72 再截），或直接用路线 B。

### 路线 B：Inkscape（原生透明 + 精确尺寸，最准确）

```powershell
$inkscape = "C:\Program Files\Inkscape\bin\inkscape.exe"
$root = "D:\CursorWorkSpace\课安\uni-kean\src\static\tab"
New-Item -ItemType Directory -Force -Path "$root\v2" | Out-Null
foreach ($n in @("home","home-active","publish","publish-active","message","message-active","mine","mine-active")) {
  & $inkscape "$root\svg-source\$n.svg" --export-type=png --export-filename="$root\v2\$n.png" --export-width=72 --export-height=72
}
```

### 路线 C：resvg / sharp（若本机 node 可用）

```
npx --yes @resvg/resvg-js-cli --fit-width 72 --fit-height 72 svg-source/home.svg v2/home.png
```

（无网络时不可用；不要求为此新增项目依赖。）

---

## 4. 导出后的自检

```powershell
Get-ChildItem "D:\CursorWorkSpace\课安\uni-kean\src\static\tab\v2\*.png" |
  ForEach-Object { "{0}`t{1} bytes" -f $_.Name, $_.Length }
```

应恰好 8 个文件、每个 > 0 字节。**`pages.json` 已指向这 8 个路径：任缺一个，对应 tab 图标会空白且不报错。**

---

## 5. 若以后要新增第 5 个「任务」tab（本轮**未做**，仅备查）

设计图第 4 项是「任务」。要在现有 4 项之后追加，需（属结构变更，本轮明确不做）：

1. 新建页面 `pages/task/list`（或复用列表页）并在 `pages.json` 的 `pages` 里注册。
2. 在 `tabBar.list` **末尾**追加一项（不要插到中间，避免既有顺序变化）：
   ```json
   {
     "pagePath": "pages/task/list",
     "text": "任务",
     "iconPath": "static/tab/v2/task.png",
     "selectedIconPath": "static/tab/v2/task-active.png"
   }
   ```
3. 「任务」图标的建议画法（与这 4 枚同规范：24 网格 / 描边 2 / round / 无填充）：
   **清单式**——一块圆角板 + 顶部夹子，板内 2~3 条短横线：
   `M9 6.5 L15 6.5`（顶部夹子/环）· `rect x=4.5 y=4.5 w=15 h=16.5 rx=3.5`（板身）
   · `M8.5 10.5 L15.5 10.5` · `M8.5 14 L15.5 14` · `M8.5 17.5 L12.5 17.5`（条目行）
   （也可用「日历」语义：外框 + 顶部两小竖 + 一条横线。二者都必须保持同样线宽与端点，才能与这 4 枚统一。）
