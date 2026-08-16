# TaskManager（任务管理器）

一个基于 Fabric 的 Minecraft 模组，在游戏内提供类似系统任务管理器的进程管理能力：查看/管理服务端、客户端与实体进程，支持线程级与方法级采样，并可跨机器远程管理专用服务器。

## 功能特性

- **进程管理**：按「游戏本体 / 模组 → 来源 → 类别」树形展示进程（全局进程、玩家、生物、掉落物实体等）
- **线程级操作**：查看进程线程（CPU、内存分配速率、状态、堆栈），支持线程级暂停/恢复/终止与优先级调整
- **进程操作**：暂停/恢复/终止/强制终止/重启，支持多选批量（Ctrl 点选、Shift 区间）
- **真实冻结**：暂停「服务端主循环」可真实冻结服务器 tick（等价 `/tick freeze`），多人专用服务器自动禁用冻结以保护其他玩家
- **远程管理**：客户端通过 TCP + token 认证（挑战-应答 HMAC，token 不明文上网）连接专用服务器，远程查看/操作进程，支持实例持久化保存
- **概览面板**：JVM/系统 CPU、堆内存、GPU、网络流量、磁盘 I/O 等实时指标（远程连接时显示远程数据）
- **辅助功能**：拼音搜索、操作日志、进程表导出（.prc）、调试模式

## 环境要求

- Minecraft 26.2
- Fabric Loader 0.19.x
- Fabric API
- JDK 21+（构建）

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/`，包含三个版本：

| jar | 模组 id | environment | 用途 |
|-----|---------|-------------|------|
| `taskmanager-<version>.jar` | taskmanager | `*` | 无拆分版（客户端+服务端一体，单人游戏/本地使用） |
| `taskmanager-<version>-server.jar` | taskmanager | `server` | 服务端专用（装到专用服务器，客户端无需安装即可加入） |
| `taskmanager-<version>-client.jar` | taskmanager-client | `client` | 客户端 UI（连接远程服务端管理，也可单独查看本地进程） |

## 安装与使用

- **单人/本地**：安装无拆分版（`taskmanager-<version>.jar`），按 `F12` 打开任务管理器 UI
- **专用服务器**：安装 `server` 版，远程管理服务端默认监听 `0.0.0.0:<游戏端口+1>`（默认 25566），游戏内 `/taskmgr token` 查看连接 token
- **远程管理**：客户端安装 `client` 版，F12 打开 UI → 实例按钮 → 添加远程 → 输入 `服务器IP:端口` 与 token
- **面板服**：无需修改启动脚本，可在 `config/taskmanager.json` 中配置 `bindHost` / `port` / `token`

## 远程管理配置

配置优先级：系统属性 `-Dtaskmanager.remote.*` > `config/taskmanager.json` > 默认值。

```json
{
  "bindHost": "0.0.0.0",
  "port": 25566,
  "token": "自定义口令（留空则每次启动随机生成）"
}
```

- `/taskmgr token`：查看当前 token
- `/taskmgr token reset [口令]`：重置 token（永久有效，持久化到配置文件）
- 认证采用挑战-应答（HMAC-SHA256），token 本身不在网络明文传输

## License

本项目基于 [MIT License](LICENSE) 开源。
