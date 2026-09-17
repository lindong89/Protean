package com.ld.protean

import android.app.Application
import com.tencent.bugly.crashreport.CrashReport
import com.ld.protean.android.Bugly
import org.osmdroid.config.Configuration

class Protean : Application() {

    override fun onCreate() {
        super.onCreate()

        // osmdroid 初始化：设置瓦片缓存目录与 User-Agent（在线瓦片服务要求带 UA）
        Configuration.getInstance().apply {
            load(this@Protean, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            tileFileSystemCacheMaxBytes = 100L * 1024 * 1024
        }

        CrashReport.initCrashReport(applicationContext)

        CrashReport.setUserId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceModel(applicationContext, Bugly.getDeviceModel())
        CrashReport.setCollectPrivacyInfo(applicationContext, true)

        //CrashReport.setAllThreadStackEnable(applicationContext, true, true)
    }
}
