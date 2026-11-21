# 仕様書: Feather PDF（軽快PDFビューア） (Android)

## 目的
Androidスマホで「めちゃめちゃ軽快に」PDFを閲覧できるアプリ。起動・ページ表示・スクロール・ズームのすべてが速いことを最優先にする。機能の多さより速さと安定性。

## 技術方針（必須）
- 言語: Kotlin。UIは **Android View**（Jetpack Composeは使わない。起動速度とAPKサイズのため）。
- PDF描画: Android標準の `android.graphics.pdf.PdfRenderer`（ネイティブライブラリを同梱しない。APKを小さく保つ）。
- 依存ライブラリは最小限: `androidx.core:core-ktx`, `androidx.recyclerview:recyclerview` 程度。AppCompat/Material/Compose は使わない（テーマは `android:Theme.Material.NoActionBar` 系 などフレームワークテーマを使う）。
- パッケージ名: `com.nyoki.lightpdf` / アプリ名: 「Feather PDF」（旧称: 軽快PDF）
- minSdk 26, targetSdk 35, compileSdk 35
- ビルド: Android Gradle Plugin 8.7.x, Kotlin 2.0.21, Gradle 8.9（Kotlin DSL）。
- release ビルドは R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) を有効にし、サイドロードできるよう debug 署名で署名する。

## 機能要件
1. **PDFを開く**
   - アプリ内の「開く」ボタンから `ACTION_OPEN_DOCUMENT`（mime `application/pdf`）で選択。
   - 他アプリ（ファイルマネージャ、ブラウザのダウンロード、共有）から `ACTION_VIEW` / `ACTION_SEND` で `application/pdf` を受け取って開ける（intent-filter）。
   - `content://` と `file://` 両方に対応。`ParcelFileDescriptor` で開く。
2. **最近開いたファイル**: ホーム画面に最大20件。タップで即再オープン。永続URI権限（`takePersistableUriPermission`）を取得。開けなくなったものは削除できる（長押し）。保存は SharedPreferences（軽量）。
3. **閲覧**
   - 縦スクロールで連続表示（RecyclerView）。ページ間に小さな隙間。
   - ページの縦横比は事前に取得し、描画前でも正しい高さのプレースホルダーを表示（スクロール位置が跳ねない）。
   - **ピンチズーム（1x〜5x）とダブルタップズーム**、ズーム中のパン。ズーム後はその倍率に合わせて高解像度で再描画（ぼやけたままにしない）。
   - 現在ページ / 総ページ数の表示（スクロール中に表示し、止まったら数秒で消える）。
   - 高速スクロール用のドラッグ可能なスクロールつまみ（ページ数が多いPDFで素早く移動）。
   - ページ番号を指定してジャンプ。
   - 最後に見ていたページとズームをファイルごとに記憶し、再オープン時に復元。
   - 夜間モード（ページ色反転、ColorMatrixで描画時に適用）切り替え。
   - 画面回転・マルチウィンドウに対応（状態を失わない）。
4. **エラー処理**: 壊れたPDF・パスワード付きPDF・権限切れは落ちずに日本語のメッセージを表示。

## 性能要件（最重要）
- `PdfRenderer` はスレッドセーフでないため、**専用の単一バックグラウンドスレッド**で全描画を直列化する。UIスレッドで描画しない。
- 表示中ページを優先し、画面外になったページの描画要求はキャンセル/スキップする。前後1〜2ページを先読み。
- ビットマップは `LruCache`（使用可能メモリの1/4〜1/8程度を上限）でキャッシュ。画面幅に合わせた解像度で描画し、無駄に大きいビットマップを作らない。可能なら `Bitmap.Config.RGB_565` ではなく ARGB_8888 を使うが、再利用（inBitmap相当のプール）で GC を減らす。
- ズーム中はまず既存ビットマップを拡大表示し、指を離した後に高解像度へ差し替える。
- コールドスタートが速いこと: Application/Activity の onCreate で重い処理をしない。スプラッシュ用の無駄な待ちを入れない。
- APKサイズは小さく（目安 2MB 以下）。

## デザイン
- プロジェクト直下の `DESIGN.md`（Liquid Titanium）に従う: 背景 `#070b12`、パネル/カード `rgba(18,24,25,0.78)` 相当、プライマリ `#b7c6cf`、アクセント `#8a7cff`、テキスト `#f5fbff`、角丸14dp、8dp基準の余白、ボタンはハイライト→プライマリのグラデーション＋暗い文字。
- ただし閲覧画面はPDFの読みやすさ優先: ページ背景は白（夜間モード時は反転）、ツールバーは半透明で、タップで表示/非表示を切り替えて全画面表示。
- 日本語UI。アイコンはベクタードローアブル（PNGを増やさない）。アダプティブアイコンも用意。

## 成果物
- `settings.gradle.kts`, `build.gradle.kts`, `app/` 一式、`gradle.properties`、`.gitignore`
- `README.md`（ビルド方法・機能・設計上の工夫を日本語で）
- `app/build/outputs/apk/release/app-release.apk` がビルドできること
