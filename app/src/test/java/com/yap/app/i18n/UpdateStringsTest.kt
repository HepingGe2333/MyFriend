package com.yap.app.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.15 更新渠道的界面文案：中英两边都要有，且英文不能是「没翻的中文」。
 *
 * `StringResourceParityTest` 已经保证 key 集合与占位符一致；这里补的是**这一组新文案**
 * 的显式清单——加一条中文却忘了英文时，报错会直接点名是哪一条，
 * 而不是让 review 去两个大 XML 里对。
 */
class UpdateStringsTest {

    private val updateKeys = listOf(
        "settings_section_update",
        "settings_update_check_title",
        "settings_update_desc",
        "settings_update_checking",
        "settings_update_up_to_date",
        "settings_update_available",
        "settings_update_skipped_prerelease",
        "settings_update_unavailable",
        "settings_update_failed",
        "settings_update_auto_title",
        "settings_update_auto_desc",
        "settings_update_prerelease_title",
        "settings_update_prerelease_desc",
        "update_dialog_title",
        "update_dialog_prerelease",
        "update_dialog_no_notes",
        "update_dialog_later",
        "update_dialog_download"
    )

    /** 备份版本信息的文案（导入预览里的「格式 v7 · schema v19 · 校验和已通过」）。 */
    private val backupMetadataKeys = listOf(
        "backup_dialog_format",
        "backup_dialog_format_no_schema",
        "backup_dialog_checksum_ok",
        "backup_dialog_checksum_missing",
        "backup_dialog_checksum_foreign",
        "backup_fail_checksum"
    )

    @Test
    fun `更新与备份版本的新文案中英都在`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        (updateKeys + backupMetadataKeys).forEach { key ->
            assertTrue("values/ 缺少 $key", zh.containsKey(key))
            assertTrue("values-en/ 缺少 $key", en.containsKey(key))
        }
    }

    @Test
    fun `英文文案是真的英文 不是没翻的中文`() {
        val zh = TestResources.strings("values")
        val en = TestResources.strings("values-en")
        (updateKeys + backupMetadataKeys).forEach { key ->
            val zhText = zh.getValue(key)
            val enText = en.getValue(key)
            assertTrue("$key 的中文是空的", zhText.isNotBlank())
            assertTrue("$key 的英文是空的", enText.isNotBlank())
            assertFalse("$key 的英文里还有中文：$enText", CJK.containsMatchIn(enText))
            assertFalse("$key 的英文看起来是中文原文", enText == zhText)
        }
    }

    private companion object {
        val CJK = Regex("[\\u3000-\\u303f\\uff00-\\uffef\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    }
}
