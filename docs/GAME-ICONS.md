# 游戏图标素材

用户端的游戏筛选与商品卡统一使用 `GameIcon.vue`，图标打包在项目内，H5 和微信小程序均不依赖图片外链。仅用于标识对应游戏，游戏名称和商标归原权利人所有。

| 文件 | 官方来源 | 处理 |
| --- | --- | --- |
| `apps/mobile/src/static/games/delta.png` | [三角洲行动官网](https://df.qq.com/) 的[站点图标](https://game.gtimg.cn/images/dfm/cp/a202609102anniversary2d/favicon.ico) | ICO 转 PNG，128 × 128 |
| `apps/mobile/src/static/games/valorant.png` | [VALORANT 官网](https://playvalorant.com/en-us/) 的[站点图标](https://cmsassets.rgpub.io/sanity/images/dsfx7636/news/cbf4460132cdfeb2a97fad5f9dd25ba0bc058f76-128x128.png?accountingTag=VAL) | 保留原 PNG，128 × 128 |
| `apps/mobile/src/static/games/lol.png` | [英雄联盟官方素材页](https://www.leagueoflegends.com/en-us/league-of-legends-downloads/) 的 [LoL Icon Flat Gold](https://cmsassets.rgpub.io/sanity/files/dsfx7636/news/5fc5b5dac58a9ae32728fa0cc8fbd9c834b6dde5.zip) | PNG 等比缩至 128 像素 |

获取日期：2026-10-25。新增游戏时，在组件中补充相同规格的本地图标。
