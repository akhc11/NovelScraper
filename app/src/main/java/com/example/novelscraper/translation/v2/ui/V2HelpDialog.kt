package com.example.novelscraper.translation.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.novelscraper.ui.theme.AppColors

private const val V2_HELP_TEXT = """■ 全体の流れ
フォルダ巡回 → 言語検出 → 辞書解決 → 翻訳 → 保存の順に進みます。
辞書は初回に作り、次回から使い回します。辞書が未完成の章は訳さず次回に回します（トークン浪費防止）。
翻訳は束ね（数話一括）・単品・大ファイル分割の3路ですが、中身の「送る→確かめる→保存する」は共通です。
推敲on時は保存の直前に磨き直しを1回だけ行います。

■ 指示文の組み立て順
基底文（1〜7番・カスタム）→ 前話文脈 → 人名指示 → 束ね枠 → 完走栞の注記の順に足します。
前話文脈は訳文末尾があればそれだけ、なければ原文末尾です（重ねません）。

■ タグ・記号集
[人物対応表挿入位置]
  基底文の中の指定行です。人物表がある章はこの位置に表が混ざり、ない章は行ごと消えます。
  指定行のない文面では表が末尾に付きます。小説本文には入りません。
[SRC_END]（完走栞）
  原文の最後に付けて送り、訳文の最後への複写で完走を確認します。栞なしは途切れ疑いです。
名前⟦読み⟧（注釈・中国語のみ）
  本文に確定訳を書き込み、訳文から剥がします。中国語以外の言語では使いません。
=== PREVIOUS CONTEXT / TEXT ===
  前話の文脈（訳文末尾優先・なければ原文末尾）です。訳文には含めません。
<doc id> / <trans id>（束ね用）
  複数話を束ねる時の構造タグです。本文ではありません。
=== SOURCE / TRANSLATION ===（推敲用）
  推敲に送る入力文の区切りです。原文・初回訳・人物対応表の順に並びます。

■ 言語別の辞書方式
中国語：書き込み式（本文に読みを写す）。英語・韓国語：対応表式（本文無改変で表を渡す）。
探し方も言語別で、中国語は全文探し、韓国語は文節先頭、英語は単語境界です。
短い名前の必須判定は緩め（警告止まり）で、1欠けでの章落ちを防ぎます。

■ 訳文検査の順序
栞 → 空白 → 辞書 → 残留（他言語混入） → 行数 → 量比 → 日本語らしさの順です。
不合格は次の指示文で再送し、全滅時のみ確定失敗にします。

■ 推敲（最終磨き）
共通タブでon/offと指示文（空欄＝既定文）を変更できます。
原文＋初回訳文＋人物対応表を渡し、磨き文が検査に通れば採用、不合格・制限時は初回訳文を使います。
モデルは翻訳と同一経路で、追加の鍵設定は要りません。試行は1回固定です。

■ 空欄の約束
指示文1〜7番・辞書指示文・推敲指示文はすべて「空欄または初期値に戻す＝既定文」です。
"""

@Composable
internal fun V2HelpDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppColors.surfaceDark,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "v2翻訳の仕組み",
                    color = AppColors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = V2_HELP_TEXT,
                        color = AppColors.textPrimary,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("閉じる", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}
