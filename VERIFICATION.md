# OpenWrt 安装器 Android v1.7.6 验证记录

## 结论

v1.7.6 的唯一运行时代码变化是机型安全识别：新增 Changxing60 的 `ZTE 7552N / P720S20` 规则，并把 Yuanhang41S 与 Changxing60 的 package profile 强绑定到各自固件声明型号。机型不符、板号不符或 profile 与型号自相矛盾时 fail closed。安装、更新、启动、ADB、卸载和数据保护实现均未改动。

三份输入 ZIP 均由安装器检查器成功解析；Changxing60 新规则、两类固件的篡改拒绝、MU300 内置安装模拟、E5 双阶段安装模拟、ADB、启动与卸载合成测试均通过。本次没有连接、读取、写入或修改真实设备；固件 ZIP 内的脚本和二进制只作为数据解析、哈希校验或不透明资产，未执行。

## 固件身份与安装支持

- **Yuanhang41S**：profile `yuanhang41s`，型号 `ZTE 7546N`，主板 `P720S11`；保持 v1.7.5 原识别规则。输入 ZIP SHA-256：`fae87f2c185065f0190479dc493c8dd65d48daafc437b1da4ccbaa70612e0c76`。
- **Changxing60**：profile `changxing60`，型号 `ZTE 7552N`，主板 `P720S20`。APK 只接受这组型号/profile/板号，不接受把 P720S11 用到 Changxing60 或把其他型号改贴此 profile。输入 ZIP SHA-256：`b199e87f33e8c62aaf3be0c27f33d96255ea3ae925e6ede5a4e4fab93221510e`。
- **MU300 最新包**：`format=2` package manifest；profile `mu300`；OpenWrt 25.12.5；本次包为 7.2.8 内核线。固定 SHA-256 锁定的 desktop/storage 工具与此 ZIP 匹配，因此原有受限 MU300 内置存储路径可用；不匹配的版本仍拒绝，不因版本名而放宽。输入 ZIP SHA-256：`a359f8c3295e6e4c21d065cae06482ca7ab75796c0d139cc12ad959cf4ec3070`。

| 链路 | 当前结论 | 边界 |
|---|---|---|
| TF 卡安装 | 支持 | 对应 package 清单与目标身份检查通过后，沿用原有 TF 路径；本次未刷真实卡/设备。 |
| 独立内置分区安装 | 支持 | 只限受支持 package 与通过型号、板号、分区、容量、启动链检查的目标。MU300 最新包通过 180 项模拟；E5 系列由现有合成测试验证。 |
| 重装已有内置分区 / 两阶段续装 | 支持 | 沿用 `REPART` 与 `RESUME`；必须匹配当前安装证据及本地续装记录。 |
| userdata 镜像 | 实验性、受限支持 | 加密、FBE、dm 映射或不满足文件系统/容量条件时拒绝；可能影响 Android 数据，不能据本次静态测试推断实机可启动。 |
| 启动与 A/B 槽切换 | 支持既有流程 | 先做启动槽/BCB 等检查与备份，再写另一槽并回读校验；合成测试通过，未做实机启动验证。 |
| OpenWrt 固件在线 OTA | **不支持** | APK 没有固件在线更新方法；请使用电脑端安装器或固件本身支持的更新流程。APK 自身只能由用户下载新版后手动安装。 |
| 卸载并归还空间 | 支持既有安全流程 | 需 USB/LAN 连接目标、身份/分区/启动证据核验；布局不适合保留数据时会额外确认。E5/MU300 合成卸载与恢复测试通过，未操作实机。 |

## 相对 v1.7.5 的逐项差异

1. `PackageInspector` 新增 Changxing60 的精确 `ZTE 7552N / P720S20` 规则；对 Yuanhang41S 和 Changxing60 的 package profile 增加严格 profile-to-model 绑定。其余型号规则没有放宽。
2. `AboutActivity` 支持机型列表新增 Changxing60 型号与板号；没有加入“离线测试”专门说明，也没有更改其他 UI 流程。
3. `RuleTest` 增加 Changxing60 正反向匹配断言；`negative-package-test.py` 和 `run-all.sh` 为 Yuanhang41S/Changxing60 添加各自的重算清单哈希篡改拒绝覆盖。
4. 版本递增为 `1.7.6` / versionCode `28`；README 与 MU300 UFS 说明同步，且修正旧版 README 对 OTA/卸载能力的混淆：固件 OTA 不支持，但受安全门控的卸载路径本来就支持。
5. TF、内置分区、镜像、重装、续装、启动、ADB、卸载、备份/恢复与数据保护实现无差异。相对于 v1.7.5，无安装器设备端流程重写。

## 回归结果

完整 `test/run-all.sh` 在提供测试依赖后全通过：

- 固件解析 3/3；Yuanhang41S 与 Changxing60 各 9/9 篡改拒绝；MU300 manifest 篡改测试全部通过。
- `Mu300FlowTest` 180 项、`E5PartTest` 46 项；`UninstallTest` 66 项、`Mu300Ext4RecoveryTest` 71 项、`UninstallFlowTest` 32 项。
- ADB TCP 17 项、USB ADB 19 项、ADB stall 10 项、USB backup 13 项；身份探测 46 项、认证等待 6 项、UFI 工具 29 项。
- 分区规划 49 项、安全导入 29 项、只读身份不匹配 6 项、UFS GPT 16 项、UI 静态检查 66 项、版本/包名检查 19 项；Android API 兼容扫描 0 个问题。
- 合成 mock：helper 26 项、userdata 106 项、MU300 全通过、权限 52 项；均为仓库安装器代码与合成设备/虚假 package fixture，不执行用户 ZIP 内代码。

构建目标为 Android API 34。回归脚本的测试入口硬编码查找 API 35，而环境只安装了 API 34；本次仅在临时测试 SDK 路径将该查找映射到 API 34 供编译，API 35 本身未单独验证。初次回归因本机缺少 BusyBox 与 Python `cryptography` 而未通过；补齐宿主测试依赖后完整回归通过。这两项依赖均未取自固件 ZIP。

## APK、签名与源码 ZIP

APK：`dist/OpenWrt-Installer-Android-1.7.6.apk`；package `top.linote.owrtinstaller`；versionName `1.7.6`；versionCode `28`；minSdk `26`；APK SHA-256：`dd1a4c7b6a672efb6091e151c4d2170cc23bf4771118010155180b43f2e8f8ff`。APK 签名验证通过；证书 SHA-256 为 `84895da2db82cae13497c93d92697841df13f57bb3924add0bc69ab9e3bf4f31`，与 v1.7.5 使用的新签名一致，但不同于旧正式签名版。

源码 ZIP 仅包含源码、测试和说明，排除 APK、`build/`、`build-test/`、`dist/`、用户固件 ZIP、设备备份、私钥和密码配置。签名材料文件名审计未在源码树或 ZIP 成员中发现私钥/密钥库/密码配置文件。v1.7.6 Release 已公开发布；本仓库 `main` 已同步该版本源码，并更新了本验证记录。
