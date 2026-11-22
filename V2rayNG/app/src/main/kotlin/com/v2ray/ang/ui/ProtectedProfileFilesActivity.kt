package com.v2ray.ang.ui

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.PasswordProtectedLinkArchive
import com.v2ray.ang.util.ProfileLinkBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Portable, *link-only* password-protected files selected using the Android
 * Storage Access Framework. No clipboard, unencrypted file, automatic backup,
 * subscription import, in-app static passphrase storage, or network access.
 */
class ProtectedProfileFilesActivity : BaseActivity() {
    private lateinit var status: TextView

    private val createFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) showExportPasswordDialog(uri) }

    private val openFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) showImportPasswordDialog(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.protected_profile_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density + 0.5f).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        content.addView(TextView(this).apply {
            setText(R.string.protected_profile_scope)
            textSize = 15f
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        })
        content.addView(Button(this).apply {
            setText(R.string.protected_profile_export)
            setOnClickListener { createFile.launch("mahsa-profile-links.mhsaenc") }
        })
        content.addView(Button(this).apply {
            setText(R.string.protected_profile_import)
            setOnClickListener { openFile.launch(arrayOf("application/octet-stream", "*/*")) }
        })
        status = TextView(this).apply {
            setText(R.string.protected_profile_ready)
            setTextIsSelectable(true)
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    /** Avoid immutable intermediate password Strings; never retain across a picker. */
    private fun passwordChars(field: EditText): CharArray =
        CharArray(field.length()) { i -> field.text[i] }

    private fun passwordField(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setHint(R.string.protected_profile_password)
        maxLines = 1
    }

    private fun deleteUnusedExportDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, uri) }
    }

    private fun showExportPasswordDialog(uri: Uri) {
        val password = passwordField()
        val again = passwordField().apply {
            setHint(R.string.protected_profile_confirm_password)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(password)
            addView(again)
        }
        var exportStarted = false
        AlertDialog.Builder(this)
            .setTitle(R.string.protected_profile_export)
            .setMessage(R.string.protected_profile_export_warning)
            .setView(layout)
            .setPositiveButton(R.string.protected_profile_export) { _, _ ->
                val pass = passwordChars(password)
                val confirm = passwordChars(again)
                password.text.clear()
                again.text.clear()
                val valid = pass.size in PasswordProtectedLinkArchive.MIN_PASSWORD_LENGTH..256 &&
                    pass.contentEquals(confirm)
                confirm.fill('\u0000')
                if (!valid) {
                    pass.fill('\u0000')
                    deleteUnusedExportDocument(uri)
                    status.setText(R.string.protected_profile_password_invalid)
                } else {
                    exportStarted = true
                    lifecycleScope.launch {
                        status.setText(R.string.protected_profile_working)
                        var exportSucceeded = false
                        val result = try {
                            withContext(Dispatchers.IO) {
                                try {
                                    val export = AngConfigManager.exportPortableProfileLinks(
                                        MmkvManager.decodeServerList()
                                    ) ?: return@withContext null
                                    val plain = export.contents.toByteArray(StandardCharsets.UTF_8)
                                    val encrypted = try {
                                        PasswordProtectedLinkArchive.encrypt(plain, pass)
                                    } finally {
                                        plain.fill(0)
                                    }
                                    val written = try {
                                        contentResolver.openOutputStream(uri, "wt")?.use { output ->
                                            output.write(encrypted)
                                            output.flush()
                                            export.count to export.skipped
                                        }
                                    } finally {
                                        encrypted.fill(0)
                                    }
                                    if (written != null) {
                                        exportSucceeded = true
                                    }
                                    written
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        } finally {
                            pass.fill('\u0000')
                            if (!exportSucceeded) {
                                withContext(NonCancellable + Dispatchers.IO) {
                                    deleteUnusedExportDocument(uri)
                                }
                            }
                        }
                        if (result == null) {
                            status.text = getString(R.string.protected_profile_export_failed)
                        } else {
                            status.text = getString(
                                R.string.protected_profile_export_summary,
                                result.first,
                                result.second,
                            )
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                deleteUnusedExportDocument(uri)
            }
            .setOnCancelListener {
                if (!exportStarted) deleteUnusedExportDocument(uri)
            }
            .show()
    }

    private fun showImportPasswordDialog(uri: Uri) {
        val password = passwordField()
        AlertDialog.Builder(this)
            .setTitle(R.string.protected_profile_import)
            .setMessage(R.string.protected_profile_import_warning)
            .setView(password)
            .setPositiveButton(R.string.protected_profile_import) { _, _ ->
                val pass = passwordChars(password)
                password.text.clear()
                if (pass.size !in PasswordProtectedLinkArchive.MIN_PASSWORD_LENGTH..256) {
                    pass.fill('\u0000')
                    status.setText(R.string.protected_profile_password_invalid)
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    status.setText(R.string.protected_profile_working)
                    val outcome = withContext(Dispatchers.IO) {
                        try {
                            val archive = readBoundedArchive(uri) ?: return@withContext null
                            val bytes = PasswordProtectedLinkArchive.decrypt(archive, pass)
                                ?: return@withContext null
                            try {
                                // Strict UTF-8, complete allowlisted link parsing BEFORE
                                // any existing importer is allowed to alter MMKV.
                                val text = StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(bytes)).toString()
                                val valid = ProfileLinkBundle.parse(text)
                                    ?: return@withContext null
                                val links = valid.joinToString("\n")
                                AngConfigManager.importBatchConfig(links, "", true) to valid.size
                            } finally {
                                bytes.fill(0)
                            }
                        } catch (_: Exception) {
                            null
                        } finally {
                            pass.fill('\u0000')
                        }
                    }
                    status.text = if (outcome == null)
                        getString(R.string.protected_profile_import_failed)
                    else getString(R.string.protected_profile_import_summary,
                        outcome.first, outcome.second)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Fail closed on oversized input before decrypting or parsing a single profile. */
    private fun readBoundedArchive(uri: Uri): ByteArray? =
        contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (output.size().toLong() + n > PasswordProtectedLinkArchive.MAX_ARCHIVE_BYTES)
                    return@use null
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
