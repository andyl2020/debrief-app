package com.andyluu.debrief.share

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareUploadWorkerTest {
    @Test
    fun foregroundInfoUsesDataSyncServiceType() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val info = shareUploadForegroundInfo(context, "Preparing private share", 0, 1)

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }
}
