# MovinkPad Macro Driver

Wacom MovinkPad Pro 11のペンサイドボタンへ、任意のキーボードショートカットを割り当てるAndroidアプリです。

CLIP STUDIO PAINTなど、別のアプリを操作している間もペンボタンを監視できます。rootは不要ですが、shell権限で入力デバイスを監視するために[Shizuku](https://shizuku.rikka.app/)が必要です。

## 主な機能

- `BTN_STYLUS`と`BTN_STYLUS2`の押下をバックグラウンドで監視
- 2ボタンの同時押しを3つ目の操作として認識
- 長押し・キーリピート時も、ボタンを離すまで1回だけ実行
- Ctrl、Shift、Alt、Metaと英数字・記号・操作キーを組み合わせて割り当て
- 割り当てを端末へ保存し、監視サービスの再起動なしで反映
- ペン入力デバイスを能力情報から自動検出
- Foreground Serviceによる常駐監視

初期設定は次のとおりです。

| ペン操作 | キー |
| --- | --- |
| ボタン1（`BTN_STYLUS`） | Ctrl+Z |
| ボタン2（`BTN_STYLUS2`） | Ctrl+Y |
| 2ボタン同時押し | C |

## 動作確認環境

- Wacom MovinkPad Pro 11
- Android 14
- Shizuku（ADB起動、UID 2000）
- CLIP STUDIO PAINT

現時点ではMovinkPad Pro 11専用の実験的なアプリです。他の端末やAndroidバージョンでは動作確認していません。

本プロジェクトは非公式であり、WacomおよびCELSYSとは関係ありません。製品名・サービス名は各社の商標または登録商標です。

## 導入

1. 端末へShizukuをインストールします。
2. ShizukuをワイヤレスデバッグまたはADBで起動します。
3. 本アプリをインストールして起動します。
4. Shizukuの利用許可を与えます。
5. 「常駐監視を開始」を押します。
6. 必要に応じて各ボタンのキー割り当てを変更します。

非root環境では、端末を再起動するたびにShizukuを再度起動する必要があります。これはShizuku側の仕様です。

## ビルド

Android Studioでプロジェクトを開くか、JDK 17以降を用意して次を実行します。

```shell
./gradlew assembleDebug
```

Windowsでは次を実行します。

```powershell
.\gradlew.bat assembleDebug
```

生成されるAPK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 仕組み

1. Shizukuのshellプロセスで`getevent -il`を実行します。
2. `BTN_STYLUS`と`BTN_STYLUS2`を公開する入力デバイスを検出します。
3. 対象の`/dev/input/eventX`を読み、Linuxの`input_event`を解析します。
4. ボタン押下時にAndroidの`input keyevent`または`input keycombination`を実行します。

入力デバイス番号は固定していません。番号が変わった場合や読み取りが切れた場合は再検出します。

## 制限事項

- Shizukuが停止すると監視できません。
- AndroidやShizukuの更新により動作しなくなる可能性があります。
- 現在は、対象端末でUserServiceが起動できなかったため、Shizuku 13.xに残る非公開の`newProcess` APIをリフレクションで呼び出しています。このAPIは将来削除される予定です。
- Linuxの64bit `input_event`（24バイト）を前提にしています。
- 同時押しは、2つのDOWNイベントが同じ`SYN_REPORT`に含まれる場合に判定します。
- 記号キーの挙動は、利用するアプリやキーボードレイアウトによって異なる場合があります。
- Google Playでの配布を想定した審査対応は行っていません。

## プライバシー

本アプリはネットワーク権限を要求せず、入力イベントや設定を外部へ送信しません。監視対象は自動検出したペンデバイスの`BTN_STYLUS`と`BTN_STYLUS2`だけです。

## 開発上の背景

通常の`MotionEvent`では、別アプリの使用中にペンボタンを取得できません。AccessibilityServiceではイベントを取得できてもCLIP STUDIO PAINT側へ入力が届かなくなったため、Shizukuのshell権限でLinux入力イベントを読み、キーイベントだけを送る方式を採用しています。

Shizuku APIの`newProcess`は非推奨です。公式にはUserServiceへの移行が案内されていますが、動作確認端末ではUserService起動時にフレームワーク内部で失敗したため、互換経路として現在の方式を使用しています。

## 依存ライブラリ

- [Shizuku API](https://github.com/RikkaApps/Shizuku-API) — MIT License
- AndroidX / Jetpack Compose

## ライセンス

このプロジェクトは[MIT License](LICENSE)で公開します。
