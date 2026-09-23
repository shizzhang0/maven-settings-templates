# 发布到 JetBrains Marketplace

> 依据：JetBrains 官方文档《Publishing a Plugin》和《JetBrains Marketplace Approval Guidelines》（2026-09 查阅）。

## 一、一次性准备

### 1. 账号与协议

1. 注册 / 登录 JetBrains 账号：<https://account.jetbrains.com>
2. 在 <https://plugins.jetbrains.com> 登录后，按提示接受 **Marketplace Developer Agreement**。

### 2. 生成签名密钥

签名用来证明插件包是你发布的、并且没有被篡改。自签名证书即可，不需要购买。

在 **Git Bash** 里执行（`openssl` 随 Git for Windows 自带）：

```bash
mkdir -p ~/.jetbrains-signing && cd ~/.jetbrains-signing

# 生成加密的 RSA 私钥：会提示你设置私钥密码，自己记住，不要告诉任何人
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# 用私钥生成自签名证书（有效期 10 年）：会提示输入上面设置的密码，其余字段可直接回车
openssl req -key private_encrypted.pem -new -x509 -days 3650 -out chain.crt
```

注意事项：

- 生成的两个文件放在 `C:\Users\timothy\.jetbrains-signing\`，**不在项目里**，不会被提交。
- **备份这两个文件和密码**。以后每个版本都要用同一套密钥签名。
- 私钥文件始终是加密的；构建时通过 `PRIVATE_KEY_PASSWORD` 解密，不会在磁盘上留下明文私钥。

### 3. 生成发布令牌（第一个版本上传之后再做）

登录 Marketplace → 右上角头像 → **My Tokens** → 新建令牌。令牌只显示一次，请保存好。

## 二、每次发版前的检查

1. `gradle.properties` 的 `version` 已改成新版本号。Marketplace 不接受重复的版本号。
2. `CHANGELOG.md` 里有该版本的小节（格式 `## [x.y.z] - YYYY-MM-DD`）。构建时会自动把这一节放进插件的 change-notes，也就是市场上的 "What's New"。
3. 运行校验，确认兼容，并且没有内部 API 和实验性 API：

   ```bash
   ./gradlew buildPlugin verifyPlugin
   ```

## 三、设置环境变量

在 **PowerShell** 里执行。密码和令牌用 `Read-Host` 输入，不会留在命令历史里：

```powershell
$env:CERTIFICATE_CHAIN = Get-Content -Raw "$HOME\.jetbrains-signing\chain.crt"
$env:PRIVATE_KEY = Get-Content -Raw "$HOME\.jetbrains-signing\private_encrypted.pem"
$env:PRIVATE_KEY_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host -AsSecureString "Private key password")).Password
# 只有用 Gradle 发布（第四步的 publishPlugin）时才需要：
$env:ORG_GRADLE_PROJECT_intellijPlatformPublishingToken = [Net.NetworkCredential]::new('', (Read-Host -AsSecureString "Marketplace token")).Password
```

这些变量只在当前 PowerShell 窗口里有效，关掉窗口就没了。

## 四、签名并发布

### 签名，并校验签名

在同一个 PowerShell 窗口里：

```powershell
./gradlew signPlugin verifyPluginSignature
```

生成的 `build/distributions/maven-settings-templates-<version>-signed.zip` 就是要上传的包。

### 第一个版本：网页手动上传

官方要求第一个版本必须手动上传。

1. <https://plugins.jetbrains.com> → 头像 → **Upload plugin**（或 "Add new plugin"）。
2. 上传上一步生成的 `-signed.zip`。
3. 表单填写：
   - **License**：`https://github.com/shizzhang0/maven-settings-templates/blob/main/LICENSE`（Apache 2.0）
   - **Source code**：`https://github.com/shizzhang0/maven-settings-templates`
   - **Issue tracker**：`https://github.com/shizzhang0/maven-settings-templates/issues`
   - **Category / Tags**：例如 Build、Maven
   - **Channel**：Stable
   - **EEA 声明**：个人开发者一般选 "Non-trader"
4. 可以上传截图，官方建议 1280×800。
5. 提交后等待 JetBrains 人工审核，通常需要几个工作日。

### 之后的版本：用 Gradle 发布

```powershell
./gradlew publishPlugin
```

`publishPlugin` 会先自动执行 `signPlugin`。

## 五、同步发布到 GitHub

每个版本也在 GitHub 上建一个 Release，并附上 zip，方便不用 Marketplace 的人下载：

```bash
gh release create v<version> build/distributions/maven-settings-templates-<version>.zip --target main --title "v<version>"
```
