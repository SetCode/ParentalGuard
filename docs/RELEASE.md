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
./tools/release.sh 1.0.1                      # 只构建，产物在仓库根目录
./tools/release.sh 1.0.1 --push               # 提交安装包与版本号 + 打 tag 并推送，由 CI 发布
./tools/release.sh 1.0.1 --upload             # 用本地产物直接创建/更新 Release（需 gh 已登录）
./tools/release.sh 1.0.1 --notes-file docs/releases/v1.0.1.md --push
```

脚本会依次：写版本号 → 跑单元测试 → `assembleRelease` → `apksigner verify` 验签 →
`aapt2 dump badging` 核对包内版本号 → 输出 `ParentalGuard-v1.0.1.apk` 与 `.sha256` 到仓库根目录。
任何一步不符直接非零退出，不会产出「版本号对不上」的包。

发布说明优先级：`--notes` > `--notes-file` > `docs/releases/v<版本>.md` > 自动取 `git log`。

## 三、CI 发布（推荐）

`.github/workflows/release.yml`：推送 `v*` tag 或手动触发（输入版本号）时，CI 只做三件事——
**校验产物、生成发布说明、创建 Release**，附件为 `ParentalGuard-v<版本>.apk` 与 `.sha256`。
若同 tag 的 Release 已存在，则覆盖附件与说明（可重跑）。

校验内容：APK 是否存在、`sha256` 与记录值是否一致、`aapt2` 读出的包内 `versionName` 是否等于 tag。
任一不符直接失败，绝不发出对不上的包。

`.github/workflows/ci.yml`：推送到 `master` 或提 PR 时跑单元测试 + Debug 构建，保证合并前可编译。

### 为什么不需要任何 Secret

签名在**开发者本机**完成（`tools/release.sh` 用 `release.jks` 签名并验签），私钥既不在仓库里，
也不经 CI，因此不需要 `KEYSTORE_BASE64` 之类的 Secret——CI 只用 GitHub 自带的 `GITHUB_TOKEN`
创建 Release。代价是发布包必须在本地构建后提交，这也是仓库里保留历史 APK 的原因。

## 四、验证发布结果

```bash
# 线上 Release 是否存在、附件是否齐全
gh release view v1.0.1 --json tagName,name,assets,url

# 应用内更新检查所依赖的 API 是否已返回新版本
curl -s https://api.github.com/repos/SetCode/ParentalGuard/releases/latest | grep '"tag_name"'

# 下载附件并与本地 sha256 比对
gh release download v1.0.1 -p 'ParentalGuard-v1.0.1.apk' -D /tmp/pg
sha256sum /tmp/pg/ParentalGuard-v1.0.1.apk ParentalGuard-v1.0.1.apk
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
