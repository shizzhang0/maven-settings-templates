# 发布新版本

> 依据：JetBrains 官方文档《Publishing a Plugin》和《JetBrains Marketplace Approval Guidelines》（2026-09 查阅）。
>
> 当前状态：1.0.0 已于 2026-09-24 审核通过并上架，插件页面 <https://plugins.jetbrains.com/plugin/34443-maven-settings-templates>。

发版由 GitHub Actions（`.github/workflows/release.yml`）完成：**在 GitHub 上发布一个 Release，插件就会自动发布到
JetBrains Marketplace**，签名后的 ZIP 也会附到这个 Release 上。两边的版本号、更新说明和安装包都来自同一份代码。

## 一、一次性准备

### 1. 签名密钥（已完成）

签名用来证明插件包是你发布的、并且没有被篡改。用的是自签名证书，不需要购买。

密钥放在 `C:\Users\timothy\.jetbrains-signing\`，**不在项目里**：

- `private_encrypted.pem`：加密的 RSA 私钥
- `chain.crt`：自签名证书，有效期到 2036-09-20

**备份这两个文件和密码**。以后每个版本都要用同一套密钥签名。

需要重新生成时（例如在新电脑上从头开始），在 **Git Bash** 里执行（`openssl` 随 Git for Windows 自带）：

```bash
mkdir -p ~/.jetbrains-signing && cd ~/.jetbrains-signing

# 生成加密的 RSA 私钥：会提示你设置私钥密码，自己记住，不要告诉任何人
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# 用私钥生成自签名证书（有效期 10 年）：会提示输入上面设置的密码，其余字段可直接回车
openssl req -key private_encrypted.pem -new -x509 -days 3650 -out chain.crt
```

### 2. 发布令牌

登录 Marketplace → 右上角头像 → **My Tokens** → 新建令牌。令牌只显示一次，生成后马上存进下一步的 Secret。

### 3. GitHub 仓库 Secrets

Actions 从仓库的加密 Secrets 读取密钥。Secret 写进去之后谁都看不到内容，只有工作流运行时能用。

在 **PowerShell** 里进入项目目录，逐条执行。证书和私钥直接从文件交给 `gh`：

```powershell
Get-Content -Raw "$HOME\.jetbrains-signing\chain.crt" | gh secret set CERTIFICATE_CHAIN
Get-Content -Raw "$HOME\.jetbrains-signing\private_encrypted.pem" | gh secret set PRIVATE_KEY
```

密码和令牌让 `gh` 自己提示输入。输入内容不会显示在屏幕上，也不会留在命令历史里。执行后粘贴，再按回车：

```powershell
gh secret set PRIVATE_KEY_PASSWORD
gh secret set PUBLISH_TOKEN
```

不要用管道把密码传给 `gh`：PowerShell 会在末尾加一个换行，存进去的密码就不对了。

执行 `gh secret list` 应该能看到这 4 个名字。也可以在网页上设置：仓库 **Settings → Secrets and variables → Actions**。

### 4. 试运行（可选）

GitHub 上打开 **Actions → Release → Run workflow**。手动运行是试运行：只打包、校验、签名，
**不发布**，签名后的 ZIP 会作为这次运行的附件（Artifacts）保存。用它可以确认 Secrets 都配对了。

## 二、每次发版

1. 在分支上修改，走 PR 合并到 main：
   - `gradle.properties` 的 `version` 改成新版本号，例如 `1.1.0`。Marketplace 不接受重复的版本号。
   - `CHANGELOG.md` 里加上这个版本的小节，格式 `## [1.1.0] - YYYY-MM-DD`。构建时会把这一节放进插件的
     change-notes，也就是市场上的 "What's New"。
2. GitHub 上打开 **Releases → Draft a new release**：
   - **Tag**：`v1.1.0`，从 main 新建。必须是 `v` 加上 `gradle.properties` 里的版本号，否则工作流会报错停下。
   - **Title**：`v1.1.0`
   - **说明**可以留空，工作流会自动填入 CHANGELOG 里这一版的内容。
3. 点 **Publish release**。工作流会依次：
   1. 检查 Secrets、版本号和 CHANGELOG
   2. 打包并运行 Plugin Verifier
   3. 签名并校验签名
   4. 发布到 JetBrains Marketplace
   5. 把 `maven-settings-templates-<version>-signed.zip` 附到这个 Release 上
4. 在 **Actions** 页面看运行结果。任何一步失败都会停下，失败在发布之前的话 Marketplace 什么都不会收到。
   修好后在 Actions 里对这次运行点 **Re-run jobs**。
5. 新版本在 Marketplace 上可能还要经过审核，状态在插件页面的 **Versions** 里看。

## 三、备用：在本地手动发布

GitHub Actions 不能用的时候，可以在本地用同样的步骤发布。

在 **PowerShell** 里设置环境变量。密码和令牌用 `Read-Host` 输入，不会留在命令历史里；
这些变量只在当前窗口有效，关掉窗口就没了：

```powershell
$env:CERTIFICATE_CHAIN = Get-Content -Raw "$HOME\.jetbrains-signing\chain.crt"
$env:PRIVATE_KEY = Get-Content -Raw "$HOME\.jetbrains-signing\private_encrypted.pem"
$env:PRIVATE_KEY_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host -AsSecureString "Private key password")).Password
$env:ORG_GRADLE_PROJECT_intellijPlatformPublishingToken = [Net.NetworkCredential]::new('', (Read-Host -AsSecureString "Marketplace token")).Password
```

然后在同一个窗口里：

```powershell
./gradlew buildPlugin verifyPlugin signPlugin verifyPluginSignature
./gradlew publishPlugin
gh release create v<version> build/distributions/maven-settings-templates-<version>-signed.zip --target main --title "v<version>"
```

注意：用这种方式建 GitHub Release 也会触发工作流，而这个版本已经发布过了，所以那次运行会在发布这一步失败，不用管它。
