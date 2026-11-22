package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.system.Os
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.*
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivitySubSettingBinding
import com.v2ray.ang.databinding.ItemRecyclerUserAssetBinding
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.AppFetchFileDownloader
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.SafeAssetFilename
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.text.DateFormat
import java.util.*

class UserAssetActivity : BaseActivity() {
    companion object {
        const val EXTRA_VPN_RUNNING = "extra_vpn_running"
    }

    private lateinit var binding: ActivitySubSettingBinding
    @Volatile private var vpnRunning: Boolean = false
    private var vpnReceiverRegistered: Boolean = false

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    vpnRunning = true
                }
                AppConfig.MSG_STATE_NOT_RUNNING,
                AppConfig.MSG_STATE_START_FAILURE,
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    vpnRunning = false
                }
            }
        }
    }

    val extDir by lazy { File(Utils.userAssetPath(this)) }
    val geofiles = arrayOf("geosite.dat", "geoip.dat")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubSettingBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        title = getString(R.string.title_user_asset_setting)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        vpnRunning = intent.getBooleanExtra(EXTRA_VPN_RUNNING, false)
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }
        vpnReceiverRegistered = true

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = UserAssetAdapter()
    }

    override fun onResume() {
        super.onResume()
        // Query live service state so route choice reflects changes while this screen stays open.
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onDestroy() {
        if (vpnReceiverRegistered) {
            runCatching { unregisterReceiver(vpnStateReceiver) }
            vpnReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_asset, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.add_file -> {
            showFileChooser()
            true
        }

        R.id.download_file -> {
            downloadGeoFiles()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun showFileChooser() {
        // The document provider grants access to the selected URI.
        // This also permits selecting non-image geo assets on Android 13+.
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            chooseFile.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } catch (ex: android.content.ActivityNotFoundException) {
            toast(R.string.toast_require_file_manager)
        }
    }

    private val chooseFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val uri = it.data?.data
            if (it.resultCode == RESULT_OK && uri != null) {
                lifecycleScope.launch {
                    val copied = withContext(Dispatchers.IO) {
                        runCatching { copyFile(uri) }.isSuccess
                    }
                    if (copied) {
                        toast(R.string.toast_success)
                        binding.recyclerView.adapter?.notifyDataSetChanged()
                    } else {
                        toast(R.string.toast_asset_copy_failed)
                    }
                }
            }
        }

    private fun copyFile(uri: Uri): String {
        val fileName = SafeAssetFilename.validate(getCursorName(uri))
            ?: throw IOException("Document provider returned an invalid filename")
        val root = extDir.canonicalFile
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Cannot prepare asset directory")
        }
        val targetFile = File(root, fileName).canonicalFile
        if (targetFile.parentFile != root) {
            throw SecurityException("Document filename escapes asset directory")
        }

        // A failed stream or partial copy must never replace the existing asset.
        val staged = File.createTempFile(".asset-import-", ".tmp", root)
        try {
            var copiedBytes = 0L
            contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copiedBytes += read
                        if (copiedBytes > AppFetchFileDownloader.DEFAULT_MAX_BYTES) {
                            throw IOException("Selected document exceeds maximum asset size")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IOException("Unable to open selected document")
            if (copiedBytes <= 0L) throw IOException("Selected document is empty")
            // Same-directory rename atomically publishes the validated data.
            Os.rename(staged.absolutePath, targetFile.absolutePath)
        } finally {
            staged.delete()
        }
        return targetFile.path
    }

    private fun getCursorName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.let { cursor ->
            cursor.run {
                if (moveToFirst()) getString(getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                else null
            }.also { cursor.close() }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun downloadGeoFiles() {
        // Prefer a live local proxy; offline direct downloads require explicit consent.
        AppFetchRouteChooser.choose(this, { vpnRunning }) { route ->
            toast(R.string.msg_downloading_content)
            geofiles.forEach { name ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val saved = AppFetchFileDownloader.download(
                        url = URL(AppConfig.geoUrl + name),
                        target = File(extDir, name),
                        route = route,
                    )
                    withContext(Dispatchers.Main) {
                        if (saved) {
                            toast(getString(R.string.toast_success) + " " + name)
                            binding.recyclerView.adapter?.notifyDataSetChanged()
                        } else {
                            toast(getString(R.string.toast_failure) + " " + name)
                        }
                    }
                }
            }
        }
    }

    inner class UserAssetAdapter : RecyclerView.Adapter<UserAssetViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserAssetViewHolder {
            return UserAssetViewHolder(ItemRecyclerUserAssetBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: UserAssetViewHolder, position: Int) {
            val file = extDir.listFiles()?.getOrNull(position) ?: return
            holder.itemUserAssetBinding.assetName.text = file.name
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            holder.itemUserAssetBinding.assetProperties.text = "${file.length().toTrafficString()}  •  ${dateFormat.format(Date(file.lastModified()))}"
            if (file.name in geofiles) {
                holder.itemUserAssetBinding.layoutRemove.visibility = GONE
            } else {
                holder.itemUserAssetBinding.layoutRemove.visibility = VISIBLE
            }
            holder.itemUserAssetBinding.layoutRemove.setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                file.delete()
                binding.recyclerView.adapter?.notifyItemRemoved(adapterPosition)
            }
        }

        override fun getItemCount(): Int {
            return extDir.listFiles()?.size ?: 0
        }
    }

    class UserAssetViewHolder(val itemUserAssetBinding: ItemRecyclerUserAssetBinding) : RecyclerView.ViewHolder(itemUserAssetBinding.root)
}
