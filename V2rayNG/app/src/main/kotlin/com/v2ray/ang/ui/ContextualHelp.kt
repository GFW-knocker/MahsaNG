package com.v2ray.ang.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.R

/** Offline help for all visible menus. Keep the map aligned with real menu IDs. */
internal object ContextualHelp {
    private data class HelpTopic(@StringRes val title: Int, @ArrayRes val sections: Int)

    private val topics = linkedMapOf(
        R.id.sub_setting to HelpTopic(R.string.title_sub_setting, R.array.help_subscriptions),
        R.id.settings to HelpTopic(R.string.title_settings, R.array.help_settings),
        R.id.user_asset_setting to HelpTopic(R.string.title_user_asset_setting, R.array.help_assets),
        R.id.logcat to HelpTopic(R.string.title_logcat, R.array.help_logs),
        R.id.filter_config to HelpTopic(R.string.title_filter_config, R.array.help_filter),
        R.id.service_restart to HelpTopic(R.string.title_service_restart, R.array.help_restart),
        R.id.del_all_config to HelpTopic(R.string.title_del_all_config, R.array.help_delete_all),
        R.id.del_duplicate_config to HelpTopic(R.string.title_del_duplicate_config, R.array.help_delete_duplicates),
        R.id.del_invalid_config to HelpTopic(R.string.title_del_invalid_config, R.array.help_delete_failed),
        R.id.export_all to HelpTopic(R.string.title_export_all, R.array.help_export),
        R.id.ping_all to HelpTopic(R.string.title_ping_all_server, R.array.help_tcp),
        R.id.real_ping_all to HelpTopic(R.string.title_real_ping_all_server, R.array.help_real_ping),
        R.id.sort_by_test_results to HelpTopic(R.string.title_sort_by_test_results, R.array.help_sort),
        R.id.sub_update to HelpTopic(R.string.title_sub_update, R.array.help_refresh),
        R.id.import_qrcode to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_clipboard to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_manually_vmess to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_manually_vless to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_manually_ss to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_manually_socks to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_manually_trojan to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_config_custom_clipboard to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_config_custom_local to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_config_custom_url to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
        R.id.import_config_custom_url_scan to HelpTopic(R.string.menu_item_add_config, R.array.help_import),
    )

    // One entry per distinct topic. Every active menu command maps to one of these.
    private val index = listOf(
        R.id.sub_setting, R.id.settings, R.id.user_asset_setting, R.id.logcat,
        R.id.import_qrcode, R.id.filter_config, R.id.service_restart,
        R.id.del_all_config, R.id.del_duplicate_config, R.id.del_invalid_config,
        R.id.export_all, R.id.ping_all, R.id.real_ping_all,
        R.id.sort_by_test_results, R.id.sub_update,
    )

    fun showIndex(context: Context) {
        val titles = index.map { context.getString(requireNotNull(topics[it]).title) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.help_choose_topic)
            .setItems(titles) { _, which -> show(context, index[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun show(context: Context, @IdRes topicId: Int) {
        val topic = topics[topicId] ?: return
        val scale = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * scale + 0.5f).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(32))
        }
        content.addView(TextView(context).apply {
            setText(topic.title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(0, 0, 0, dp(12))
        })
        context.resources.getStringArray(topic.sections).forEach { section ->
            content.addView(TextView(context).apply {
                text = section
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        BottomSheetDialog(context).apply {
            setContentView(ScrollView(context).apply { addView(content) })
            show()
        }
    }
}
