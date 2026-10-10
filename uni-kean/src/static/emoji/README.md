内置表情素材说明（README）
=========================

素材来源与许可见本目录 LICENSE.txt（box-im，MIT License, Copyright (c) 2022 blue）。
映射表与渲染实现见 src/utils/emoji.ts。

一一对应规则（改任何一处之前必须读这一节）
------------------------------------------
    EMOJI_NAME_LIST[i]  ←→  static/emoji/<i>.png
    （数组下标 = 文件名，0 基）

因此 `src/utils/emoji.ts` 里的 `EMOJI_NAME_LIST`：

  · 顺序绝对不能改（改了 = 全部表情错位）；
  · 项数必须与本目录的 png 数量、且必须与文件名范围 [0, n-1] 严格一致；
  · 只允许追加在**末尾**，并且必须同时补上对应编号的 png。

当前状态
--------
  · 本目录图片：0.png ~ 72.png，共 73 个；
  · EMOJI_NAME_LIST：73 项（0 基下标 0..72）；
  · 两者一一对应，无缺失、无多余。

与 box-im 原始数据的差异（重要）
--------------------------------
box 的 `emoTextList` 原文是 **74 项**，最后一项是 `月亮`（下标 73，对应 `73.png`），
但 box 的素材目录里**没有 73.png**，所以 `月亮` 在 box 里也是一条取不到图标的死条目
（其 `textToUrl` 对它返回空串）。

本项目按「数组与素材必须一一对应」处理：**只收 0..72 这 73 项，最后一项是 `西瓜`**，
序号 0..72 的名称与顺序与 box **逐字一致、一个不错位**。

将来若要支持 `月亮`：先把 `73.png` 放进本目录，再在 `EMOJI_NAME_LIST` 末尾追加
`"月亮"`（顺序必须是这 74 项的第 74 位），两件事必须同一次提交完成。

素材目录来源（本仓库内的原始副本）
----------------------------------
  .box-emoji-src/emoji-pack/emoji/0.png ~ 72.png
  .box-emoji-src/emoji-pack/emotion.web.ts        ← 映射表来源
  .box-emoji-src/emoji-pack/LICENSE.box-im        ← 许可原文
