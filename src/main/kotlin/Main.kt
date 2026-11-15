import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import kotlin.system.exitProcess

/*

環境変数

ollama サーバとこのクライアントのIPが違う場合、サーバ側に下記の修正が必要

sudo systemctl stop ollama.service
sudo systemctl edit ollama
# https://qiita.com/ntrlmt/items/4f11d8e2cea2f07c2c11
# ここにあるように、 ### Anything と ### Line の間に以下の環境変数をいれる
#  CONTENT_LENGTHが短いと、「request (4161 tokens) exceeds the available context size (4096 tokens)」などのエラーが出る

[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
Environment="OLLAMA_CONTEXT_LENGTH=8192"
Environment="OLLAMA_MAX_LOADED_MODELS=1"
Environment="OLLAMA_NUM_PARALLEL=1"
Environment="OLLAMA_SCHED_SPREAD=0"
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_KV_CACHE_TYPE=q8_0"

# 終わったら ^Xを押して保存

sudo systemctl daemon-reload
sudo systemctl restart ollama

*/

// ==============================
// メイン
// ==============================

fun main(args: Array<String>) {

    val config = parseArgs(args) ?: run {
        printUsage()
        exitProcess(2)
    }

    val dir = config.directory

    if (!Files.isDirectory(dir)) {
        System.err.println("エラー: 解析対象ディレクトリが存在しません: $dir")
        exitProcess(2)
    }

    val apiFormat = config.format ?: detectFormat(config.serverUrl)

    // HTTPクライアント
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    // 対象画像を取得
    val images = Files.list(dir).use { stream ->
        stream
            .filter { isImageFile(it) }
            .sorted()
            .toList()
    }

    println("対象ディレクトリ: $dir")
    println("画像枚数: ${images.size}")
    println("API形式: $apiFormat")
    println("送信先: ${config.serverUrl}")
    println("モデル: ${config.modelName}")

    var ok = 0
    var fail = 0

    // 画像を1枚ずつ処理
    for ((index, imagePath) in images.withIndex()) {

        println()
        println("[${index + 1}/${images.size}] 解析中: ${imagePath.fileName}")

        try {

            val result = analyzeImage(
                client = client,
                imagePath = imagePath,
                modelName = config.modelName,
                prompt = config.prompt,
                serverURL = config.serverUrl,
                apiFormat = apiFormat,
                apiKey = config.apiKey,
                verbose = config.verbose
            )

            // 結果を保存
            saveResult(
                imagePath = imagePath,
                result = result
            )

            println("解析終了: $result")
            ok++

        } catch (e: Exception) {

            System.err.println("解析失敗: ${imagePath.fileName} - ${e.message}")
            if (config.verbose) {
                e.printStackTrace(System.err)
            }
            fail++
        }
    }

    println()
    println("完了: 成功 $ok 件 / 失敗 $fail 件")

    if (fail > 0) {
        exitProcess(1)
    }
}

// ==============================
// 引数解析
// ==============================

data class CliConfig(
    val directory: Path,
    val serverUrl: String,
    val modelName: String,
    val prompt: String,
    val apiKey: String,
    val format: ApiFormat?,
    val verbose: Boolean
)

/**
 * 設定は「コマンドライン引数 → 環境変数 → コード内既定値」の順で解決する。
 * 既定値は GUI 版のハードコード値を踏襲している。
 */
private fun parseArgs(args: Array<String>): CliConfig? {

    var serverUrl = System.getenv("OLLAMA_SERVER_URL")
        ?: "http://strataserver:8888/v1/chat/completions"

    var modelName = System.getenv("OLLAMA_SERVER_MODEL")
        ?: "hf.co/OBLITERATUS/Qwen3.8-27B-OBLITERATED:Q2_K"  // strata の場合は無視

    // 画像解析用プロンプト
    var prompt = System.getenv("OLLAMA_SERVER_PROMPT")
        ?: """
    make a very detailed description for stable diffusion moss of the image in one line, 
     but remove hairstyle and hair color and eye color and breast size of woman character 
     specifications from the description in English natural language style, not danbooru tag style. add "komaro michinoku," in front of the prompt line.
        """.trimIndent()

    // APIキー（未入力ならヘッダー付与しない）
    var apiKey = System.getenv("OPENAI_API_KEY") ?: ""

    var format: ApiFormat? = null
    var verbose = false
    var directory: Path? = null

    var i = 0
    while (i < args.size) {

        val a = args[i]

        when {

            a == "-h" || a == "--help" -> return null

            a == "--verbose" || a == "-v" -> {
                verbose = true
                i++
            }

            a == "--url" || a == "--server" -> {
                serverUrl = nextValue(args, i, a)
                i += 2
            }

            a == "--model" -> {
                modelName = nextValue(args, i, a)
                i += 2
            }

            a == "--prompt" -> {
                prompt = nextValue(args, i, a)
                i += 2
            }

            a == "--apikey" || a == "--api-key" -> {
                apiKey = nextValue(args, i, a)
                i += 2
            }

            a == "--format" -> {
                val v = nextValue(args, i, a).trim().lowercase()
                format = when {
                    v.startsWith("ollama") -> ApiFormat.OLLAMA
                    v.startsWith("openai") -> ApiFormat.OPENAI_COMPATIBLE
                    else -> {
                        System.err.println("エラー: --format は ollama / openai のいずれかを指定してください: $v")
                        exitProcess(2)
                    }
                }
                i += 2
            }

            a.startsWith("-") -> {
                System.err.println("エラー: 不明なオプション: $a")
                return null
            }

            else -> {
                // 位置引数 = 解析対象ディレクトリ（1つのみ）
                if (directory == null) {
                    directory = Path.of(a)
                } else {
                    System.err.println("エラー: ディレクトリは1つだけ指定できます: $a")
                    return null
                }
                i++
            }
        }
    }

    if (directory == null) {
        System.err.println("エラー: 解析対象ディレクトリが指定されていません。")
        return null
    }

    return CliConfig(
        directory = directory,
        serverUrl = serverUrl.trim(),
        modelName = modelName.trim(),
        prompt = prompt,
        apiKey = apiKey.trim(),
        format = format,
        verbose = verbose
    )
}

private fun nextValue(args: Array<String>, i: Int, name: String): String {
    if (i + 1 >= args.size) {
        System.err.println("エラー: $name の値が指定されていません。")
        exitProcess(2)
    }
    return args[i + 1]
}

private fun printUsage() {

    println(
        """
使い方: analyze-image-cli <画像ディレクトリ> [オプション]

位置引数:
  <画像ディレクトリ>          解析対象のフォルダ（jpg / jpeg / png / webp を走査し、
                             同じフォルダに同名の .txt を書き出す）

オプション（未指定時は 環境変数 → コード内既定値 の順で解決）:
  --url, --server <URL>      送信先 URL      (env: OLLAMA_SERVER_URL)
  --model <name>             モデル名        (env: OLLAMA_SERVER_MODEL)
  --prompt <text>            解析プロンプト  (env: OLLAMA_SERVER_PROMPT)
  --apikey <key>             APIキー         (env: OPENAI_API_KEY / 未指定なら Authorization ヘッダーなし)
  --format <ollama|openai>   API形式（未指定なら URL から自動判定: /v1/ または 8080 を含めば OpenAI互換）
  -v, --verbose              詳細ログを stderr に出す
  -h, --help                 このヘルプを表示

終了コード:
  0 全成功 / 1 失敗あり / 2 引数エラー

例:
  analyze-image-cli /data/images
  analyze-image-cli /data/images --format openai --apikey apikey
  analyze-image-cli /data/images --url http://local-llama:11434/api/chat --model llama3.2-vision --format ollama
        """.trimIndent()
    )
}

/** URL文字列からAPI形式を推定する */
private fun detectFormat(url: String): ApiFormat {
    val u = url.trim().lowercase()
    return when {
        u.contains("/v1/") || u.contains("8080") -> ApiFormat.OPENAI_COMPATIBLE
        else -> ApiFormat.OLLAMA
    }
}

// ==============================
// API 形式
// ==============================

enum class ApiFormat {
    OLLAMA,
    OPENAI_COMPATIBLE
}

// ==============================
// 画像解析
// ==============================

fun analyzeImage(
    client: HttpClient,
    imagePath: Path,
    modelName: String,
    prompt: String,
    serverURL: String,
    apiFormat: ApiFormat,
    apiKey: String,
    verbose: Boolean = false
): String {

    val imageBytes = Files.readAllBytes(imagePath)
    val imageBase64 = Base64.getEncoder().encodeToString(imageBytes)
    val mimeType = getMimeType(imagePath)

    val json = when (apiFormat) {
        ApiFormat.OLLAMA -> """
            {
              "model": ${jsonString(modelName)},
              "messages": [
                {
                  "role": "user",
                  "content": ${jsonString(prompt)},
                  "images": [${jsonString(imageBase64)}]
                }
              ],
              "stream": false
            }
        """.trimIndent()

        ApiFormat.OPENAI_COMPATIBLE -> {
            val dataUrl = "data:$mimeType;base64,$imageBase64"
            """
            {
              "model": ${jsonString(modelName)},
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {
                      "type": "text",
                      "text": ${jsonString(prompt)}
                    },
                    {
                      "type": "image_url",
                      "image_url": {
                        "url": ${jsonString(dataUrl)}
                      }
                    }
                  ]
                }
              ],
              "stream": false
            }
            """.trimIndent()
        }
    }

    if (verbose) {
        System.err.println("  API形式: $apiFormat")
        System.err.println("  送信先: $serverURL")
        System.err.println("  送信画像: $imagePath")
        System.err.println("  画像サイズ: ${imageBytes.size} bytes / MIME: $mimeType")
    }

    val builder = HttpRequest.newBuilder()
        .uri(URI.create(serverURL))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofMinutes(10))
    if (apiKey.isNotBlank()) {
        builder.header("Authorization", "Bearer $apiKey")
    }
    val request = builder
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() !in 200..299) {
        throw RuntimeException("APIエラー (${response.statusCode()})\n${response.body()}")
    }

    val body = response.body()
    return when (apiFormat) {
        ApiFormat.OLLAMA -> {
            val messageStart = body.indexOf("\"message\"")
            if (messageStart < 0) {
                throw RuntimeException("Ollamaレスポンスに message がありません: $body")
            }
            extractJsonStringValue(body, "content", messageStart)
                ?: throw RuntimeException("Ollamaレスポンスから message.content を取得できません: $body")
        }

        ApiFormat.OPENAI_COMPATIBLE -> {
            val choicesStart = body.indexOf("\"choices\"")
            if (choicesStart < 0) {
                throw RuntimeException("OpenAI互換レスポンスに choices がありません: $body")
            }
            val messageStart = body.indexOf("\"message\"", choicesStart)
            if (messageStart < 0) {
                throw RuntimeException("OpenAI互換レスポンスに message がありません: $body")
            }
            extractJsonStringValue(body, "content", messageStart)
                ?: throw RuntimeException("OpenAI互換レスポンスから choices[0].message.content を取得できません: $body")
        }
    }
}

// 指定位置以降にある JSON 文字列プロパティを取得する
fun extractJsonStringValue(json: String, key: String, fromIndex: Int = 0): String? {
    val keyToken = "\"$key\""
    val keyIndex = json.indexOf(keyToken, fromIndex)
    if (keyIndex < 0) return null

    var pos = json.indexOf(':', keyIndex + keyToken.length)
    if (pos < 0) return null
    pos++

    while (pos < json.length && json[pos].isWhitespace()) pos++
    if (pos >= json.length || json[pos] != '"') return null
    pos++

    val result = StringBuilder()
    var escaped = false
    while (pos < json.length) {
        val c = json[pos++]
        if (escaped) {
            when (c) {
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    if (pos + 4 <= json.length) {
                        val code = json.substring(pos, pos + 4).toIntOrNull(16) ?: return null
                        result.append(code.toChar())
                        pos += 4
                    } else return null
                }
                else -> result.append(c)
            }
            escaped = false
        } else {
            when (c) {
                '\\' -> escaped = true
                '"' -> return result.toString()
                else -> result.append(c)
            }
        }
    }
    return null
}

// ==============================
// 結果保存
// ==============================

fun saveResult(
    imagePath: Path,
    result: String
) {

    val fileName =
        imagePath.fileName.toString()

    val baseName =
        fileName.substringBeforeLast('.')

    val outputPath = imagePath.parent.resolve("$baseName.txt")

    Files.writeString(
        outputPath,
        result
    )

    println("保存: $outputPath")
}

// ==============================
// 画像ファイル判定
// ==============================

fun isImageFile(path: Path): Boolean {

    if (!Files.isRegularFile(path)) {
        return false
    }

    val name =
        path.fileName.toString()
            .lowercase()

    return name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".webp")
}

// ==============================
// MIMEタイプ
// ==============================

fun getMimeType(path: Path): String {

    val name =
        path.fileName.toString()
            .lowercase()

    return when {

        name.endsWith(".jpg") ||
                name.endsWith(".jpeg") ->
            "image/jpeg"

        name.endsWith(".png") ->
            "image/png"

        name.endsWith(".webp") ->
            "image/webp"

        else ->
            "application/octet-stream"
    }
}

// ==============================
// JSON文字列エスケープ
// ==============================

fun jsonString(value: String): String {

    return buildString {

        append('"')

        for (c in value) {

            when (c) {

                '"' ->
                    append("\\\"")

                '\\' ->
                    append("\\\\")

                '\n' ->
                    append("\\n")

                '\r' ->
                    append("\\r")

                '\t' ->
                    append("\\t")

                '\b' ->
                    append("\\b")

                '\u000C' ->
                    append("\\f")

                else ->
                    append(c)
            }
        }

        append('"')
    }
}