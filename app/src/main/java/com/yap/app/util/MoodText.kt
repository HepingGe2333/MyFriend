package com.yap.app.util

import android.content.Context
import androidx.annotation.StringRes
import com.yap.app.R
import com.yap.app.domain.MoodLexicon

/**
 * 心情词表的「显示版」。
 *
 * 词表是固定的（[MoodLexicon]），所以常见的那些都能说成对方的语言；模型自己造的怪词原样显示——
 * 不猜、不硬翻。放在 `util/` 而不是某个 Composable 里，是因为聊天气泡下的工具痕迹（数据层写入）
 * 也要用同一套映射：不然同一个「雀跃」在心情胶囊里是英文、在动作痕迹里还是中文。
 */
object MoodText {

    /** 认出来的词给资源；认不出来（模型自造词）返回 null，调用方原样显示。 */
    @StringRes
    fun labelRes(label: String): Int? = when (MoodLexicon.normalize(label)) {
        "平静" -> R.string.mood_label_calm
        "放松" -> R.string.mood_label_relaxed
        "温柔" -> R.string.mood_label_gentle
        "雀跃" -> R.string.mood_label_elated
        "开心" -> R.string.mood_label_happy
        "兴奋" -> R.string.mood_label_excited
        "期待" -> R.string.mood_label_looking_forward
        "想念" -> R.string.mood_label_missing_you
        "有点累" -> R.string.mood_label_a_little_tired
        "疲惫" -> R.string.mood_label_worn_out
        "犯困" -> R.string.mood_label_sleepy
        "低落" -> R.string.mood_label_low
        "难过" -> R.string.mood_label_sad
        "心烦" -> R.string.mood_label_restless
        "不安" -> R.string.mood_label_uneasy
        "有点委屈" -> R.string.mood_label_a_little_hurt
        "愧疚" -> R.string.mood_label_guilty
        else -> null
    }

    /** 认识就翻，不认识就原样（模型可能自己造词）。 */
    fun label(context: Context, label: String): String {
        val word = MoodLexicon.normalize(label)
        return labelRes(word)?.let(context::getString) ?: word
    }
}
