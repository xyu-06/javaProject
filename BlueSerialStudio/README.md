# 蓝牙串口实验室 BlueSerialStudio

适用于 Android 8.0 (API 26) 及更高版本的**原生 Android 蓝牙经典 SPP/RFCOMM** 串口助手，面向 STM32、51 单片机、Arduino、支持经典蓝牙 SPP 的 ESP32（并非所有 ESP32 型号都支持）等。

> 本项目是**真实的 Android Java 源码工程**，不是 HTML 模拟器。它尚未在实体 Android 手机与蓝牙模块上验证，需编译和真机测试。

## 功能

- 搜索附近经典蓝牙设备 / 已配对设备，系统配对后 RFCOMM 连接；安全连接失败时回退到兼容性更强但安全性较低的非认证连接。
- 双向收发：UTF-8 文本、发送 HEX、接收显示 HEX、LF/CRLF/无结束符、毫秒时间戳、收发字节统计。
- 数据日志自动滚动、清空、通过系统文件选择器导出 TXT。
- 遥控：双轴摇杆 (-100～100)、按下/抬起即发送的方向键、急停键、8 个可长按修改名称和命令的快捷按钮。
- 3 路可配置滑杆（范围、名称、指令前缀）；拖动节流发送、松开时立即发送终值。
- 收到 `12.3,45.6,78.9\n` 或 `x=12,y=25\n` 自动解析为 1～4 通道曲线。最近 180 帧自动缩放 Y 轴；支持暂停、清空、无硬件演示。
- 权限兼容：Android 12+ 蓝牙附近设备权限；旧版 Android 扫描需要位置权限。
- 不使用网络权限，不上传数据；**不需要第三方运行时依赖**。

## 怎么生成可安装 APK

### Android Studio（最简单）

1. 安装 **Android Studio**，用 `Open` 打开当前文件夹 `BlueSerialStudio`。
2. 在 Android Studio 中安装 Android SDK Platform 35，并允许 Gradle 同步。工程使用 Android Gradle Plugin `8.9.2`、JDK 17、Gradle 8.11.1。
3. 本离线生成的工程**不含 Gradle Wrapper 的二进制 JAR**。如果 Android Studio 提示找不到 Gradle Wrapper，请在 IDE 的 `Settings → Build, Execution, Deployment → Build Tools → Gradle` 将 Gradle Distribution 改为本地 Gradle 8.11.1；也可以在有 Gradle 的终端运行 `gradle wrapper --gradle-version 8.11.1` 后再重新同步。
4. 选择 `Build → Build Bundle(s) / APK(s) → Build APK(s)`，生成路径为 `app/build/outputs/apk/debug/app-debug.apk`。
5. 把 APK 发送到手机安装。第一次点连接时授权“附近的设备”。

### GitHub Actions 无需本地 SDK

仓库包含 `.github/workflows/build-apk.yml`。把整个项目上传到自己的 GitHub 仓库，打开 `Actions → Build debug APK → Run workflow`，待完成后下载构建产生的 `BlueSerialStudio-debug-apk` 工件，其中是 APK。项目工作流会从网络下载依赖，仅需首次仓库配置。

> 该工程在当前交付环境中没有可用 Android SDK / Gradle，因而**未实际完成 APK 构建**；请勿把工程 ZIP 当成 APK 直接安装。

## 与 STM32 通讯

### 推荐接法（以 USART1 为例）

```
SPP 蓝牙串口模块 TXD   --> STM32 PA10 / USART1_RX
SPP 蓝牙串口模块 RXD   <-- STM32 PA9  / USART1_TX
SPP 蓝牙串口模块 GND   --- STM32 GND (必须共地)
SPP 蓝牙串口模块 VCC   --- 按该模块实际要求供电
```

**注意**：查看模块板上的 VCC 与 RXD 电平规格，裸芯片的 IO 未必能承受 5V；不要把 5V 当作任意模块的逻辑安全电平。模块 UART 波特率必须与 STM32 USART1 配置一致，通常可先试 9600（具体以模块设置为准）。蓝牙无线连接本身不要求在手机上选串口波特率。

第一次使用时在手机**系统蓝牙设置**里配对，PIN 码以模块实际配置为准（常见模块可能是 1234 或 0000），然后打开 APP 连接。

### 默认发送格式

所有指令默认以 `\n` 结尾（可在设置页改为 CRLF 或无后缀）。

| 操作 | 串口数据（不含结束符） |
|---|---|
| 摇杆 | `J,x,y`，例 `J,36,-70`，松手 `J,0,0`；按住时约 160ms 发送一次心跳 |
| 前进 / 后退 / 左转 / 右转 | `F` / `B` / `L` / `R` |
| 方向键抬起 / 急停 | `S` |
| LED 开 / LED 关 | `LED_ON` / `LED_OFF` |
| PWM 滑杆 | `PWM,65` |
| 舵机滑杆 | `SERVO,90` |
| 辅助滑杆 | `AUX,42` |
| 蜂鸣器 | `BEEP` |

### STM32F103C8T6 标准库 C 示例

见 `stm32_sample/blue_serial_protocol.c`、`.h`。

在已有标准外设库工程调用：

```c
BlueSerial_Init(9600);  /* 蓝牙模块 UART 波特率 */
while (1) {
    BlueSerial_Poll();
    /* 蓝牙 UART 发送三路曲线数据的演示例子：
       BlueSerial_SendPlot(adc1, adc2, adc3); // 放在你的定时采样逻辑中
    */
}
```

把 `BlueSerial_Tick1ms()` 加进已有的 1ms SysTick 或其他 1ms 定时器中，实现 400ms 无运动命令自动停车。**不要重复创建 SysTick_Handler 或 USART1_IRQHandler**，若你的工程已经有 USART1 中断处理函数，请合并 RX 逻辑。演示中仅 PC13 LED 能开关；电机、舵机与蜂鸣器操作通过弱函数留作你已有的驱动来覆盖。若未实现驱动回调，方向和 PWM 指令不会驱动电机，这是有意的安全默认值。

### 示波器上传格式

STM32 发送一帧以换行结束的 UTF-8 数字，例如：

```
120,125,131\n
115,130,129\n
```

或 `temperature=26.8,voltage=3.3\n`。每行最多绘制前四个数字值；建议 10～50 Hz 上传，过高的频率可能造成手机频繁刷新。

## 已知限制 / 注意事项

- **仅支持蓝牙经典 SPP**。不支持 BLE-only 设备如某些 HM-10、ESP32-C3/S3 的 GATT 连接；这些设备需要另加 BLE 功能。
- 不支持蓝牙串口的额外硬件流控配置；只面向透明串口通信模块。
- 关闭本 APP 会断开蓝牙连接；不提供后台服务或自动重连。
- 本项目为单连接，日志内存保留尾部约 20KB，绘图保留最多 180 帧。
- Motor 控制命令可能因断连而停留在最后状态。接真实电机时必须实现固件端看门狗超时停机，同时安装总电源开关。
- 代码尚未通过真实 Android Gradle 编译及真机测试，可能需要根据 IDE / 蓝牙模块反馈小幅调整。

## v1.1 更新 (2026-10-26)

- 设置页增加摇杆发送模板 (`{x}`, `{y}`) 及前、后、左、右、停止自定义指令，保存后立即生效。
- 摇杆松开回中、方向键松开、急停按钮使用优先发送逻辑，清除尚未发送的排队数据，降低旧运动命令在急停后发出的风险。
- 优先停止仅清除手机应用的待发队列，不能取消已经通过蓝牙模块送达的指令；MCU 端必须有通信超时停车保护及物理断电措施。
- `applicationId` 保持不变；使用新的 debug 签名安装时如果提示签名冲突，先卸载旧版本（会清除该 APP 的本地设置）。

## v1.2 改动与测试（2026-10-26）

- 发送结束符支持无 / LF(`\\n`) / CRLF(`\\r\\n`) / CR(`\\r`) 四种；发送框上方**实时显示当前追加值**，切换设置立即更新，文本每次发送按当前选项追加；HEX 发送不添加结束符。
- 发送窗固定在终端页面底部，接收日志区独立滚动；对 Android 15+ 处理输入法 IME Insets，键盘打开时临时隐藏标题与底部导航，确保输入区域有空间。仍需真机对不同厂商输入法测试。
- 左摇杆 Y 控制前后，右摇杆 X 控制左右；两个控件分别收发事件，并合并为 `J,{x},{y}`；单侧松开清零该轴，双侧松开 `J,0,0`。MCU 须实现通信超时停车。
- 快捷按键和参数滑杆可分别添加、编辑、删除，使用 JSON 保存在本机 SharedPreferences；现版本各最多 64 项，首次使用自动继承 v1.1 旧配置，且支持重置默认快捷按键。
- 自动化校验：`python3 qa/check_source.py`，`javac -d .build app/src/main/java/com/embedded/blueserial/SerialProtocol.java tests/ProtocolSelfTest.java && java -cp .build ProtocolSelfTest`。这不替代 Android 编译及 SPP 真机联调。
