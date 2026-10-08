客户端线程安全，一个应用创建一个实例即可。客户端，实时会话与录音器都有显式的释放方法，全部可以重复调用，异常路径同样会关闭连接，取消定时器。

## 创建，使用与释放

```tabs
items:
  - { label: Java, ref: 'java/README.md#生命周期', lang: java }
  - { label: 安卓, ref: 'android/README.md#生命周期', lang: kotlin }
  - { label: iOS, ref: 'ios/README.md#生命周期', lang: swift }
  - { label: 网页, ref: 'web/README.md#生命周期示例', lang: js }
  - { label: 小程序, ref: 'miniprogram/README.md#生命周期', lang: js }
```

## 释放规则

| 对象 | 释放 | 释放后 |
|---|---|---|
| 客户端 | `close()` | 未结束的实时会话依次进入 `CANCELLED` 与 `CLOSED`，进行中的请求以 90004 结束，之后的调用以 90004 失败。小程序同时释放 `createRecorder` 创建的录音器 |
| 实时会话 | `cancel()` 或 `close()` | 进入 `CANCELLED` 与 `CLOSED`，不再回调终评与错误，连接关闭回调照常触发 |
| 录音器 | `release()` | 停止录音，释放麦克风，注销监听器 |

Java 客户端实现 `AutoCloseable`，可以用 try-with-resources 管理，SDK 线程都是守护线程，不阻止 JVM 退出。示例里安卓在 `onDestroy` 释放，iOS 在 `deinit` 里释放，网页在 `pagehide` 事件里释放，小程序在页面 `onUnload` 里释放。

## 状态查询

| 对象 | 查询 |
|---|---|
| 客户端 | 网页与小程序 `isClosed()`，Java `getOpenSessionCount()` 返回尚未关闭的会话数 |
| 实时会话 | `getState()` 或 `state`，`isActive()` 在终态之前为 true |
| 录音器 | 网页与小程序的录音器状态为 `IDLE`，`RECORDING`，`PAUSED`，`STOPPED`，`RELEASED` |

## 声通平替层

```tabs
items:
  - { label: 安卓平替, ref: 'android-stcompat/README.md#生命周期示例', lang: java }
  - { label: iOS 平替, ref: 'ios-stcompat/README.md#生命周期示例', lang: objc }
```

平替层沿用声通的引擎单例与回调写法，引擎初始化一次，每道题调用一次开始与结束，页面退出时销毁引擎。
