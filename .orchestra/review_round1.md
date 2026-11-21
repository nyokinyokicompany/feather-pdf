SCORE: 82
## 内訳
- 性能・軽快さ: 23/30
  - 良い点: 単一専用描画スレッド（`LightPdf-RenderThread`）による `PdfRenderer` 操作の完全直列化、優先度キュー（表示中ベース ＞ ズームタイル ＞ 先読み ＞ メタスキャン）＋同一優先度内LIFO制御、ピンチ/ダブルタップ中のGPU Canvas変換（レイアウト再計算ゼロ）と確定後の可視領域タイル描画、`ColorMatrixColorFilter` による再描画ゼロの夜間モード、APKサイズ約193KB（目標2MB以下を大幅に達成）など、基本設計は非常に優秀。
  - 減点理由:
    1. 画面回転・幅変更時（特に横画面など幅1600px超）に `currentBaseWidthPx`（未クランプ）と `safeW`（最大1600pxにクランプ）が不一致となり、`LruCache` への格納が完全にスキップされ、スクロールのたびに先読みタスクが無限に再描画・破棄される致命的な性能バグがある（-4点）。
    2. `BitmapPool` の上限設定（`maxCacheBytes / 3`）と単一ビットマップ受け入れ上限（`maxBytes / 2`）が小さすぎるため、標準的な1080p〜1440p端末の1xページビットマップ（約6.6MB〜11.7MB）がすべて `size > maxBytes / 2` で弾かれて即 `recycle()` され、プール再利用が実質機能していない（-3点）。
- 機能の網羅性: 23/25
  - 良い点: `SPEC.md` の機能要件1〜4（`ACTION_OPEN_DOCUMENT`、外部からの `ACTION_VIEW` / `ACTION_SEND`、`content://`・`file://` と非シーク可能ストリームの一時ファイルフォールバック、最大20件の履歴と永続URI権限取得・長押し削除、縦連続スクロールと隙間、事前縦横比取得、1x〜5xピンチズーム・ダブルタップズーム・パン・高解像度再描画、自動消去ページ表示、高速スクロールつまみ、ページジャンプ、ページ・ズーム復元、夜間モード、画面回転対応、日本語エラー表示）がすべて実装されている。
  - 減点理由:
    1. ズーム時の水平パン位置（`panX`）が `RecentFilesStore` および `onSaveInstanceState` に保存されておらず、再オープン時やActivity再生成時に常に左端（`panX = 0f`）へリセットされる（-1点）。
    2. ページインジケータ表示（`currentPage`）は画面中央のページを基準にしている一方、状態保存（`captureScrollAnchor`）は `findFirstVisibleItemPosition()`（画面上端にわずかでも残っている前のページ）を保存するため、直前ページの下端が数px見えている状態で閉じると履歴表示や再オープン時のページ番号が1ページずれる（-1点）。
- 正確性・安定性: 18/25
  - 良い点: `RefCountedBitmap` による参照カウント管理で描画中のビットマップ破棄クラッシュを防いでいる点、OOM発生時のキャッシュ解放＋縮小フォールバック、各種例外のキャッチは堅実。
  - 減点理由:
    1. `RecyclerView.setItemViewCacheSize(2)` により画面外にキャッシュされた `mCachedViews`（デタッチ済み・`onViewRecycled` 未呼出）に対して、パン・ズーム・夜間モード・画面幅変更が反映されず、再スクロール時に `onBindViewHolder` が呼ばれないまま古い `panX`・高さ・夜間モード状態で再アタッチされる表示破綻バグ、および `onDestroy` 時のビットマップ参照リークがある（-3点）。
    2. `MainActivity.onActivityResult` でPDFの検証前に `store.recordOpened()` を呼んでいるため、破損PDFやパスワード付きPDFを選択しても「最近開いたファイル」に登録されてしまう（-1点）。
    3. 非シーク可能な `content://` ストリーム経由でパスワード付きPDFを開いた際、`tryCopyToTempFileAndOpen` が `SecurityException` を握り潰して `null` を返すため、「パスワード保護」ではなく「破損ファイル」のエラーメッセージが表示される（-1点）。
    4. `MainActivity` の削除確認ダイアログ（および `ViewerActivity` のページジャンプダイアログ）がライフサイクル連動しておらず、ダイアログ表示中に画面回転等が起きると `WindowLeaked` が発生する（-1点）。
    5. 高速スクロールつまみを離した際（`onScrubStateChanged(false)`）、`scrollToPositionWithOffset` のレイアウト反映を待たずに同期的に `updateCurrentPageAndPrefetch` と `refreshHighResTiles` を呼んでいる（-1点）。
- コード品質: 9/10
  - 良い点: パッケージ構成（`data` / `pdf` / `ui`）が明快で、不要なライブラリに頼らず標準APIのみで簡潔に実装されている。R8・リソース圧縮設定や `README.md` のドキュメントも丁寧。
  - 減点理由: ベース描画幅の上限値 `1600` が `PdfPagesAdapter.kt` と `PdfRenderEngine.kt` に重複定義されており、`onViewportWidthChanged` でのクランプ漏れバグの原因になっている（-1点）。
- UI/デザイン: 9/10
  - 良い点: `DESIGN.md`（Liquid Titanium）のカラートークン、角丸14dp、グラデーションボタン、半透明ツールバー、ベクターアイコン、アダプティブアイコン（monochrome対応含む）、自然な日本語UIが高い完成度で実装されている。
  - 減点理由:
    1. ズーム中の水平パン処理（`handleHorizontalPanEvent`）にタッチスロップ（`scaledTouchSlop`）判定がなく、0.25px以上のわずかな指のブレで縦スクロール中やタップ時にも横揺れが発生する（-0.5点）。
    2. 全画面切替でシステムバーを非表示にする際、`getInsets(WindowInsetsCompat.Type.systemBars())` が即座に `0` になるため、ツールバーの非表示アニメーション中に上部パディングが0になり一瞬上に跳ねる（また `displayCutout()` が考慮されていない）（-0.5点）。

## 重大な問題（必ず直すべき）
1. [app/src/main/java/com/nyoki/lightpdf/pdf/PdfRenderEngine.kt:297-302] **横画面・大画面（幅1600px超）へ回転後に `LruCache` が完全無効化され、先読みタスクが毎フレーム無限に再実行される**
   - **問題**: `onViewportWidthChanged(newWidthPx: Int)` で `currentBaseWidthPx = newWidthPx` をクランプせずに代入しているが、`requestBasePage()`（170行目）および `PdfPagesAdapter.baseRenderWidth()`（225行目）では描画幅を `coerceIn(64, MAX_BASE_WIDTH_PX)`（上限1600px）にクランプしている。一般的なフルHD以上のスマートフォンを横画面に回転すると `newWidthPx` は2200〜2400pxになるため、`requestBasePage()` 完了時の `if (currentBaseWidthPx == 0 || currentBaseWidthPx == safeW)`（199行目）が常に `false`（例: `2280 == 1600`）となる。結果として `baseCache.put()` が一切実行されなくなり、スクロールのたびに `prefetchAround()` がキャッシュミスして毎回4ページの再描画タスクを投入・即破棄し続ける。
   - **修正方法**: `onViewportWidthChanged(newWidthPx: Int)` の冒頭で `val clampedW = newWidthPx.coerceIn(64, MAX_BASE_WIDTH_PX)` にクランプし、`currentBaseWidthPx` には常にクランプ後の幅を設定すること。また、`MAX_BASE_WIDTH_PX` 定数を `PdfRenderEngine` に一元化すること。

2. [app/src/main/java/com/nyoki/lightpdf/pdf/BitmapPool.kt:96] / [app/src/main/java/com/nyoki/lightpdf/pdf/PdfRenderEngine.kt:117-118] **`BitmapPool` のサイズ上限判定により、標準的な解像度のベースページBitmapがプールされず毎回 `recycle()` される**
   - **問題**: `AndroidManifest.xml:9` で `android:largeHeap="false"` が指定されており、一般的な端末のヒープ上限（192MB〜256MB）では `maxCacheBytes = maxMem / 6`（32MB〜42.6MB）、`poolBytes = maxCacheBytes / 3`（約10.6MB〜14.2MB）となる。一方、`BitmapPool.put()` の96行目で `if (size <= 0 || size > maxBytes / 2)`（約5.3MB〜7.1MB超は拒否）としているため、1080p〜1440p端末における1xのA4ページ `ARGB_8888` ビットマップ（1080×1527×4 ≒ 6.6MB、1440×2036×4 ≒ 11.7MB）がすべて閾値を超えて弾かれ、即座に `bitmap.recycle()` されている。
   - **修正方法**: 最大サイズ（1600×2400×4 ≒ 15.4MB）のベースページビットマップを少なくとも2〜3枚プールできるよう、`poolBytes` の下限・割合を引き上げる（例: `maxCacheBytes / 2` かつ最低24MB〜32MB程度）とともに、`BitmapPool.put()` の単一ビットマップ拒否条件を `size > maxBytes`（または単一最大ページサイズ以上を許容）に緩和すること。

3. [app/src/main/java/com/nyoki/lightpdf/ui/PdfPagesAdapter.kt:56-119, 216-223] / [app/src/main/java/com/nyoki/lightpdf/ViewerActivity.kt:211] **`RecyclerView` の `mCachedViews`（オフスクリーンキャッシュ）にあるページの `panX`・ズーム高さ・夜間モードが更新されず、表示ずれおよび終了時のBitmapリークが発生する**
   - **問題**: `ViewerActivity.kt:211` で `pdfRecyclerView.setItemViewCacheSize(2)` を設定している。画面外へ出た直後の最大2ページは `RecyclerView` からデタッチされて `mCachedViews` に入るが、この段階では `onViewRecycled()` は呼ばれない。さらに逆方向へスクロールして画面内に戻った際、`RecyclerView` は `onBindViewHolder()` を呼ばずにそのまま再アタッチする。しかし `PdfPagesAdapter` の `updatePanXOnAttached()`・`updateZoomOnAttached()`・`setNightMode()`・`setViewportWidth()` は `0 until recyclerView.childCount`（現在アタッチ中の子Viewのみ）しか更新していないため、`mCachedViews` にいたページが画面に戻ると「横パン位置 `panX` が他のページとずれる」「夜間モードが反映されない」「ズーム後の高さが古いままになる」不整合が起きる。また、`ViewerActivity.onDestroy()` の `releaseAllAttached()` もアタッチ中の子Viewしか解放しないため、`mCachedViews` 内のViewHolderが保持する `RefCountedBitmap` が解放されない。
   - **修正方法**: `PdfPagesAdapter` で `onViewAttachedToWindow(holder: PageViewHolder)` をオーバーライドし、現在の `zoomScale`・`panX`・`nightMode`・`computePageHeight(pos)`（および幅変更時はベースBitmap再バインド）を必ず同期させること。また、`ViewerActivity.onDestroy()` では `pdfRecyclerView.adapter = null` を呼んで `mCachedViews` を含む全ViewHolderを確実にリサイクル・解放させること。

4. [app/src/main/java/com/nyoki/lightpdf/MainActivity.kt:121-123] **ファイルピッカー選択時にPDFの有効性確認前に履歴（`RecentFilesStore`）へ登録している**
   - **問題**: `onActivityResult` で `ViewerActivity` を開く前に `store.recordOpened(uri.toString(), displayName)` を実行しているため、破損したPDFやパスワード保護されたPDF、空のPDFを選択した場合でも「最近開いたファイル」一覧に追加されてしまう（正常に開けた場合は `ViewerActivity.onPdfOpened` の358行目でも `store.recordOpened` が呼ばれている）。
   - **修正方法**: `MainActivity.onActivityResult` 内の `store.recordOpened(...)` を削除し、`ViewerActivity.onPdfOpened()` でPDFのオープンに成功した時点でのみ履歴に記録すること。

5. [app/src/main/java/com/nyoki/lightpdf/pdf/PdfRenderEngine.kt:473-484, 616-645] **非シーク可能なストリーム経由でパスワード付きPDFを開くと「破損ファイル」と誤判定される**
   - **問題**: `PdfRenderer(pfd)` が非シーク可能パイプ等で `IOException` を投げた際、`tryCopyToTempFileAndOpen(uri)` にフォールバックするが、コピー後の一時ファイルに対する `PdfRenderer(pfd)`（634行目）がパスワード保護により `SecurityException` を投げても `catch (e: Exception)` で捕捉されて `null` が返り、呼び出し元（478行目）が一律で `PdfError.CORRUPTED` を返している。
   - **修正方法**: `tryCopyToTempFileAndOpen` 内で `SecurityException` を区別して呼び出し元へ伝播させる（または結果型で `PASSWORD_PROTECTED` を返す）ように修正すること。

## 改善提案
- **[app/src/main/java/com/nyoki/lightpdf/ViewerActivity.kt:368, 559-587, 594-605] 水平パン位置（`panX`）の保存・復元とスクロールアンカーページの一貫性**:
  - `ReadingState` および `savedInstanceState` に水平パン比率（`panX / width` 等）も保存・復元すると、ズーム状態で閉じたファイルを開き直した際やプロセス再生成時の体験がさらに向上する。
  - `captureScrollAnchor()` は `findFirstVisibleItemPosition()` を使っており、画面上端に前のページの下端が数px残っているだけでも前のページ番号が `lastPage` として保存される。履歴一覧の表示ページと閲覧中のインジケータ表示（画面中央基準の `currentPage`）を一致させるか、アンカー復元後に `updateCurrentPageAndPrefetch` を実行してインジケータ表示を揃えるとよい。
- **[app/src/main/java/com/nyoki/lightpdf/MainActivity.kt:139-169] / [app/src/main/java/com/nyoki/lightpdf/ViewerActivity.kt:503-550] ダイアログのライフサイクル管理**:
  - 生成した `Dialog` インスタンスをActivityのプロパティとして保持し、`onDestroy()` で `dismiss()` することで、画面回転や設定変更時の `WindowLeaked` 例外を防止できる。
- **[app/src/main/java/com/nyoki/lightpdf/ViewerActivity.kt:313-316] 高速スクロールつまみ解放時のレイアウト待機**:
  - `onScrubStateChanged(false)` で `updateCurrentPageAndPrefetch` と `refreshHighResTiles` を呼ぶ際、`showPageJumpDialog`（526行目）と同様に `pdfRecyclerView.post { ... }` で囲み、`scrollToPositionWithOffset` によるレイアウト完了後に実行させるべき。
- **[app/src/main/java/com/nyoki/lightpdf/ui/ZoomablePdfRecyclerView.kt:259-286] ズーム中の水平パンにおけるタッチスロップ判定**:
  - `ACTION_MOVE` で即座に `abs(nextPan - panX) > 0.25f` で水平パンを開始しているが、タップや純粋な縦スクロール時の微小な横ブレを無視できるよう、初回移動時に `ViewConfiguration.get(context).scaledTouchSlop` 相当の閾値判定を入れると操作感が安定する。
- **[app/src/main/java/com/nyoki/lightpdf/ViewerActivity.kt:184-202] 全画面切替時の WindowInsets 取得方法**:
  - `insets.getInsets(WindowInsetsCompat.Type.systemBars())` の代わりに `insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())` を使用すると、システムバー非表示アニメーション中にツールバーの上部パディングが0に跳ねる現象や、横画面でのノッチ（ディスプレイカットアウト）重なりを防げる。

## 良い点
- **徹底した軽量・高速アーキテクチャ**: AppCompat・Material Components・Compose を排し、標準 View と `RecyclerView`、`PdfRenderer` のみで構成することで、リリースAPKサイズが約193KBと驚異的に小さく、コールドスタートも極めて速い。
- **高水準なレンダリングパイプライン設計**: `PdfRenderer` の全操作を単一スレッドに直列化しつつ、優先度付きキュー（同一優先度内LIFO）と画面外タスクの即時キャンセル（`pruneOffscreenTasks`）、大規模PDF（120ページ超）のチャンク分割バックグラウンド縦横比スキャンなど、スクロールを止めない工夫が随所に施されている。
- **OOMを防ぎつつ60/120fpsを維持するズーム設計**: ピンチ・ダブルタップアニメーション中は `RecyclerView` のレイアウトを変更せず Canvas 行列変換のみで滑らかに拡大し、操作確定後に可視領域（＋オーバースキャン）のみをタイルとして高解像度描画するアプローチが非常に合理的。
- **ゼロコスト夜間モード**: `ColorMatrixColorFilter` を `PdfPageView` の描画時 `Paint` に適用することで、ビットマップの再生成やキャッシュ破棄を一切伴わずに即座に切り替えている。
- **Liquid Titanium デザインの再現度**: `DESIGN.md` のカラートークン、角丸14dp、ハイライトからプライマリへのグラデーションボタン、半透明パネル、アダプティブアイコン（monochrome含む）および全ベクターアイコン化まで細部にわたり丁寧に作り込まれている。
