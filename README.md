# analyze-image-cli

AnalyzeImageClient の Java Swing (GUI) 版を、コマンドライン CLI に置き換えたもの。

## analyze images using llm

- llmを使って画像の英語promptを得ます
- 主にllmを使った画像の学習用txtの作成など（じゃあなんでabliteratedとか聞くな）
- llmを使うのでWD14 taggerよりはるかに重い
- でもQwen on Strataならまあまあ早くて、ollamaなんかやってられん
- 画像と同じディレクトリに `<画像名>.txt` として保存する挙動は GUI 版そのまま

## 環境変数

前もって設定しておく環境変数（GUI 版からそのまま流用可）。

- `OLLAMA_SERVER_URL="http://server.addr.jp:11434/api/chat"`
    - promptを投げるAPI URLを指定 [4]
    - OpenAI Vision API互換形式にも対応（URL に `/v1/` または `8080` を含めば自動で OpenAI 互換と判定）
    - llama.cppはAMDでcore dumpしまくりだったので、今回は不採用 [4]
- `OLLAMA_SERVER_MODEL="hf.co/mradermacher/Qwen3-VL-4B-Instruct-abliterated-GGUF:Q4_K_M"`
    - まあまあ軽いモデル。`Q4_K_M` 相当でもそこそこ重い [4]
- `OLLAMA_SERVER_PROMPT="provide a very detailed description of the image in one line"`
    - llm に投げる promptそのもの [4]
    - very detailed description の重みは自分で調整してね [4]
    - 未指定時は anima 向け（髪型・髪色・瞳の色・胸のサイズ指定を除く、自然な英語散文、Danbooru タグ形式ではない）の既定プロンプトを使用 [1]
- `OPENAI_API_KEY="api_key_donot_see"`
    - OpenAI API / Strata にアクセスするときの鍵。きちんとStrataに鍵かけてね [4]
    - 未設定なら `Authorization` ヘッダーを付けずに送信 [1]

## 必須環境

- JDK **17 以上**（`java.net.http.HttpClient` を使っており、toolchain も 17 指定）[1]
- 実行マシンに Gradle が入っていなくても `./gradlew`（wrapper）でビルド可能
- 外部依存なし。HTTP・JSON・ファイル I/O はすべて JDK 標準実装のみ [1]
    - ただし Kotlin 実行のため `kotlin-stdlib` が必要 → fat jar（shadow）で同梱する

## ビルド

```
./gradlew build          # コンパイル確認
./gradlew shadowJar      # 単一実行可能 jar を生成
```

生成物:

```
build/libs/analyze-image-cli-1.0-all.jar
```

- shadow は `application` プラグイン標準の `distZip` / `distTar` を無効化します
  （`shadowDistZip` / `shadowDistTar` に置き換わる）
- JVM 引数も管理したいなら `./gradlew installDist` で
  `build/install/analyze-image-cli/bin/analyze-image-cli` でも実行可

## 起動

```
java -jar build/libs/analyze-image-cli-1.0-all.jar /path/to/images
java -jar build/libs/analyze-image-cli-1.0-all.jar /path/to/images --format openai --apikey あなたの鍵
java -jar build/libs/analyze-image-cli-1.0-all.jar /path/to/images \
  --url http://192.168.11.100:11434/api/chat --model llama3.2-vision --format ollama
```

GUI 版の `java -jar AnalyzeImageClient.jar` → dialog → `Select Directory...` は、
CLI では **位置引数としてディレクトリを渡す**動作に置き換わりました [4]。

### 使い方

```
使い方: analyze-image-cli <画像ディレクトリ> [オプション]

オプション（未指定時は 環境変数 → コード内既定値 の順で解決）:
  --url, --server <URL>      送信先 URL      (env: OLLAMA_SERVER_URL)
  --model <name>             モデル名        (env: OLLAMA_SERVER_MODEL)
  --prompt <text>            解析プロンプト  (env: OLLAMA_SERVER_PROMPT)
  --apikey <key>             APIキー         (env: OPENAI_API_KEY / 未指定なら Authorization ヘッダーなし)
  --format <ollama|openai>   API形式（未指定なら URL から自動判定: /v1/ または 8080 を含めば OpenAI互換）[1]
  -v, --verbose              詳細ログを stderr に出す
  -h, --help                 ヘルプを表示

終了コード: 0 全成功 / 1 失敗あり / 2 引数エラー
```

- 対象は `jpg` / `jpeg` / `png` / `webp`（GUI 版と同じ拡張子判定）[1]
- 結果は画像と同じフォルダに同名 `.txt` で保存（GUI 版と同じ）[1]
- 進捗と解析結果は stdout、エラーと詳細ログは stderr（パイプ・ログに向ける想定）
- Gradle 経由で開発中に実行する場合:

```
./gradlew run --args="/path/to/images --format openai"
./gradlew run -Pdir=/path/to/images -PcliArgs="--format openai"
```

## Server と Client が違う場合

- ServerはUbuntu ollamaで構築しました（当初）。StrataのOpenAI互換に切り替えた [4]
- ollama サーバとこのクライアントのIPが違う場合、サーバ側に下記の修正が必要 [4]

```
sudo systemctl stop ollama.service
sudo systemctl edit ollama
```

- https://qiita.com/ntrlmt/items/4f11d8e2cea2f07c2c11
- 上記にあるように、 `### Anything` と `### Line` の間に以下の環境変数をいれる [4]
- CONTENT_LENGTHが短いと、「request (4161 tokens) exceeds the available context size (4096 tokens)」などのエラーが出る [4]

```
[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
Environment="OLLAMA_CONTEXT_LENGTH=8192"
Environment="OLLAMA_MAX_LOADED_MODELS=1"
Environment="OLLAMA_NUM_PARALLEL=1"
Environment="OLLAMA_SCHED_SPREAD=0"
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_KV_CACHE_TYPE=q8_0"
```

- 終わったら `^X`を押して保存 [4]

```
sudo systemctl daemon-reload
sudo systemctl restart ollama
```

## TIPS

- JSON は正規ライブラリなしの手書き抽出（`extractJsonStringValue`）。本格的に使うなら Jackson / kotlinx.serialization 推奨 [1]
- cron 等で回すなら `--url` / `--model` を引数で固定したラッパースクリプトにするのが事故りにくい