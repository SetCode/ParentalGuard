# 版本号与发布流程

应用内「版本与更新」卡片会读取 `https://api.github.com/repos/SetCode/ParentalGuard/releases/latest`
与本机 `versionName` 比较，发现更高版本时提示并给出安装包下载入口。因此**发布流程的正确性直接决定更新提示是否生效**。

## 一、版本号唯一来源

`version.properties` 是唯一来源，`app/build.gradle` 从这里读取，也允许 `-PversionName` / `-PversionCode` 覆盖（CI 使用）。

| 字段 | 规则 |
|---|---|
| `versionName` | 语义化版本，如 `1.0.1`；用户可见，也是比较依据 |
| `versionCode` | 必须单调递增，否则设备拒绝覆盖安装；推荐按 `主*10000 + 次*100 + 修订` 计算 |

Git tag 必须是 `v<versionName>`（如 `v1.0.1`），否则应用内比较会判定为「版本格式异常」。

## 二、本地发布

```bash
./tools/release.sh 1.0.1                      # 只构建，产物在 dist/
./tools/release.sh 1.0.1 --push               # 提交版本号 + 打 tag 并推送，由 CI 发布
./tools/release.sh 1.0.1 --upload             # 用本地产物直接创建/更新 Release
./tools/release.sh 1.0.1 --notes-file docs/releases/v1.0.1.md --push
```

脚本会依次：写版本号 → 跑单元测试 → `assembleRelease` → `apksigner verify` 验签 →
`aapt2 dump badging` 核对包内版本号 → 输出 `dist/ParentalGuard-v1.0.1.apk` 与 `.sha256`。
任何一步不符直接非零退出，不会产出「版本号对不上」的包。

发布说明优先级：`--notes` > `--notes-file` > `docs/releases/v<版本>.md` > 自动取 `git log`。

## 三、CI 发布（推荐）

`.github/workflows/release.yml`：推送 `v*` tag 或手动触发（输入版本号）时，
在 GitHub 上构建签名 APK、跑单测、核对包内版本号，然后创建 Release，附件包含
`ParentalGuard-v<版本>.apk` 与 `.sha256`。若同 tag 的 Release 已存在，则覆盖附件与说明（可重跑）。

`.github/workflows/ci.yml`：推送到 `master` 或提 PR 时跑单元测试 + Debug 构建，保证合并前可编译。

### 需要在仓库配置的 Secrets

密钥自仓库移除后（见 README 安全说明），CI 必须用 Secrets 提供签名材料：

```bash
# 在项目根目录执行，生成可直接粘贴到 GitHub Secrets 的 base64 内容
base64 -w0 release.jks            # → Secret 名：KEYSTORE_BASE64
base64 -w0 keystore.properties    # → Secret 名：KEYSTORE_PROPERTIES_BASE64
```

未配置时 CI 仍会构建，但产物未签名，只能全新安装、无法覆盖旧版本（工作流会给出 warning）。

## 四、验证发布结果

```bash
# 线上 Release 是否存在、附件是否齐全
gh release view v1.0.1 --json tagName,name,assets,url

# 应用内更新检查所依赖的 API 是否已返回新版本
curl -s https://api.github.com/repos/SetCode/ParentalGuard/releases/latest | grep '"tag_name"'

# 下载附件并与本地 sha256 比对
gh release download v1.0.1 -p 'ParentalGuard-v1.0.1.apk' -D /tmp/pg
sha256sum /tmp/pg/ParentalGuard-v1.0.1.apk dist/ParentalGuard-v1.0.1.apk
```

## 五、应用内更新检查的判定规则

| 情况 | 判定 | 界面表现 |
|---|---|---|
| 远端 tag 解析后高于本机 | 有更新 | 弹窗列出当前/最新版本、发布时间、发布说明，提供「下载安装包」「发布页」 |
| 远端 tag 等于或低于本机 | 已是最新 | 状态行显示绿色「已是最新版本 vX」，手动检查时弹 Toast |
| API 返回 404 | 尚无正式发布 | 黄色提示「还没有正式发布版本」，不算失败 |
| 超时 / 无网络 / 非 2xx | 网络失败 | 红色显示具体原因；自动检查失败不弹窗打扰 |
| tag 或本机版本号无法解析 | 格式异常 | 黄色高亮提示具体是哪一个版本号不合法 |

启动时自动检查受 12 小时间隔限制（`UpdatePrefs`），手动点「检查更新」不受限制。
