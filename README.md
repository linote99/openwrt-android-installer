# OpenWrt 安装器（安卓版）v1.4.2

这个 APK 是从各机型「OpenWrt 一键刷入包」移植来的手机版安装器。在手机上选择完整安装包 ZIP，APK 会先校验、再提取安装文件，然后调用安装包自带的设备端脚本完成安装，全程不需要电脑。

- APK：`dist/OpenWrt-Installer-Android-1.4.2.apk`（包名 `top.linote.owrtinstaller`，Android 8.0+，versionCode 7，签名与之前各版相同，可以直接覆盖升级）

## 1.4.2：检查页直接显示日志和失败原因

- **检查页加日志框**：第 4 步「检查与计划」页面下方新增和安装页一样的日志框，检查时实时滚动显示每一步输出，可以“复制”或“导出日志”，不用再切到日志页。
- **失败原因直接显示**：检查没通过时，检查卡片里会用红色提示写明原因和处理办法；内置方式检查失败时还提供“更换安装方式”按钮。
- **MU300 检查逐步输出**：读取到的型号与启动槽、userdata 节点/大小/可用空间/文件系统、`ro.crypto.state` / `ro.crypto.type`、加密判断结果、GPT 空闲表项与最大未分配空间，都会逐行写进日志。
- **userdata 镜像方式与加密**：与原安装程序一致，userdata 已加密时（Android 默认的文件级加密 FBE 就算）不能使用 userdata 镜像方式，因为启动 Linux 时无法解密读取 userdata 里的镜像。这种设备请选 TF 卡安装或独立内置分区安装。
- **版本号统一**：之前头部卡片的版本号单独写死在代码里（`VERSION = "1.4.0"`），1.4.1 升级时只改了构建脚本，没改这里，所以安装包是 1.4.1、页面却显示 v1.4.0（使用说明页读的是安装包信息，没受影响）。现在版本号只写在 `version.properties` 一处：`build.sh` 由它生成 APK 文件名、versionName/versionCode 和 `BuildInfo`，头部卡片、使用说明页、闪退记录都读 `BuildInfo`。构建结束会核对清单与 `BuildInfo` 一致，`test/version-check.sh`（已加入 `run-all.sh`）再核对文件名、清单、编译常量、.sha256、README 和发行说明，并禁止源码里写死版本号。
- **构建更稳**：修正构建脚本在签名校验输出处偶发中途退出的问题；`.sha256` 文件只写文件名，可直接作为发行附件。

## 1.4.1：修复 MU300 内置方式只读检查时闪退

- **问题**：在 1.4.0 中给 MU300 选择任一种内置存储方式（独立内置分区、重装已有内置分区、userdata 镜像）后，点击“只读检查”会立刻闪退。
- **原因**：MU300 分区表模块在给 shell 参数加引号时，用了正则标志 `Pattern.UNICODE_CHARACTER_CLASS`。电脑上的 Java 支持这个标志，但 Android 的正则引擎不支持，遇到就会抛出异常（[AOSP 源码说明](https://android.googlesource.com/platform/libcore/+/refs/heads/master/ojluni/src/main/java/java/util/regex/Pattern.java)）。这个异常发生在该模块第一次加载时，变成了任务执行器拦不住的类初始化错误，于是整个应用退出。只读检查正是第一次用到这个模块的地方。TF 卡方式和其他机型不受影响。
- **修复**：改用与原程序 `shlex.quote` 完全相同的 ASCII 字符集，不再使用该标志。另外把一处只在 Android 12 以上才有的 `Math.floorDiv(long, int)` 改为 Android 8 就有的写法。
- **防止再次闪退**：后台任务里即使出现意外的内部错误，也只会在日志里显示“✗ 内部错误”并结束该步骤，不再让应用退出。万一仍然闪退，下次打开时日志页会显示上次的异常信息，可以直接复制反馈。
- **新增测试**：`test/api-level-scan.py` 逐条检查 APK 编译出的代码：用到的每个系统 API 都必须在 Android 8（API 26）上存在，正则标志也必须是 Android 支持的。这项检查已加入 `run-all.sh`，用 1.4.0 的代码跑会报出这两个问题，1.4.1 为 0。

## 1.4.0：MU300 内置存储安装

MU300 现在除了 TF 卡，还可以装到内置存储，三种方式都按原电脑端安装器（`desktop/installer.py`）移植：

| 方式 | 做什么 | 数据影响 |
|---|---|---|
| 独立内置分区 | 所选大小放得进 eMMC 未分配空间时，直接在那里新建 `mu300root` 分区并写入；放不下时，首次开机由 initramfs 离线缩小 userdata 再新建分区，完成后自动重启一次 | 用未分配空间时不动 Android 数据；缩容时保留数据，仅限未加密的 userdata |
| 重装已有内置分区【高级】 | 清空已有的 `mu300root` 并重新写入 | 不改分区表，不动 Android 数据，但分区里的 OpenWrt 设置会清空 |
| userdata 镜像【实验性】 | 在 `/data/mu300linux` 里创建 OpenWrt 镜像 | 不格式化 userdata；原程序标为实验性，可能损坏 Android 的 F2FS |

- **流程与原程序一致**：读取 GPT 两份副本并校验，找未分配空间（并检查那段空间里没有文件系统签名）；开始写入前把 GPT、misc 和两槽启动链备份到手机的 `Android/data/top.linote.owrtinstaller/files/mu300-backup/`（附 `SHA256SUMS`）；上传安装文件，在目标设备上用安装包自带的 `android-image.sh`、`android-write-free.sh`、`android-write-part.sh` 生成和写入镜像；`boot.img` 由 APK 按原程序 `boot_image.py` 的方式在手机上生成（LZ4 legacy、cpio、AVB 尾部保留），写入另一槽后回读比对，不一致会恢复原内容；最后只写 misc 里的一次性 A/B 启动块并回读确认。
- **rootfs 处理**：和原程序一样，上传前去掉构建机上的基带运行时和非公开固件，基带文件改为安装时从目标设备本身提取。
- **两个 MU300 包（内核 7.2.8 / 6.18.54）都支持**。APK 内置了这两个包里内置安装相关文件的 SHA-256；安装包里这些文件只要与适配的版本不同，内置存储方式就会停用（界面会说明原因），TF 卡方式不受影响。
- **只读检查**会确认机型（F50 / MU300 / U30 Air / V50）、当前槽位、userdata 加密状态与文件系统、eMMC 未分配空间和可选大小；所选大小与检查结果对不上（例如检查时还能用未分配空间，开始时已经不够）会要求重新检查。
- 目标设备上的每个步骤都在后台独立运行，USB 拔线或网络断开不会打断写入。

## 1.3.0：USB 有线连接、关于页、适配更多机型

- **USB 有线连接（OTG）**：「设备」页的「局域网设备」改名为「其他设备」，里面分成「USB 有线（OTG）」和「局域网（无线 ADB）」两部分。用 OTG 线把手机接到目标设备，点「检测 USB 设备」，再点列出的设备即可连接，本机不需要 root。APK 自己实现了 USB 上的 ADB 传输（只认 USB 调试接口 class ff / subclass 42 / protocol 01），之后的推送、检查、安装与无线方式完全一样；安装在目标设备后台运行，中途拔线不影响。
- **关于页**：右上角「ⓘ」打开，内容有使用说明、支持机型、注意事项，作者 Lino，结尾是 Lino 笔记和 GitHub 主页的图标链接。

### 适配的机型

| 安装包 | 机型要求（与安装包脚本一致） | 解锁要求 | 支持的安装方式 |
|---|---|---|---|
| E5 | `ro.product.model` = E5 | orange | TF / 缩容分区 / 镜像 |
| T551M | T551M | orange | TF / 缩容分区 / 镜像 |
| T512N | T512N | 不要求（工程固件显示 green） | TF / 缩容分区 / 镜像 |
| VIPME M8 | VIPME M8 | orange | TF / 缩容分区 / 镜像 |
| angelcare K1 | angelcare K1，且主板 `ro.product.device` = ums9157_2h10 | orange | TF / 缩容分区 / 镜像 |
| 远航41S | ZTE 7546N，且主板 P720S11 | 不要求 | TF / 缩容分区 / 镜像 |
| MU300（内核 7.2.8 和 6.18.54 两个包） | ZTE F50 / MU300 / U30 Air，或 V50 / MU3351（须为 UMS9620 平台） | 不要求 | TF / 独立内置分区 / 重装已有分区 / 镜像（1.4.0 起） |

- 前 6 种和 E5 结构相同，设备端脚本逐字节一致，沿用原有流程；槽位方向由脚本在运行时按 `ro.boot.slot_suffix` 决定。
- APK 内置了上表的机型规则：安装包声明的机型不在表里会拒绝；目标设备与安装包不匹配时，「开始安装」不可用。
- MU300 安装包结构完全不同（`payload/`、`desktop/manifest.json`）。APK 会按 `desktop/manifest.json` 校验全部 451 个文件，只提取 Magisk 安装需要的 24 个文件（约 108 MiB），再按安装包 `payload/customize.sh` 的方式生成同样的部署参数，调用包内 `payload/tf-install-worker.sh`：只读检查时只运行它的 `--preflight`，安装时运行它完整的分阶段流程（预检 → 提取本机固件 → 写入 TF → 写入并回读另一槽 boot → 切换启动槽）。1.3.0 时 MU300 只提供 TF 卡方式（1.4.0 起支持内置存储，见上文），管理地址是 `http://192.168.77.1`，日志在 `/data/local/tmp/mu300-tf-install`。

## 1.2.0：每一步单独一个 tab 页

顶部的标题和 tab 栏固定不动，tab 栏可以左右滑动。共 7 个 tab：**1 设备 · 2 安装包 · 3 安装方式 · 4 检查与计划 · 5 安装 · 6 完成 · 日志**。

- 每页只显示当前这一步的内容。底部固定一栏「← 上一步 / 第 N / 6 步 / 下一步 →」，手机的返回键也是回到上一步。
- 完成的步骤会在 tab 上打 ✓，当前所在的 tab 会高亮。任何 tab 都可以直接点进去；前置步骤没做完时，页面会提示你先去完成哪一步，并给出跳转按钮。
- 自动跳转：连接或检测到可用的目标设备后，跳到「安装包」；安装包校验通过后，跳到「安装方式」；开始安装后，跳到「安装」；安装成功后，跳到「完成」。只有在本页发起的操作完成时才会跳转，切换「本机 / 局域网设备」不会触发跳转。
- 「检查与计划」页不提供「下一步」，必须在这一页勾选确认并输入 `yes` 才能开始安装。
- 有任务在运行时，其他 tab 的顶部会显示一张小状态卡（当前阶段、用时、进度）。
- 「安装」页包含进度和日志；「日志」tab 用整屏显示日志，可以复制或导出。
- 其余功能、安全限制和设备端脚本与 1.1.0 完全相同。

## 1.1.0 新增内容

1. **支持局域网设备（无线 ADB）**：手机本身不用 root，就能通过同一 Wi-Fi 或热点，把 OpenWrt 装到另一台已 root 的设备上，例如在畅行60 上给 E5 刷机。
2. **三种安装方式**：TF 卡、独立内置分区（保留 Android 数据）、userdata 镜像（实验性）。
3. **界面仿照电脑端安装器**：配色、卡片、步骤顺序、确认方式都与电脑端一致（1.2.0 起改为分 tab 页显示）。

## 先看：机型必须匹配

每个安装包只适用于上表中对应的机型。安装脚本会检查**目标设备**的型号和主板，不一致就拒绝；APK 在机型不匹配时也不会让你点「开始」。

- 用畅行60 当「遥控器」通过 USB 或局域网给这些设备刷机：可以，这正是「其他设备」模式的用途。
- 把这些包刷进畅行60 本身：**不可以，也不要强行这么做**（畅行60 不在任何一个包的支持列表里）。

## 选择目标设备

**本机**：在 Magisk / KernelSU / KSUNext 里给「OpenWrt 安装器」授予 root 权限，然后点「重新检测」。

**其他设备 · USB 有线（OTG）**：

1. 用 OTG 转接头或 OTG 线把手机接到目标设备的 USB 口（手机作 USB 主机，会给目标设备供电）。
2. 在目标设备上开启 USB 调试，点「检测 USB 设备」，再点要连接的设备；手机会询问是否允许「OpenWrt 安装器」访问该 USB 设备。
3. 第一次连接时，在目标设备上允许 USB 调试，再在它的 root 管理器里允许 **Shell** 的超级用户请求。
4. 手机本身必须支持 OTG（USB 主机模式），不支持的话请用局域网方式。

**其他设备 · 局域网（无线 ADB）**：

1. 让手机连上目标设备的热点，或者和它连到同一个路由器。
2. 在目标设备上开启经典的网络 ADB（5555 端口）。可以先用 USB 执行 `adb tcpip 5555`；如果目标设备已经 root，也可以在它上面执行 `setprop service.adb.tcp.port 5555`，再重启 adbd。
3. 在 APK 里输入 `IP[:端口]` 后点「连接」，或者点「扫描局域网」。扫描只查 Wi-Fi、热点、以太网和 USB 共享网络，不会扫运营商网络；网关通常就是提供热点的那台设备。
4. 第一次连接时，目标设备会弹出「允许 USB 调试」，并显示本应用的密钥指纹（APK 界面上也会显示）。确认后，再在目标设备的 root 管理器里允许 **Shell** 的超级用户请求。
5. 连接的 ADB 密钥是本应用自己生成的 RSA-2048 密钥，保存在应用私有目录里，和任何其他项目的密钥都无关。
6. 暂不支持 Android 11 以后的「无线调试」配对码方式，只支持经典的 5555 端口。

在局域网模式下，安装文件会先推送到目标设备的 `/data/local/tmp/owrt-apk-stage`，再由目标设备上的 root 移到 `/data/adb/owrt-apk/pkg`、改归 root 所有，并再做一次 SHA-256 校验。安装任务在目标设备后台运行，**Wi-Fi 断开不影响安装**，重新连接后点「继续未完成的安装 / 查看日志」就能接上进度。

## 三种安装方式

| 方式 | 写入什么 | 数据影响 |
|---|---|---|
| TF 卡 | 清空 TF 卡上唯一的目标分区或整卡，写入 OpenWrt | 只清空 TF 卡；不改分区表，内部存储和 Android 数据都不动 |
| 独立内置分区（保留数据） | 把 OpenWrt 镜像、本机固件和设置暂存到 `/data/e5linux`，设置 `boot-os=part`；下次开机时由 initramfs 离线缩小 userdata、在它后面新建 `e5root` 分区并写入 | 保留 Android 数据，但**会修改内部存储的分区表**。缩容前先做文件系统检查，任何一步出错都会在改分区表之前停下。缩容过程中断电可能损坏 userdata，请先备份 |
| userdata 镜像（实验性） | 在 `/data/e5linux/openwrt.ext4` 新建镜像，大小按你的选择扩展 | 不格式化 userdata，只占用相应空间；旧镜像会移到 `previous.*` 保留 |

分区方式还要求 userdata 是 **F2FS**：安装包 initramfs 里的缩容脚本只认 F2FS，其他文件系统会放弃（不改动任何东西）。APK 在只读检查时就读取超级块魔数，不是 F2FS 会直接拒绝，免得重启后才发现没装上。

后两种方式只支持 **userdata 明确未加密**的设备。只要检测到 FBE、元数据加密，或 `/data` 经过 dm 映射，就会拒绝。分区大小或镜像大小的可选范围由只读检查算出，规则与电脑端相同：分区最小为镜像大小 + 256 MiB，最大为 userdata 容量 − 已用空间 − 2048 MiB。

三种方式都会做的事：提取本机的 Wi-Fi、蓝牙、音频固件，写入 OpenWrt；启动链有差异时，先备份两个槽再同步；写入另一个槽 `boot_<另一槽>` 并回读校验；设置下次从 OpenWrt 槽试启动，连续失败两次会自动回到 Android。当前的 Android 槽和 NV 都不会写入。

**不支持（请用电脑端安装器）**：加密 userdata 的「两阶段、清空 Android 数据」内置分区安装、E5 系列的重装已有分区、两阶段续装、在线更新和卸载（MU300 的「重装已有内置分区」从 1.4.0 起支持）。这些操作需要在恢复出厂设置的过程中保存事务状态，或在多次重启之间做编排。

## 使用步骤

1. 选择目标设备（本机，或通过 USB / 局域网连接的其他设备），确认型号、root、槽位等信息都正确。
2. 选择完整安装包 ZIP，不用解压。APK 会逐个校验 ZIP 内全部文件的 SHA-256（E5 系列 431 个，MU300 451 个），然后只提取设备端需要的文件（E5 系列 41 个，约 233 MiB；MU300 24 个，约 108 MiB）。
3. 选择安装方式。
4. 点「只读检查」：只暂存文件、运行安装包自带的 preflight 和 APK 自己的 userdata 检查，**不写入任何分区**。
5. 核对检查结果和「数据影响」。如果是分区或镜像方式，还要选择大小。勾选确认后点「确认并开始」，再输入 `yes`。
6. 完成后，界面会显示热点名称和密码，以及 LuCI 地址（E5 系列 `http://192.168.9.1`，MU300 `http://192.168.77.1`）（用户名和密码都是 root，请尽快修改）。可以选择现在重启目标设备，也可以稍后手动重启。如果用的是分区方式，重启后屏幕会显示离线缩容的进度，完成后会自动再重启一次，进入 OpenWrt。

E5 系列的安装器日志和备份在 `/data/adb/e5-tf-install/`，启动链备份在 `/data/adb/e5-bootchain/`；MU300 TF 方式的日志在 `/data/local/tmp/mu300-tf-install/`，内置存储方式的备份在手机的 `Android/data/top.linote.owrtinstaller/files/mu300-backup/`。APK 的「清理暂存文件」只删除它自己暂存的内容。

## 安全措施

- ZIP 里的文件只要被改过、缺失、混进了未登记的文件、有路径穿越、有重复条目，或者一个 ZIP 里有多个包，都会拒绝安装。BusyBox、`boot.img`、`openwrt.ext4.gz` 的大小和哈希还要与包内元数据一致。
- 暂存到目标设备后会再校验一次。安装文件的子清单里，每一行都必须原样出现在完整清单中，而完整清单的指纹要和 APK 读到的一致。暂存目录如果是符号链接，也会拒绝。
- 必须先对当前这个包、当前这种安装方式通过只读检查，才能安装。同一时间只能运行一个安装任务；安装失败后，同一次开机内会锁住，不能重试。
- `SHA256SUMS` / `desktop/manifest.json` 只能保证文件完整、没被改过，不能证明安装包的来源。请只使用可信来源的安装包。

## 构建

```bash
./build.sh   # 需要 JDK 17+，以及 Android SDK（platforms;android-34、build-tools;34.0.0）
```

不使用 Gradle，构建流程是 aapt2 → javac → d8 → zipalign → apksigner。签名密钥在 `signing/` 目录下，是本项目第一次构建时新生成的，没有用任何其他项目的密钥。这个目录不在源码包里，请自行妥善保管：以后发布更新必须用同一把密钥。

## 测试（都在电脑上运行，不执行安装包里的任何东西）

```bash
J=app/src/main/java/top/linote/owrtinstaller
# 1. 解析与校验核心：只读取和解包随附的真实安装包
javac -d build-test/cls $J/{MiniJson,PackageInspector}.java test/DesktopInspect.java
java -cp build-test/cls DesktopInspect <安装包.zip> build-test/work
python3 test/negative-package-test.py <E5系列安装包.zip> build-test/cls build-test/neg   # 11 种异常包（含机型规则）
python3 test/negative-mu300-test.py <MU300安装包.zip> build-test/cls build-test/neg-mu  # 12 种 MU300 篡改/异常包
# 5. Android 兼容性（先 ./build.sh）：API 不高于 minSdk 26、正则标志 Android 可用
python3 test/api-level-scan.py build/classes ~/android-sdk/platforms/android-34/data/api-versions.xml 26
# 6. 版本号一致性（先 ./build.sh）：文件名、versionName/versionCode、BuildInfo、.sha256、README、发行说明
bash test/version-check.sh
java -cp build-test/cls top.linote.owrtinstaller.RuleTest                         # 18 条机型规则
# 2. 无线 ADB 客户端 + 模拟 adbd（真实验证 RSA 签名、公钥结构、流控、sync push）
javac -d build-test/adb $J/{AdbConnection,AdbKey}.java test/AdbTest.java
python3 test/mock-adbd.py 15555 build-test/adb-root &  java -cp build-test/adb top.linote.owrtinstaller.AdbTest 15555 build-test/adb-work build-test/adb-root
# 2b. USB 有线 ADB：用 android.hardware.usb 测试桩（test/usbstub）模拟 USB bulk 传输，接到同一个模拟 adbd
#     检查分帧：消息头单独 24 字节、每次传输 ≤16 KiB、需要时补零长度包；以及拔线、无响应超时
# 3. root 辅助脚本：用自己写的假安装包模拟
sudo bash test/mock-helper-test.sh     # TF 流程 + 局域网暂存（26 项）
sudo bash test/mock-userdata-test.sh   # 分区 / 镜像方式（56 项）
sudo bash test/mock-mu300-test.sh      # MU300 TF：暂存、预检参数、安装成功/失败、篡改拒绝（12 项）
# 4. MU300 内置存储：模拟设备（带真实 GPT 的稀疏 eMMC、by-name 分区、假 shell），包内脚本只模拟效果、不执行
java -Xmx3g -cp build-test/cls top.linote.owrtinstaller.Mu300FlowTest <MU300安装包.zip> build-test/muflow   # 42 项
# 一次运行全部：
bash test/run-all.sh <放安装包 ZIP 的目录>
```

## 与原安装程序的流程对比

发布前把移植版和原电脑端安装器（以及 MU300 的 `desktop/installer.py`）的安装流程逐项对过一遍，只读源码，没有运行：

- **TF 卡（E5 系列）**：APK 的调用方式与安装包自带的 Magisk 入口 `customize.sh` 一致（busybox applet 检查、standalone 模式、`nsenter -t 1 -m` 进入全局挂载空间、30 分钟超时、相同环境变量、成功后删除临时镜像）。
- **独立内置分区（缩容）和 userdata 镜像**：直接复用安装包里的 `prepare_firmware`、`write_boot` 和 `collect-device-files.sh`，写入的文件、文件名、顺序与原程序「不清空数据」的路径相同。各机型（含远航41S 的音频、蓝牙配置转换）的设备文件处理在包内脚本里完成，APK 不改动。5 个新机型的 initramfs 都带有 `install-part.sh`，并支持 userdata 镜像启动目标。
- **MU300 TF 卡**：部署参数与 `payload/customize.sh` 逐项一致。
- **MU300 内置存储（1.4.0）**：按 `desktop/installer.py`、`boot_image.py`、`binary_io.py` 逐个函数移植——机型与槽位识别、GPT 校验与空闲空间计算、缩容大小、备份内容、上传文件清单、设备端脚本参数、启动镜像生成、misc 启动块写入与回读，都与原程序一致。
- **本机 / USB / 局域网**：三种连接最终都在目标设备上以 root 运行同一个辅助脚本；USB 和局域网先推送到 `/data/local/tmp/owrt-apk-stage`，再由 root 校验后移入 `/data/adb/owrt-apk`，与本机路径相同。root 获取方式与原程序一致（adbd 本身是 root，或 `su` / `/debug_ramdisk/su` / `/sbin/su`）。
- **这次修正的问题**：① 分区方式补上 F2FS 预检（原程序也没有检查这一项，要等到重启后由 initramfs 放弃）；② 连接 USB 设备时，部分提示仍写着「局域网」或「无线 ADB」，已改为同时说明 USB。
- **关于 E5 启动后 sipa-fill-recv / sipa-set-rps / sipa-free 线程长时间阻塞**：OpenWrt 里的基带由 `e5-vendor` 服务在 vendor chroot 中运行 Android 的 `modem_control` 来启动，依赖的文件由安装包自己的 `collect-device-files.sh` 收集。原程序和 APK 调用的是同一个脚本，换内核也一样会出现，所以这不是移植造成的差异，更可能出在固件或内核的基带数据通道上。建议把 `dmesg` 和 `logread | grep -i -E "e5-vendor|modem_control|sipa"` 的输出交给安装包作者排查。

## 验证情况

**已在电脑上验证：**

- 用随附的 E5 包测试：431/431 个文件校验通过，提取 41 个文件，分区和镜像方式需要的文件都在其中。8 种篡改或异常包全部被拒绝。
- 无线 ADB 客户端对模拟 adbd 的 17 项测试全部通过：首次授权、签名验证、多包流控、5 MiB、空文件和奇数大小文件的推送内容一致、文件权限、退出码、断开后重连。这项测试还发现并修复了一个问题：远程命令里如果有 `exit`，原来会拿不到退出码。
- 辅助脚本的模拟测试共 82 项全部通过，包括：
  - TF 流程，以及调用方被杀后重新接上任务；
  - 局域网暂存，以及篡改文件、改动子清单、符号链接这三种攻击都被拒绝；
  - 分区方式：范围计算、加密和 dm 映射的拒绝、已有 `e5root` 或已有待处理请求的拒绝、配置文件内容、步骤顺序；失败时会撤回请求，不设置启动目标，也不 arm 启动槽；
  - 镜像方式：参数、提交、保留旧镜像、清理临时文件。
- APK 构建通过，v2/v3 签名校验通过，已申请网络权限。
- 1.3.0：7 个新上传的安装包（远航41S、T551M、T512N、M8、K1、两个 MU300）和 E5 包全部校验通过；18 条机型规则、11 种 E5 系列异常包、8 种 MU300 异常包、12 项 MU300 模拟测试，以及原有 82 项脚本模拟测试和 17 项 ADB 测试，全部通过。MU300 的部署参数与安装包 `payload/customize.sh` 逐项核对一致，机型规则与 `payload/android-bootchain.sh` 一致。

- 1.3.0 USB 有线连接：用 `android.hardware.usb` 测试桩模拟 USB bulk 传输，对模拟 adbd 的 15 项测试全部通过：只列出带 ADB 接口的设备（含复合设备）、无 ADB 接口与无权限时拒绝、授权、退出码、大输出、推送内容一致、分帧符合 USB ADB 要求（消息头单独发送、每次 ≤16 KiB、需要时补零长度包，0 处错误）、拔线后报错而不是卡住、设备不响应时 10 秒超时。同时重跑了全部旧测试（8 个安装包解析、18 条机型规则、E5 8 种 / K1 11 种 / MU300 8 种异常包、17 项无线 ADB、26 + 56 项脚本模拟、MU300 模拟），全部通过。`bash test/run-all.sh` 可一次跑完。
- 流程对比后的修正：userdata 模拟测试增加「非 F2FS 被拒绝 / F2FS 通过」两项（共 58 项），全部回归测试重跑通过。

- 1.4.0 MU300 内置存储：模拟设备测试 40 项，两个 MU300 包都全部通过，覆盖：未分配空间安装（GPT 新增 `mu300root`、userdata 不变、另一槽 boot 自检通过、Android 槽不变、misc 只切一次、备份及其 `SHA256SUMS` 可校验）；空闲区残留旧文件系统签名时不当作空闲空间；无空闲空间时的离线缩容（只写请求文件、不在线改分区表、请求重启）；加密或加密状态不明时拒绝；机型不符拒绝；已有 `mu300root` 时拒绝新建、重装可用；镜像方式的大小范围与超限拒绝；设备端步骤失败时不碰启动槽和 misc；本机暂存文件被改动时在上传前拒绝；检查结果与开始时不一致时，在备份和写入前中止。MU300 异常包测试增加到 12 种（内置安装相关文件被改动时只停用内置方式、TF 照常可用）。原有全部测试重跑通过。

- 1.4.2：版本一致性检查 11 项全部通过；MU300 模拟设备测试新增“加密 userdata + userdata 镜像”时逐步日志与拒绝原因的检查（共 42 项），两个 MU300 包都通过；Android 兼容性扫描为 0；其余全部测试重跑通过。
- 1.4.1：新增 Android 兼容性扫描（API 级别 ≤ 26、正则标志），v1.4.0 的代码被它查出 `UNICODE_CHARACTER_CLASS` 和 `Math.floorDiv(long, int)` 两处问题，1.4.1 为 0；MU300 模拟设备测试增加 `quote()` 与原程序 `shlex.quote` 一致性的检查（共 41 项），两个 MU300 包都通过；其余全部测试重跑通过。

建议的实测顺序是：先在匹配机型上走一遍「只读检查」，确认没问题后再用 TF 卡方式安装；分区方式请在备份好数据之后再试。
