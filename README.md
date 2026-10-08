# 快捷仓室 (Quick Hatch)

代码全是glm5.3f和ds4.1f写的，很多有的bug反复修了好几遍，还有的死活修不好让他抄eaep才修好（🙏赞美伟大的eaep🙏），曾经想实现一些更进一步的功能但是死活写不好就删了，所以删删改改代码可能乱七八糟的

一个 [GregTech CEu Modern](https://github.com/GregTechCEu/GregTech-Modern)（GTCEu Modern）辅助模组，让建多方块结构时摆放仓室快得飞起。

- Minecraft 1.20.1 · Forge 47.x
- 依赖：GTCEu Modern（必需）、Applied Energistics 2（可选）

## 分支说明

| 分支 | 适配 | 构建产物 |
| --- | --- | --- |
| `main` | 最新稳定版 GTCEu Modern（7.5.3） | `quickhatch-<ver>.jar` |
| `gtl` | 最新 GTL 整合包（[GTLCore](https://github.com/GNSW/GTLCore)，使用 GTCEu 1.4.4） | `quickhatch-<ver>-gtl.jar` |

## 功能

g键打开仓室选择界面，窗口里排列着gtm的所有仓室，线缆，管道和应用能源2的物品。
每个物品根据快捷栏，物品栏，身上精妙背包，身上无线终端所绑定的ae网络中物品数量总和实时显示数量，可以像eaep那样中键下单。

界面左侧有3列筛选栏，一列筛选类别，一列筛选电压，一列筛选电流，标题旁边有筛选输入输出的按钮，shift点击筛选栏某个选项时，在原本左键应用此筛选的基础上取消其他手动分类筛选栏位的选项
左下角有开关下次进入这个界面时是否保留上次筛选的按钮

直接左键拉取1组并关闭界面
直接右键拉取1个并关闭界面
shift左键拉取1组
shift右键拉取1个

对着能被替换为仓室的方块以及所有仓室ctrl左键也会打开仓室选择界面（保险起见能放样板的不行我给扔黑名单了），此时直接左键仓室选择界面的一个方块的操作为将原方块破坏并放置选中的方块，被破坏的方块直接返还到身上

界面可以使用打开物品栏的键位退出即e键，也可以使用打开他的键位退出即g键

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。开发环境需要 JDK 17。