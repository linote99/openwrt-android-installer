# OpenWrt 安装器 Android v1.7.0 验证报告

## 本版改动

新增 MU300 UFS `mu300root` 的受限 ext4 journal 修复入口。只有只读预检同时确认目标是 `/dev/block/sda75`、`state=0x0001`、`incompat=0x00000046`、4096-byte block、`error_count=0`、内部 journal、没有 `data=journal` 配置、目标未挂载且安装证据完整时，应用才允许继续。

修复前须输入“修复 mu300root journal”。应用只执行 `e2fsck -p -E journal_only`，之后复读 superblock、运行完整只读 `e2fsck -f -n`，并重新通过原有安装证据与卸载计划检查。异常时停止，不使用 `-y`、格式化、扩容或自动重试；修复通过也不会自动卸载，仍需走原卸载确认。维护工具暂存在 RAM `/dev`，写入范围限定为目标 ext4 的 journal/元数据，不改 GPT、boot、misc、userdata 或其它分区。

缺少本地原记录时，当前启动镜像和 OpenWrt 文件标记仍须证明安装证据；上传的备份 ZIP 不会自动导入。

## APK 身份

| 项目 | 结果 |
|---|---|
| APK | `dist/OpenWrt-Installer-Android-1.7.0.apk` |
| 包名 / 显示名称 | `top.linote.owrtinstaller` / `OpenWrt 安装器` |
| 版本 | `1.7.0`（versionCode `22`），最低 API 26，target SDK 34 |
| 签名 | APK Signature Scheme v2、v3 通过；证书 SHA-256 与现有 v1.6.9 APK 相同：`8b0b57870c08745a54e928d5ac949625397f5a8a3bebd696415da3b73942c5b0` |
| APK SHA-256 | `e7519f47908948df4760c2d9cb327d887df6b25f55aac246834dc39c05eaad78` |

## 测试结果

本地 APK 构建、版本/包名检查（17 项）、UI 静态检查（55 项）、UFS 合成 GPT 测试（16 项）和 Android API 26 兼容性扫描（0 个问题）通过。ext4 recovery 合成测试 29 项全部通过，覆盖 clean postcondition、错误/不支持状态、数据日志与外置 journal 拒绝、设备身份或 GPT 几何变化、挂载目标、异常返回码、中断记录，以及 GPT/boot/misc/userdata 保持字节不变。其它可运行的 ADB、USB、userdata、权限和桌面模拟测试均通过。

没有提供完整 E5 与 MU300 安装包 ZIP，因此依赖固件 ZIP 的安装包审计、MU300 安装流程、卸载计划和卸载生命周期测试未运行；没有用用户上传的安装备份 ZIP 代替固件包。

## 尚未验证

本次只做本地构建与合成/模拟验证，没有连接或写入真实设备，没有在真实设备上修复 ext4 或卸载，也没有格式化、刷写或重启。真实 MU300 上的兼容性和恢复效果仍需在设备上验证；若现场状态不符合严格条件，应用会拒绝修复。用户提供的备份 ZIP 未执行、修改或导入，本次也未发布。
