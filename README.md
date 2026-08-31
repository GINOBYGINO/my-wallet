# 我的錢包（MyWallet）

一款**離線、遊戲化**的 Android 個人生活與財務管理 App。  
把收入分配、借貸、任務、課表、專注計時與小遊戲獎勵整合在同一套虛擬經濟裡。

> 本 App **不連網**、不蒐集個人資料；所有資料儲存在本機 SQLite（Room）。

## 功能概覽

| 模組 | 說明 |
|------|------|
| 錢包與經濟 | 多錢包分帳、月收入分配、借貸利率、中央透支 |
| 任務 | 分類、到期日、重複規則、完成獎勵與懲罰 |
| 娛樂 | 拉霸、二十一點、比大小、殺撲克（用任務券兌換） |
| 專注 | 計時獎勵現金與娛樂券 |
| 通行證 | 60 格季節獎勵軌道 |
| 課表／行程 | 本地課表、個人行程與通知 |
| 指令控制台 | 匯出狀態報告，搭配 LLM 批次匯入任務／課表 |

## 技術棧

- Kotlin 2.0 · Jetpack Compose · Room · Coroutines
- minSdk 26 · targetSdk 36 · 目前版本 **1.9.0**

---

## 別人怎麼安裝

### 方式一：下載 APK（最簡單）

若作者在 [Releases](https://github.com/GINOBYGINO/my-wallet/releases) 提供 APK：

1. 用手機下載 `app-debug.apk`（或 release 版）
2. 在 Android 設定中允許「安裝未知來源 App」
3. 點擊 APK 安裝
4. 首次開啟會建立本機資料庫與預設錢包

> 安裝後的資料只存在手機裡，換手機或解除安裝會遺失，請善用 App 內「匯出狀態報告」備份。

### 方式二：從原始碼自行編譯

**需求**

- [Android Studio](https://developer.android.com/studio)（建議最新穩定版）
- JDK **17** 或以上
- Android SDK **36**

**步驟**

```bash
git clone https://github.com/GINOBYGINO/my-wallet.git
cd my-wallet
```

1. 用 Android Studio 開啟專案，等待 Gradle Sync 完成
2. 接上 Android 手機並開啟「USB 偵錯」，或啟動模擬器
3. 點選 **Run ▶** 安裝到裝置

或在終端機編譯 Debug APK：

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

APK 位置：`app/build/outputs/apk/debug/app-debug.apk`

**常見問題**

| 問題 | 解法 |
|------|------|
| 找不到 SDK | 確認 Android Studio 已安裝 SDK 36；`local.properties` 會自動產生 `sdk.dir` |
| JDK 版本錯誤 | 使用 JDK 17+，在 Android Studio → Settings → Build Tools 指定 |
| 專案路徑含中文 | 已設定 `android.overridePathCheck=true`，一般可正常編譯 |

---

## 別人怎麼修改

本專案採用 [MIT License](LICENSE)，你可以自由 fork、修改、再發布，只需保留版權聲明。

### 建議流程

```bash
# 1. Fork 到自己的 GitHub
# 2. 克隆 fork 後的倉庫
git clone https://github.com/<你的帳號>/my-wallet.git
cd my-wallet

# 3. 建立功能分支
git checkout -b my-custom-rules

# 4. 修改程式碼後編譯測試
gradlew.bat :app:assembleDebug

# 5. 推送並發 PR（若你想貢獻回原專案）
git push -u origin my-custom-rules
```

### 常用自訂入口

以下檔案是 fork 後最常改的地方：

| 想改什麼 | 檔案 | 說明 |
|----------|------|------|
| **預設錢包名稱與數量** | `app/src/main/java/com/gino/mywallet/data/db/AppDatabase.kt` → `ensureSeed()` | 首次安裝寫入的錢包、分配比例、稅率 |
| **錢包代碼（id）** | `app/src/main/java/com/gino/mywallet/data/entity/Entities.kt` → `WalletCodes` | 如 `living`、`invest`；改 id 需同步改利率、分配、LLM 提示詞 |
| **預設課表** | `app/src/main/java/com/gino/mywallet/data/db/CourseSeed.kt` | 可改成自己的課程，或改成 `emptyList()` 不留範例 |
| **各戶頭月利率** | `AppDatabase.kt` → `defaultWalletRates()` | 單位為 bp（500 = 5.00%/月） |
| **抽獎／遊戲獎金** | `domain/game/GamePrizes.kt` | 各遊戲 EV、二十一點獎金等 |
| **拉霸機率與獎項** | `domain/EconomyEngine.kt` → `companion object` 內 `SLOT_*` 常數 | 符號權重、現金獎額、三連機率 |
| **通行證 60 格獎池** | `domain/EconomyEngine.kt` → `buildPassTiers()` | 每格獎勵內容 |
| **專注模式獎勵** | `domain/FocusRules.kt` | 每 4 分鐘現金、每 30 分鐘發券規則 |
| **LLM 指令提示詞** | `domain/command/LlmCommandPrompt.kt` | 給 Cursor 等 AI 用的系統提示與錢包 id 對照 |
| **App 顯示名稱** | `app/src/main/res/values/strings.xml` | `app_name` |
| **套件名稱（進階）** | `app/build.gradle.kts` 的 `applicationId` + 目錄結構 | 若要上架 Play Store 建議改成自己的 package |

### 在 App 內調整（不需改程式碼）

安裝後，多數設定可在 App 裡直接改：

- **收入分配比例**：錢包頁 → 收入設定
- **各戶頭利率**：市場頁
- **課表／行程**：任務頁新增或編輯
- **任務與獎懲**：任務頁
- **清空重來**：設定頁 → 清空並重建（會恢復程式碼中的種子資料）

### 用 LLM 批次匯入

1. 設定 → 匯出狀態報告
2. 搭配 `LlmCommandPrompt.kt` 的提示詞，請 AI 產出 `mywallet-cmd` JSON
3. 貼到設定 → 指令控制台 → 執行

適合快速建立整學期課表或每週任務，不會動到餘額。

### 修改後如何安裝到自己的手機

```bash
gradlew.bat :app:installDebug   # 直接裝到已連線裝置
# 或
gradlew.bat :app:assembleDebug  # 產生 APK 再手動安裝
```

若改了 `applicationId`，會與原版並存（視為不同 App）。

---

## 專案結構

```
app/src/main/java/com/gino/mywallet/
├── data/          # Room 實體、DAO、資料庫、種子資料
├── domain/        # 經濟引擎、遊戲邏輯、指令解析
├── focus/         # 專注計時前景服務
├── notify/        # 通知排程
└── ui/            # Compose 畫面與 ViewModel
```

核心檔案：

- `domain/EconomyEngine.kt` — 收入分配、借貸、抽獎、通行證
- `ui/AppViewModel.kt` — UI 狀態
- `domain/command/CommandParser.kt` — LLM 指令解析

## 隱私與權限

| 權限 | 用途 |
|------|------|
| `PACKAGE_USAGE_STATS` | 專注模式偵測是否切換 App（需使用者手動授權） |
| `POST_NOTIFICATIONS` | 課表、行程、借貸提醒 |
| `SCHEDULE_EXACT_ALARM` | 精準排程通知 |
| `FOREGROUND_SERVICE` | 專注計時 |

無 `INTERNET` 權限，無雲端同步。

## 授權

[MIT License](LICENSE) — Copyright (c) 2026 GINOBYGINO

你可以自由使用、修改、商用，只需保留版權與授權聲明。

## 貢獻

歡迎 Issue 與 Pull Request。調整經濟參數時，請在 PR 中簡述設計理由。

## 免責聲明

本專案為個人生活管理工具，**非真實金融或投資建議**。娛樂小遊戲僅為自律激勵，不涉及真實賭博或金流。
