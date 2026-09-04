package com.example.novelscraper.ui.components

/**
 * テンプレート候補データ
 */
data class TemplateOption(
    val label: String,
    val value: String,
    val description: String = ""
)

/**
 * 各入力欄の詳しい解説・構文ルール・テンプレート情報
 */
data class SelectorHelpInfo(
    val title: String,
    val description: String,
    val syntaxRules: List<String> = emptyList(),
    val templates: List<TemplateOption> = emptyList()
)

/**
 * 設定画面の全入力欄に対応するヘルプおよびテンプレート定義
 */
object SettingsHelpData {

    val FOLDER = SelectorHelpInfo(
        title = "作品名 Selector の使いかた",
        description = "保存先フォルダ名となる小説のタイトルをWebページから取得するためのセレクタです。空欄の場合はサイト内の大見出し(h1等)から自動取得します。",
        syntaxRules = listOf(
            "通常のCSSセレクタ: 例「h1.novel_title」",
            "ページタイトル: 「title」と書くと、<title>タグ（ヘッダー情報）から作品名を取得します。",
            "@固定タイトル: 例「@私の好きな小説」と書くと、サイトから取得せず常に指定したフォルダ名で固定保存します。"
        ),
        templates = listOf(
            TemplateOption("なろう標準", "h1.novel_title", "小説家になろうの作品タイトル見出し"),
            TemplateOption("大見出し (h1)", "h1", "ページ内で最上位のh1見出し要素"),
            TemplateOption("ページタイトル (titleタグ)", "title", "<title>タグ（ヘッダー情報）から作品名を取得"),
            TemplateOption("固定フォルダ名", "@作品名", "サイトの文字に関係なく指定フォルダに保存"),
            TemplateOption("OGPメタタグ", "meta[property=\"og:title\"]", "HTMLヘッダー内の共有タイトル"),
            TemplateOption("パンくずの親作品", "nav.breadcrumbs a:nth-of-type(2)", "パンくずリスト内の作品トップリンク")
        )
    )

    val FOLDER_REGEX = SelectorHelpInfo(
        title = "作品名 Regex の使いかた",
        description = "取得した作品名から、サイト名や余計な記号を削除・抽出するための正規表現です。「抽出（カッコで囲む）」と「削除（不要部分を削る／del:で直接消す）」の両方に対応しています。",
        syntaxRules = listOf(
            "【抽出】キャプチャ括弧 ( ) の中身を取り出す: 例 _(.*)$",
            "【削除】残したい文字を括弧で囲み、不要な文字を削る: 例 ^(.*?)\\s*-\\s*サイト名$",
            "【直接消去】「del:消したい文字」と書くと、その文字を完全に消去します: 例 del:カクヨム"
        ),
        templates = listOf(
            TemplateOption("【抽出】_以降を抽出", "_(.*)$", "「話数_作品名」から_以降の作品名を取り出す"),
            TemplateOption("【抽出】-以降を抽出", "-(.*)$", "「話数-作品名」から-以降の作品名を取り出す"),
            TemplateOption("【抽出】|以降を抽出", "\\|(.*)$", "「話数|作品名」から|以降の作品名を取り出す"),
            TemplateOption("【抽出】カギ括弧内を抽出", "『(.*?)』", "『』で囲まれた作品名のみを取り出す"),
            TemplateOption("【抽出】角括弧内を抽出", "【(.*?)】", "【】で囲まれた文字列を取り出す"),
            TemplateOption("【削除】末尾のサイト名・記号を削除", "^(.*?)(?:\\s*[-_|~]\\s*.*)$", "末尾の「 - サイト名」「 _ カクヨム」等を除去"),
            TemplateOption("【削除】先頭の [タグ] を削除", "^\\[.*?\\]\\s*(.*)$", "先頭の「[完結] 」「[R18] 」等を除去"),
            TemplateOption("【削除】先頭の 【タグ】 を削除", "^【.*?】\\s*(.*)$", "先頭の「【公式】 」等を除去"),
            TemplateOption("【直接消去】del: で特定文字を消去", "del:カクヨム", "文章中から指定した文字を完全削除")
        )
    )

    val FOLDER_LINK = SelectorHelpInfo(
        title = "別URL取得 Selector の使いかた",
        description = "各話（本文ページ）に作品名が書かれていないサイトで利用します。目次ページや作品トップページへのリンク（<a>タグ）を指定すると、そのリンク先ページに一時的にジャンプして作品名を取得してから本文スクレイピングを開始します。",
        syntaxRules = listOf(
            "リンク要素 (<a>タグ) または aタグを含む親要素を指定します。",
            "インスペクターでタップすると、自動で親の <a> タグを検出します。"
        ),
        templates = listOf(
            TemplateOption("シリーズリンク", "a.series-link", "目次・シリーズへの標準的なリンク"),
            TemplateOption("パンくずの作品トップ", "nav.breadcrumbs a:nth-of-type(2)", "パンくずリスト内の親作品リンク"),
            TemplateOption("作品トップリンク", "a[href*=\"/series/\"]", "URLに /series/ を含むリンク"),
            TemplateOption("タイトル枠のリンク", ".novel_title a", "作品名ブロック内のリンク")
        )
    )

    val TITLE = SelectorHelpInfo(
        title = "タイトル Selector の使いかた",
        description = "各話（チャプター）のサブタイトルを抽出するセレクタです。保存されるファイル名（0001_タイトル.txt）のタイトル部分になります。空欄の場合はページタイトル等から自動検出します。",
        syntaxRules = listOf(
            "通常のCSSセレクタを指定します。"
        ),
        templates = listOf(
            TemplateOption("なろう標準", ".novel_subtitle", "小説家になろうの各話サブタイトル"),
            TemplateOption("章見出し", ".chapter-title", "一般的なWeb小説の各話タイトル"),
            TemplateOption("記事見出し (h1)", "h1.entry-title", "ブログ形式や記事形式のタイトル"),
            TemplateOption("中見出し (h2)", "h2", "ページ内の第2見出し"),
            TemplateOption("ブログ標準", "div.entry-content h1, div.entry-title", "WordPress等の標準記事タイトル")
        )
    )

    val TITLE_REGEX = SelectorHelpInfo(
        title = "タイトル Regex の使いかた",
        description = "取得したタイトルから余計な文字列を除去・整形するための正規表現です。キャプチャ括弧 ( ) を使った抽出・削ぎ落としや、「del:」による直接消去が可能です。",
        syntaxRules = listOf(
            "【抽出】キャプチャ括弧 ( ) の中身を取り出す: 例 _(.*)$",
            "【削除】先頭や末尾の不要な文字を削る: 例 ^第\\d+話\\s*(.*)$",
            "【直接消去】「del:消したい文字」で文章中から完全消去: 例 del:\\[無料版\\]"
        ),
        templates = listOf(
            TemplateOption("【削除】先頭の「第○話」を削除", "^第\\d+話\\s*(.*)$", "「第15話 はじまりの朝」➔「はじまりの朝」"),
            TemplateOption("【削除】先頭の「第○章」を削除", "^第\\d+章\\s*(.*)$", "「第3章 激闘」➔「激闘」"),
            TemplateOption("【削除】先頭の連番「01.」「01-」を削除", "^\\d+[\\.\\s_\\-:]\\s*(.*)$", "「01. プロローグ」➔「プロローグ」"),
            TemplateOption("【削除】先頭の「Episode ○」を削除", "^Episode\\s*\\d+\\s*[-:：]?\\s*(.*)$", "「Episode 5: 再会」➔「再会」"),
            TemplateOption("【削除】先頭の [角括弧] タグを削除", "^\\[.*?\\]\\s*(.*)$", "「[短編] 街の灯り」➔「街の灯り」"),
            TemplateOption("【削除】先頭の 【隅付き括弧】 タグを削除", "^【.*?】\\s*(.*)$", "「【番外編】 温泉旅行」➔「温泉旅行」"),
            TemplateOption("【削除】末尾の「 - サイト名」を削除", "^(.*?)(?:\\s*[-|~]\\s*.*)$", "「第1話 旅立ち - サイト名」➔「第1話 旅立ち」"),
            TemplateOption("【削除】末尾の (注釈・日付) を削除", "^(.*?)\\s*\\(.*?\\)$", "「第1話 旅立ち (2024/01/01)」➔「第1話 旅立ち」"),
            TemplateOption("【直接消去】del: で特定フレーズを消去", "del:\\(無料版\\)", "タイトル内から指定した文字を完全削除"),
            TemplateOption("【抽出】_以降を抽出", "_(.*)$", "「話数_サブタイトル」から_以降を取り出す"),
            TemplateOption("【抽出】カギ括弧内を抽出", "『(.*?)』", "『』で囲まれたサブタイトルのみを取り出す")
        )
    )

    val CHAPTER = SelectorHelpInfo(
        title = "チャプター番号 Selector の使いかた ★最重要",
        description = "ファイル名先頭の番号（例: 0001_、0002_）を決定します。Webサイトの要素から取得するだけでなく、強力な自動連番指定が可能です。",
        syntaxRules = listOf(
            "【ハイブリッド指定（★一番おすすめ！）】:「セレクタ || @1」",
            " ➔ サイトから話数が取れるときはそれを使い、数字のない番外編やプロローグは自動通し連番で補完します。番号の重複・上書きが100%防止されます。",
            "【自動連番】:",
            " ・@1 : 4桁0埋め連番（0001, 0002...）",
            " ・@01 : 2桁0埋め連番（01, 02...）",
            " ・@1# : 0埋めなし連番（1, 2, 3...）",
            " ・@51 : 51番から開始（途中再開時に便利）",
            "【URL抽出】:「@URL」でURL末尾の数字（/123/ ➔ 0123）を抽出"
        ),
        templates = listOf(
            TemplateOption("★ハイブリッド (推奨)", ".chapter-title || @1", "セレクタ優先＋番外編は自動通し連番で重複防止"),
            TemplateOption("4桁連番 (0001〜)", "@1", "サイトの数字に頼らず完全通し番号で保存"),
            TemplateOption("2桁連番 (01〜)", "@01", "全2桁の連番（短編・中編向け）"),
            TemplateOption("0埋めなし連番 (1〜)", "@1#", "パディングなし（1, 2, 3...）"),
            TemplateOption("URLから数字抽出", "@URL", "ページURLに含まれるID・数字を利用")
        )
    )

    val CHAPTER_REGEX = SelectorHelpInfo(
        title = "チャプター番号 Regex の使いかた",
        description = "タイトルやページ内の文字列から「話数の数字」だけを正確に抜き出すための正規表現です。数字部分をキャプチャ括弧 (\\d+) で囲んで指定します。",
        syntaxRules = listOf(
            "(\\d+) の部分がチャプター番号として認識されます。"
        ),
        templates = listOf(
            TemplateOption("「第○話」の数字", "第(\\d+)話", "第15話 ➔ 15 を抽出"),
            TemplateOption("「第○章」の数字", "第(\\d+)章", "第3章 ➔ 3 を抽出"),
            TemplateOption("「Episode ○」の数字", "Episode\\s*(\\d+)", "Episode 7 ➔ 7 を抽出"),
            TemplateOption("「#○」の数字", "#(\\d+)", "#01 ➔ 01 を抽出"),
            TemplateOption("先頭の数字 (01_ 等)", "^(\\d+)", "「01_プロローグ」等の先頭数字を抽出"),
            TemplateOption("最初の連続数字", "(\\d+)", "文字列内で最初に出てくる数字"),
            TemplateOption("末尾の数字", "(\\d+)$", "タイトルの一番後ろにある数字")
        )
    )

    val BODY = SelectorHelpInfo(
        title = "本文 Selector の使いかた",
        description = "小説の本文テキストが入っているHTML要素を指定します。余計なヘッダーや広告を含まない、本文専用のコンテナを指定するのがコツです。",
        syntaxRules = listOf(
            "ID（#name）やクラス（.name）を指定します。",
            "虫眼鏡（インスペクター）で本文をタップすると最適な候補が自動生成されます。"
        ),
        templates = listOf(
            TemplateOption("小説家になろう", "#novel_honbun", "なろうの標準本文ブロック"),
            TemplateOption("汎用 novel-body", "div.novel-body", "多数の小説サイトで使われるクラス"),
            TemplateOption("記事タグ (article)", "article", "HTML5標準の記事コンテナ"),
            TemplateOption("メインタグ (main)", "main", "ページ主要コンテンツ枠"),
            TemplateOption("ブログ標準", "div.entry-content", "WordPress等の標準本文クラス")
        )
    )

    val EXCLUDE = SelectorHelpInfo(
        title = "除外要素 の使いかた",
        description = "本文コンテナの中から、保存したくない不要な要素（広告、ルビ、前書き、作者コメント、SNSボタン等）を削除します。カンマ区切りで複数指定できます。",
        syntaxRules = listOf(
            "複数のセレクタを「, 」（カンマ）で区切って記述できます。"
        ),
        templates = listOf(
            TemplateOption("広告全般 (汎用)", ".ad, .advertisement, [id*=\"google_ads\"], [class*=\"ad-\"]", "一般的なWeb広告タグを除去"),
            TemplateOption("前書き・後書き (なろう)", "#novel_p, #novel_a", "本文以外の前書きと後書きを除去"),
            TemplateOption("ルビ（ふりがな）除去", "rt", "ルビの読み仮名を消して漢字本文のみにする"),
            TemplateOption("SNS・シェアボタン", ".sns-share, .share-buttons, .social-share", "各話末尾の共有ボタンを除去"),
            TemplateOption("作者コメント・後書き枠", ".author-comment, .postscript, .afterword", "本文途中の作者コメントブロックを除去"),
            TemplateOption("ナビ・ボタン要素", "button, .btn, .nav, .page-nav", "本文枠内に紛れ込んだボタンタグを除去")
        )
    )

    val NEXT = SelectorHelpInfo(
        title = "次ページ Selector の使いかた",
        description = "次の話へ進むリンク（<a>タグ）を指定します。空欄の場合は「次へ」「Next」「下一章」等のテキストから自動検索します。自動検出でうまく進まないサイトや、より確実に指定したい場合にこのセレクタを入力します。",
        syntaxRules = listOf(
            "通常のリンク: a[rel=\"next\"] 等のCSSセレクタ",
            "JavaScript変数参照: 「js:」を先頭に付けるとJSのグローバル変数からURLを取得できます（例: js:window.book.nextUrl）"
        ),
        templates = listOf(
            TemplateOption("Web標準 (rel=next)", "a[rel=\"next\"]", "HTML標準の次リンク属性（多くのサイトで有効）"),
            TemplateOption("小説家になろう", ".novel_bn a:last-child", "前へ/次への右側リンク"),
            TemplateOption("カクヨム", ".widget-episode-sideBySide-next a, .widget-episode-next a", "カクヨムの次話ボタン"),
            TemplateOption("汎用 次へボタン", "a.next, a.next-page, a.btn-next, a.page-next", "一般的な次ページリンククラス"),
            TemplateOption("汎用 ナビリンク", "a.nav-next, a#next_url, a#nextChapter", "Web小説・ブログ向け"),
            TemplateOption("JS変数から取得", "js:window.book.nextUrl", "ボタンがなくJS内部にURLがある場合")
        )
    )

    val END_CHECK = SelectorHelpInfo(
        title = "終了検知 Regex の使いかた",
        description = "次ページURLやタイトルに合致した際、最終話に到達したと判定してスクレイピングを自動停止させる正規表現です。",
        syntaxRules = listOf(
            "パイプ「|」で複数条件をOR指定できます。"
        ),
        templates = listOf(
            TemplateOption("目次・無効リンクで停止", "list|index|toc|null|javascript:", "目次に戻った時や空リンクで停止"),
            TemplateOption("完結・最終話キーワード", "【完結】|最終話|あとがき|おわり|fin|END", "タイトルに最終話キーワードがある時停止"),
            TemplateOption("作品トップURLで停止", "index\\.html$|/top$|/series/|/title/", "トップページ・目次に戻るリンクを踏んだ時に停止")
        )
    )

    val DELAY = SelectorHelpInfo(
        title = "待機時間(秒) の使いかた",
        description = "各ページを取得した後の待機秒数を指定します。サーバーへの過剰な負荷を防ぎ、サイトのアクセス遮断（BAN）を回避するために重要です。",
        syntaxRules = listOf(
            "固定秒数: 例「3」（常に3秒待機）",
            "ランダム範囲: 例「3-6」（3秒〜6秒の間でランダムに変動して人間らしくアクセス）"
        ),
        templates = listOf(
            TemplateOption("標準ランダム (3-5秒)", "3-5", "自然な間隔で安全に取得（推奨）"),
            TemplateOption("安全第一 (5-8秒)", "5-8", "制限が厳しいサイトや長編向け"),
            TemplateOption("高速固定 (2秒)", "2", "高速に巡回（ローカルテスト等）")
        )
    )

    fun getAutoUrlHelp(currentDomain: String): SelectorHelpInfo {
        val templates = mutableListOf<TemplateOption>()
        if (currentDomain.isNotEmpty() && currentDomain != "about:blank") {
            templates.add(TemplateOption("現在のドメインをセット", currentDomain, "現在開いているサイト: $currentDomain"))
        }
        templates.add(TemplateOption("小説家になろう", "syosetu.com", "なろうグループ全域"))
        templates.add(TemplateOption("カクヨム", "kakuyomu.jp", "カクヨム"))
        templates.add(TemplateOption("ハーメルン", "syosetu.org", "ハーメルン"))

        return SelectorHelpInfo(
            title = "自動適用URL (ドメイン) の使いかた",
            description = "このプリセット設定を自動的に適用したいサイトのドメイン（例: syosetu.com）を指定します。設定しておくと、そのサイトを開くだけで自動的にこのプリセットが読み込まれます。",
            syntaxRules = listOf(
                "ドメイン名（例: syosetu.com）を入力します。http:// やパスは不要です。"
            ),
            templates = templates
        )
    }
}
