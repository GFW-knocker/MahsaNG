package com.v2ray.ang.ui

import android.Manifest
import android.content.*
import com.v2ray.ang.R
import com.v2ray.ang.util.AngConfigManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.v2ray.ang.extension.toast

class ScScannerActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_none)
        importQRcode()
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanQRCode.launch(Intent(this, ScannerActivity::class.java))
        } else {
            toast(R.string.toast_permission_denied)
        }
    }

    fun importQRcode(): Boolean {
        cameraPermission.launch(Manifest.permission.CAMERA)
        return true
    }

    private val scanQRCode = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val count = AngConfigManager.importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"), "", false)
            if (count > 0) {
                toast(R.string.toast_success)
            } else {
                toast(R.string.toast_failure)
            }
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
