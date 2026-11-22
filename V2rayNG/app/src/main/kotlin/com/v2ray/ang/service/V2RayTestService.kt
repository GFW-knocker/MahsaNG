package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG_CANCEL
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG_SUCCESS
import com.v2ray.ang.dto.ConfigTestRequest
import com.v2ray.ang.dto.ConfigTestResult
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.SpeedtestUtil
import com.v2ray.ang.util.Utils
import go.Seq
import kotlinx.coroutines.*
import libv2ray.Libv2ray
import java.util.concurrent.Executors

class V2RayTestService : Service() {
    private val dispatcher = Executors.newFixedThreadPool(10).asCoroutineDispatcher()
    private val realTestScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var currentRunId: String? = null

    override fun onCreate() {
        super.onCreate()
        Seq.setContext(this)
        Libv2ray.initV2Env(Utils.userAssetPath(this), "")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getIntExtra("key", 0)) {
            MSG_MEASURE_CONFIG_CANCEL -> {
                // The UI sends this token before submitting the next run's requests.
                currentRunId = intent.getSerializableExtra("content") as? String
                realTestScope.coroutineContext[Job]?.cancelChildren()
            }
            MSG_MEASURE_CONFIG -> {
                val request = intent.getSerializableExtra("content") as? ConfigTestRequest
                    ?: return START_NOT_STICKY
                if (request.runId != currentRunId) return START_NOT_STICKY
                realTestScope.launch {
                    // JNI may not be physically cancellable; never broadcast its late result.
                    val delay = SpeedtestUtil.realPing(request.config)
                    if (isActive && currentRunId == request.runId) {
                        MessageUtil.sendMsg2UI(
                            this@V2RayTestService,
                            MSG_MEASURE_CONFIG_SUCCESS,
                            ConfigTestResult(request.guid, delay, request.runId, request.profileRevision),
                        )
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentRunId = null
        realTestScope.cancel()
        dispatcher.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
