package com.example.novelscraper

import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.infra.InMemoryFileStore
import com.example.novelscraper.translation.v2.pipeline.DictOptions
import com.example.novelscraper.translation.v2.pipeline.DictPrompts
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.encodeNovelDict
import com.example.novelscraper.translation.v2.pipeline.generateDictionary
import com.example.novelscraper.translation.v2.pipeline.parseExtractedNames
import com.example.novelscraper.translation.v2.pipeline.parseNovelDictLenient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * 中核 internal の white-box 検証（:app からは不可視のためここに置く）。
 * 技術的根拠1行：公開APIを広げずに内部述語を直接縛るため、同一モジュール内に置く。
 */
class V2DictInternalTest {

    /**
     * 結合 scaffold の唯一の口。store・既定文面・記録の配線を1箇所に寄せる。
     * 技術的根拠1行：同一配線のコピペ重複は乖離の温床のため、差分（応答振る舞い）だけ呼出側に残す。
     */
    private fun runDictGen(
        rootName: String,
        files: List<Pair<String, String>>,
        options: DictOptions = DictOptions(maxBatchBytes = 25, maxRetriesPerBatch = 0, parallelism = 2),
        logs: MutableList<String>? = null,
        call: (DictPrompts) -> suspend (String, String, String) -> LlmResult
    ): NovelDict? = runBlocking {
        val store = InMemoryFileStore()
        val root = store.createRoot(rootName)
        val byName = files.toMap()
        generateDictionary(
            store = store,
            workDirUri = root.uri,
            fileNames = files.map { it.first },
            readText = { byName[it] },
            call = call(DictPrompts()),
            options = options,
            log = { msg -> if (logs != null) synchronized(logs) { logs.add(msg) } }
        )
    }

    @Test
    fun testDictAnnotatablePredicate() {
        assertTrue(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("李维", "レヴィ"))
        assertTrue(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("田隶", "田隷"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("文", "文"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("小灰", "小灰"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("离", "離"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("尘", "塵"))
        assertFalse(com.example.novelscraper.translation.v2.pipeline.isAnnotatableTerm("安", "安"))
    }

    @Test
    fun testEncodeNovelDict_WritesAllKeys() {
        // 技術的根拠1行：空所持の省略は「名前だけ」誤認と旧形式との区別不能を生むため、全鍵の書出しを縛る。
        val dict = NovelDict(characters = mapOf("a" to "ア"), promptsHash = "h")
        val json = encodeNovelDict(dict)
        assertTrue("style_missing:$json", json.contains("\"style\""))
        assertTrue("profiles_missing:$json", json.contains("\"profiles\""))
        assertTrue("promptsHash_missing:$json", json.contains("\"promptsHash\""))
        assertEquals(dict, parseNovelDictLenient(json))
    }

    @Test
    fun testTranslate_RetriesWhenHintsSentButProfilesEmpty() {
        // 素2件でメモゼロ返却は受渡し欠落のため取り直すこと（1欠け許容の範囲外＝2欠け）。
        val logs = mutableListOf<String>()
        var translateCalls = 0
        val dict = runDictGen(
            rootName = "d-retry-profiles",
            files = listOf("a.txt" to "李云和江思来了。", "b.txt" to "李云又来了。"),
            logs = logs
        ) { defaults ->
            { _, prompt, text ->
                if (prompt == defaults.batch) {
                    LlmResult.Success("""{"names":["李云","江思"],"hints":{"李云":["落ち着いた宗主の少年"],"江思":["明るい少女"]}}""")
                } else if (prompt == defaults.merge) {
                    LlmResult.Success(text)
                } else {
                    translateCalls++
                    if (translateCalls == 1) {
                        LlmResult.Success("""{"style":"漢字","characters":{"李云":"李雲","江思":"江思"}}""")
                    } else {
                        LlmResult.Success("""{"style":"漢字","characters":{"李云":"李雲","江思":"江思"},"profiles":{"李云":"落ち着いた宗主の少年","江思":"明るい少女"}}""")
                    }
                }
            }
        }
        assertNotNull(dict)
        assertEquals("落ち着いた宗主の少年", dict!!.profiles["李云"])
        assertEquals(2, translateCalls)
        assertTrue(logs.any { it.contains("人物メモ0件のため再試行") })
    }

    @Test
    fun testTranslate_AcceptsOneMissingWithoutRetry() {
        // 1欠けは表記揺れと区別不能のため取り直さず確定すること（送り直し浪費の防止）。
        val logs = mutableListOf<String>()
        var translateCalls = 0
        val dict = runDictGen(
            rootName = "d-partial-accept",
            files = listOf("a.txt" to "李云和江思来了。", "b.txt" to "李云又来了。"),
            logs = logs
        ) { defaults ->
            { _, prompt, text ->
                if (prompt == defaults.batch) {
                    LlmResult.Success("""{"names":["李云","江思"],"hints":{"李云":["落ち着いた宗主の少年"],"江思":["明るい少女"]}}""")
                } else if (prompt == defaults.merge) {
                    LlmResult.Success(text)
                } else {
                    translateCalls++
                    LlmResult.Success("""{"style":"漢字","characters":{"李云":"李雲","江思":"江思"},"profiles":{"李云":"落ち着いた宗主の少年"}}""")
                }
            }
        }
        assertNotNull(dict)
        assertEquals("落ち着いた宗主の少年", dict!!.profiles["李云"])
        assertEquals(1, translateCalls)
        assertTrue(logs.any { it.contains("人物メモ1/2件で確定します（1欠け許容）") })
    }

    @Test
    fun testTranslate_KeepsBestPartialResult() {
        // 2欠け以上の取り直しで後退（良い回を悪い回で上書き）せず、最良の回を確定すること。
        val logs = mutableListOf<String>()
        var translateCalls = 0
        val dict = runDictGen(
            rootName = "d-best-kept",
            files = listOf("a.txt" to "李云江思陈武来了。", "b.txt" to "李云又来了。"),
            logs = logs
        ) { defaults ->
            { _, prompt, text ->
                if (prompt == defaults.batch) {
                    LlmResult.Success("""{"names":["李云","江思","陈武"],"hints":{"李云":["落ち着いた宗主の少年"],"江思":["明るい少女"],"陈武":["厳格な中年男性"]}}""")
                } else if (prompt == defaults.merge) {
                    LlmResult.Success(text)
                } else {
                    translateCalls++
                    if (translateCalls == 1) {
                        LlmResult.Success("""{"style":"漢字","characters":{"李云":"李雲","江思":"江思","陈武":"陳武"},"profiles":{"李云":"落ち着いた宗主の少年"}}""")
                    } else {
                        LlmResult.Success("""{"style":"漢字","characters":{"李云":"李雲","江思":"江思","陈武":"陳武"},"profiles":{"陈武":"厳格な中年男性"}}""")
                    }
                }
            }
        }
        assertNotNull(dict)
        assertEquals(1, dict!!.profiles.size)
        assertEquals("落ち着いた宗主の少年", dict.profiles["李云"])
        assertEquals(2, translateCalls)
        assertTrue(logs.any { it.contains("人物メモ1/3件で確定") })
    }

    @Test
    fun testTranslate_ChunksMergeAndStyleTieBreak() {
        // 命名は上限ずつに割って送り、訳語・メモを統合し、割れた表記は多数決（同数は先勝ち）で戻すこと。
        val logs = mutableListOf<String>()
        var translateCalls = 0
        val dict = runDictGen(
            rootName = "d-chunks",
            files = listOf("a.txt" to "李云江思陈武来了。", "b.txt" to "李云又来了。"),
            options = DictOptions(maxBatchBytes = 25, maxRetriesPerBatch = 0, parallelism = 2, translateChunkNames = 2),
            logs = logs
        ) { defaults ->
            { _, prompt, text ->
                if (prompt == defaults.batch) {
                    LlmResult.Success("""{"names":["李云","江思","陈武"],"hints":{"李云":["落ち着いた宗主の少年"],"江思":["明るい少女"],"陈武":["厳格な中年男性"]}}""")
                } else if (prompt == defaults.merge) {
                    LlmResult.Success(text)
                } else {
                    translateCalls++
                    val req = parseExtractedNames(text)!!
                    val chars = req.names.associateWith { "訳$it" }
                    val profs = req.hints.keys.associateWith { "メモ$it" }
                    val style = if (translateCalls == 1) "漢字" else "カタカナ"
                    LlmResult.Success(encodeNovelDict(NovelDict(style = style, characters = chars, profiles = profs)))
                }
            }
        }
        assertNotNull(dict)
        assertEquals(3, dict!!.characters.size)
        assertEquals(3, dict.profiles.size)
        assertEquals("漢字", dict.style)
        assertEquals(2, translateCalls)
        assertTrue(logs.any { it.contains("2分割で実行中") })
        assertTrue(logs.any { it.contains("表記スタイルが割れ") })
        assertTrue(logs.any { it.contains("人物メモ3件") })
    }

    @Test
    fun testTranslate_AcceptsCharactersOnlyWhenNoHintsSent() {
        // 素なしのメモなしは正常（当て推量禁止）のため、再試行せず1回で確定すること。
        var translateCalls = 0
        val dict = runDictGen(
            rootName = "d-no-hints",
            files = listOf("a.txt" to "李云龙大战。", "b.txt" to "李云龙又来了。")
        ) { defaults ->
            { _, prompt, _ ->
                if (prompt == defaults.batch) {
                    LlmResult.Success("""{"names":["李云龙"]}""")
                } else if (prompt == defaults.merge) {
                    LlmResult.Success("""{"names":["李云龙"]}""")
                } else {
                    translateCalls++
                    LlmResult.Success("""{"style":"漢字","characters":{"李云龙":"李雲龍"}}""")
                }
            }
        }
        assertNotNull(dict)
        assertTrue(dict!!.profiles.isEmpty())
        assertEquals(1, translateCalls)
    }

    @Test
    fun testTranslate_AcceptsMemoLessAfterRetries() {
        // 素ありでも1欠け許容で初回確定し、訳語を温存して警告を残すこと（翻訳全体は止めない）。
        val logs = mutableListOf<String>()
        var translateCalls = 0
        val dict = runDictGen(
            rootName = "d-memoless",
            files = listOf("a.txt" to "李云和江思来了。", "b.txt" to "李云又来了。"),
            logs = logs
        ) { defaults ->
            { _, prompt, text ->
                if (prompt == defaults.batch) {
                    LlmResult.Success("""{"names":["李云","江思"],"hints":{"李云":["落ち着いた宗主の少年"]}}""")
                } else if (prompt == defaults.merge) {
                    LlmResult.Success(text)
                } else {
                    translateCalls++
                    LlmResult.Success("""{"style":"漢字","characters":{"李云":"李雲","江思":"江思"}}""")
                }
            }
        }
        assertNotNull(dict)
        assertEquals(mapOf("李云" to "李雲", "江思" to "江思"), dict!!.characters)
        assertTrue(dict.profiles.isEmpty())
        assertEquals(1, translateCalls)
        assertTrue(logs.any { it.contains("人物メモなしで確定") })
    }
}
