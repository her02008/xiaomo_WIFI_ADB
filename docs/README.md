# 小墨机器人 WiFi ADB 方案

> **关于脱敏**：本文不含可定位到具体实机的标识信息（序列号、型号、SoC、内网 IP 等）。
> 统一用下列占位符表示，使用时替换为你自己的实际值：
>
> | 占位符 | 含义 |
> |---|---|
> | `<设备IP>` | 设备在局域网中的 IP |
> | `<设备型号>` | adb `model` 属性，如 `MS_XXXX` |
> | `<设备序列号>` | adb 设备序列号 |
> | `<SoC型号>` | 芯片型号，如 `rkXXXX` |
> | `<app-uid>` | App 进程被系统分配的 uid |
>
> 其余技术结论（ROM 行为特性、权限模型、踩坑点）与具体设备无关，可直接复用。

设备：某定制 ROM 的教育机器人（Android 5.1.1 / API 22，armeabi-v7a）。

## 一、现状结论（均已在该设备上实测确认）

| 项目 | 实测结果 |
|---|---|
| WiFi IP | `<设备IP>`（wlan0，DHCP 分配） |
| 设备版本 | `ro.build.type=userdebug`，`ro.build.tags=test-keys` |
| SELinux | **Disabled**（无强制策略，property service 几乎不拦 setprop） |
| App 进程组 | 继承 `adb` 组 (gid 1011) → **App 可直接 setprop** |
| Root | `/system/xbin/su` 存在，但为 AOSP 原版 `ALLOW_SHELL` 模式 |
| su 限制 | 硬编码只允许 uid 0 / 2000，普通 App 调用报 `su: uid <uid> not allowed to su` |
| su 命令串联 | **不支持** `;` 串联多命令，会报 `No such file or directory`，必须逐条执行 |
| adbd 启动方式 | `service.adb.tcp.port` 属性 + `ctl.start adbd` 可切换 TCP 模式 |
| **该 ROM 的坑** | `ctl.stop adbd` 后 init **不会**自动重新拉起 adbd，必须显式 `ctl.start adbd` |
| `adb root` | **可用**（userdebug 构建），可用于设备侧应急修复 |

## 二、App 的技术选型（重要）

**结论：App 不使用 su，改用 init 的 `ctl.*` 控制通道。**

原因：AOSP 原版 su 的 `ALLOW_SHELL` 模式硬编码校验调用者 uid，
普通 App（uid <app-uid>）会被拒绝。放宽 su 文件权限（chmod 4755）也没用，
su 内部还有一层 uid 白名单。

而实测发现 App 进程自带 `adb` 组 + SELinux Disabled，
因此 App 可以直接执行：

```bash
setprop service.adb.tcp.port 5555   # 写属性 —— 实测成功
setprop ctl.stop adbd               # 停 adbd —— 实测成功
setprop ctl.start adbd              # 拉起 adbd —— 实测成功
```

另外 `stop adbd` 只影响 adbd 自身，**不会影响 App 进程**
（App 是普通应用，不依赖 adbd 存活），所以 App 内可以安全串联这三步。

## 三、恢复当前连接（本设备当前状态）

设备上 `service.adb.tcp.port` 已设为 `5555`，但执行过 `ctl.stop adbd` 后
adbd 停在 stopped 状态，USB 接口不再枚举（Windows 侧
`Get-PnpDevice -PresentOnly` 查不到设备）。

**恢复办法（任选其一）**：

1. **重启设备**（最简单）：小墨机器人断电重启后，init 会按开机流程拉起 adbd。
2. **物理按键重启**：长按电源键。
3. 重启后若需要手动切 TCP 模式：
   ```bash
   adb shell setprop service.adb.tcp.port 5555
   adb shell setprop ctl.stop adbd
   sleep 2
   adb shell setprop ctl.start adbd
   adb connect <设备IP>:5555
   ```

> 注意：第 3 步中 `ctl.stop adbd` 会让 adbd 立刻退出，
> 所以 `ctl.start adbd` 必须是**独立的一条命令**，不能与 stop 写在同一行。

## 四、设备端 App（WiFi ADB 开关）

已开发完成，位于项目根目录 `app/`，包名 `com.workbuddy.wifiadb`。

功能：
- 一键开启 / 关闭 WiFi ADB
- 显示本机 IP 与 `adb connect` 连接命令，支持一键复制
- 界面走大字、大按钮路线（标题 30sp / 主按钮 26sp / 深色高对比），便于非技术用户在机器人小屏上操作
- 可勾选「设备重启后自动保持 WiFi ADB」（写入 `persist.adb.tcp.port`）

实现要点（针对该定制 ROM 的适配）：
1. **不使用 su** —— 该 ROM 的 su 是 AOSP `ALLOW_SHELL` 模式，硬编码只允许 uid 0/2000，
   普通 App 调用会被拒（`su: uid <app-uid> not allowed to su`），放宽文件权限也没用。
2. 改走 init 的 `ctl.*` 控制通道，App 可直接执行（实测通过）：
   - `setprop service.adb.tcp.port 5555`
   - `setprop ctl.stop adbd`
   - `setprop ctl.start adbd`
3. **逐条**执行命令，不做分号串联
4. 开启流程：写属性 → `ctl.stop adbd` → `ctl.start adbd` → 校验端口真的在监听
   （失败则再补一次 `ctl.start`）
5. 关闭流程：清空即时属性与持久化属性 → `ctl.stop adbd` → `ctl.start adbd` → 校验

### 构建

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 若 `gradle wrapper` 任务因无法访问 services.gradle.org 而失败
> （部分网络环境会拦截），可从本机已缓存的 Gradle 发行版中复制官方 wrapper 三件套：
> ```
> <gradle-8.5发行版>/gradle/wrapper/gradle-wrapper.jar  →  gradle/wrapper/
> <gradle-8.5发行版>/gradlew                            →  .
> <gradle-8.5发行版>/gradlew.bat                        →  .
> ```

### 安装与验证

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.workbuddy.wifiadb/.MainActivity
```

设备上点「开启 WiFi ADB」，然后在电脑上：

```bash
adb connect <设备IP>:5555
adb devices        # 应显示 <设备IP>:5555  device
```

## 五、长期使用建议

把 WiFi ADB 做成开机自启，避免每次手工开启。两种方式：

### 方式 A：勾选 App 里的「设备重启后自动保持 WiFi ADB」（推荐）

App 在开启时同时写入 `persist.adb.tcp.port`，该属性持久化在 `/data`，
重启后 init 拉起 adbd 时读取它，adbd 自动以 TCP 模式监听 5555。

### 方式 B：命令行一次性部署

```bash
./scripts/setup-autostart.sh
```

### 恢复 USB-only 模式

```bash
adb shell setprop service.adb.tcp.port ''
adb shell setprop persist.adb.tcp.port ''
adb shell setprop ctl.stop adbd
adb shell setprop ctl.start adbd
```

## 六、已完成的验证

### ✅ 电脑端（PC → 设备）—— 真机实测通过
```
adb tcpip 5555
→ restarting in TCP mode port: 5555
adb connect <设备IP>:5555
→ connected to <设备IP>:5555
adb devices -l
→ <设备序列号>   device product:<SoC型号> model:MS_R4D device:<SoC型号>
→ <设备IP>:5555 device product:<SoC型号> model:MS_R4D device:<SoC型号>
```
USB 与 WiFi 双通道同时在线；通过 WiFi 通道可正常执行 shell 命令
（`getprop` / `id` / `dumpsys battery` 均正常）。

### ✅ App 端 —— 真机点击实测通过（核心验证）

在真机上点击按钮，logcat 确认 App **在设备本地独立**完成了 adbd 控制：

**点击「关闭 WiFi ADB」**
```
exec[setprop service.adb.tcp.port '']      ← 清空即时属性
exec[setprop persist.adb.tcp.port '']      ← 清空持久化属性
exec[setprop ctl.stop adbd]                ← 停止 adbd（WiFi 通道随之断开）
exec[setprop ctl.start adbd]               ← App 自己把它拉起来 ✓
exec[getprop service.adb.tcp.port] ->       ← 确认已关闭
```
结果：属性清空 `[]`、5555 端口关闭、adbd=running。

**点击「开启 WiFi ADB」**
```
exec[setprop service.adb.tcp.port 5555]
exec[setprop ctl.stop adbd]
exec[setprop ctl.start adbd]
exec[getprop service.adb.tcp.port] -> 5555
```
结果：属性 5555、5555 端口开放、界面按钮变为绿色「开启 WIFI ADB」。

> **关键点**：`ctl.start adbd` 无法经 `adb shell` 投递 —— 投递通道正是被停掉的 adbd。
> 这**不是 App 的缺陷**：App 在设备本地执行，不依赖 adbd 存活，
> 因此可以完成完整闭环。这是 App 方案相对命令行方案的真正优势。

### ✅ App 权限 —— 实测通过
```
run-as com.workbuddy.wifiadb id
→ uid=<app-uid>(u0_aXX) gid=<app-uid>(u0_aXX)
  groups=1003(graphics),1004(input),1007(log),1011(adb),1015(sdcard_rw),
         1028(sdcard_r),3001,3002,3003(inet),3006
```
含 `adb` 组 (gid 1011) → 可直接 setprop。

### ✅ UI —— 真机截图确认
1280x720 真机截图：标题 30sp / 主按钮 26sp / 状态色（开=绿、关=暖黄）/
深色高对比，文字无截断、布局无重叠、状态与按钮文案正确联动。

### ✅ 最终交付状态
- App 已安装：`com.workbuddy.wifiadb`
- `service.adb.tcp.port = 5555`
- `persist.adb.tcp.port = 5555`（设备重启后自动恢复 WiFi ADB）
- USB + WiFi 双通道在线

## 七、踩坑记录（避免重复踩）


### 坑 1：误改 su 权限会把自己锁死
把 su 改成 `chmod 4750`（漏了 setuid 的 `s`）后，su 变成 `-rwxr-x---`，
连 root 都无法执行；而 `/system` 默认只读，shell 用户无法改回。
**解法**：`adb root`（该设备是 userdebug，支持）→ 以 root 身份
`chown root:shell /system/xbin/su` + `chmod 4750` 修复。
正确权限应为 `-rwsr-x--- root shell`。

### 坑 2：su 提权对普通 App 不可用
AOSP 原版 su 在 `ALLOW_SHELL` 模式下硬编码校验调用者 uid，
只放行 uid 0 (root) 和 uid 2000 (shell)。
普通 App（uid <app-uid>）会看到 `su: uid <app-uid> not allowed to su`。
**放宽 su 文件权限也无效**，因为 su 内部还有一层 uid 白名单。

### 坑 3：App 无法 kill root 身份的 adbd
`kill <adbd_pid>` 会报 `Operation not permitted`（Linux 权限保护）。
**正确做法**：用 `setprop ctl.stop adbd` / `setprop ctl.start adbd`，
走 init 的服务控制通道，实测 App 身份可用。

### 坑 4：stop 与 start 不能写在同一条命令
`setprop ctl.stop adbd` 会让 adbd 立刻退出，USB 通道随之断开，
若两条命令写在同一行（用 `;` 或 `&&` 连接），`ctl.start` 将永远送不出去。
必须拆成两次独立的命令执行，中间留 1.5–3 秒。

### 坑 5：App 进程不依赖 adbd 存活
这点很关键：App 是普通应用进程，即使 adbd 被停掉，App 依然在运行，
因此 App 内部可以安全地串联「stop → start」两步。
用 `run-as <pkg>` 可以在 PC 侧忠实模拟这一行为。

### 坑 6：Activity.getColor(int) 是 API 23+，设备是 API 22 → 直接崩溃
首次装到真机即崩：
```
java.lang.NoSuchMethodError: No virtual method getColor(I)I in class MainActivity
```
模拟器是 API 24 所以测不出来。必须改用老路径：
```java
getResources().getColor(resId)
```
**教训：API 兼容性必须在目标 API 版本的设备上验证，模拟器会掩盖问题。**

### 坑 7：设备会息屏，点击前需先唤醒
`input tap` 前先 `input keyevent KEYCODE_WAKEUP`，否则点的是黑屏。

### 坑 8：截图尺寸与点击坐标要换算
设备截图为 1280x720，但图片查看工具会缩放到 1080x600，
按图上坐标点击会偏。点击坐标需按 1280/1080 = 1.185 换算。

### 坑 9：多步 adb 操作要写进单个脚本
`adb shell` 的某些命令会让 adbd 超时重启；adb server 重启又会丢失
WiFi 连接。多步操作必须写在同一个脚本里连续执行，否则中途断连。

## 八、安全提醒

设备 root 权限无授权校验（`su` 直接放行给 shell），SELinux 也是 Disabled 状态，
且 WiFi ADB 开启后同网段任意主机均可连接调试。若设备接入公共 WiFi，
建议用完立即在 App 中关闭 WiFi ADB。

## 九、文件清单

| 文件 | 说明 |
|---|---|
| `app/src/main/java/com/workbuddy/wifiadb/WifiAdbController.java` | 核心逻辑：命令执行、属性读写、IP 获取、端口校验 |
| `app/src/main/java/com/workbuddy/wifiadb/MainActivity.java` | 界面逻辑 |
| `app/src/main/java/com/workbuddy/wifiadb/BootReceiver.java` | 开机自启，确保 adbd 以 TCP 模式拉起 |
| `app/src/main/res/layout/activity_main.xml` | 布局（大字号、大按钮） |
| `app/build/outputs/apk/debug/app-debug.apk` | 可直接安装的 APK（已装到真机） |
| `scripts/common.sh` | 脚本公共配置：adb 探测、设备参数、路径解析 |
| `scripts/verify.sh` | 自检：设备连接、adbd 状态、App 安装、端口监听 |
| `scripts/recover.sh` | 命令行开启/关闭 WiFi ADB（`on` / `usb` / `status`） |
| `scripts/setup-autostart.sh` | 部署开机自启 |
| `docs/screenshot.png` | 真机界面截图 |

## 十、日常使用

### 在电脑上（Windows / macOS / Linux）
```bash
adb connect <设备IP>:5555
adb devices          # 出现 <设备IP>:5555  device 即成功
```
拔掉 USB 数据线也不影响，WiFi 调试照常工作。

### 在小墨机器人上
桌面打开「WiFi ADB」App →
- 绿色按钮 = 开启（点一下即可，电脑端随后用上面的命令连接）
- 红色按钮 = 关闭（用完建议关闭，尤其是连公共 WiFi 时）

「设备重启后自动保持 WiFi ADB」保持勾选后，设备重启会自动恢复无线调试，
无需再插 USB。
