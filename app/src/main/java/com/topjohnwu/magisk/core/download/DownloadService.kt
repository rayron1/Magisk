package com.topjohnwu.magisk.core.download

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.net.toFile
import androidx.lifecycle.LifecycleOwner
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.ActivityTracker
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.intent
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.tasks.HideAPK
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.outputStream
import com.topjohnwu.magisk.ktx.copyAndClose
import com.topjohnwu.magisk.ktx.forEach
import com.topjohnwu.magisk.ktx.withStreams
import com.topjohnwu.magisk.ktx.writeTo
import com.topjohnwu.magisk.utils.APKInstall
import com.topjohnwu.magisk.view.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DownloadService : NotificationService() {

    private val job = Job()

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        intent.getParcelableExtra<Subject>(SUBJECT_KEY)?.let { download(it) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun download(subject: Subject) {
        update(subject.notifyId)
        val coroutineScope = CoroutineScope(job + Dispatchers.IO)
        coroutineScope.launch {
            try {
                val stream = service.fetchFile(subject.url).toProgressStream(subject)
                when (subject) {
                    is Subject.App -> handleApp(stream, subject)
                    is Subject.Module -> handleModule(stream, subject.file)
                }
                val activity = ActivityTracker.foreground
                if (activity != null && subject.autoLaunch) {
                    remove(subject.notifyId)
                    subject.pendingIntent(activity)?.send()
                } else {
                    notifyFinish(subject)
                }
                subject.postDownload?.invoke()
                if (!hasNotifications)
                    stopSelf()
            } catch (e: Exception) {
                Timber.e(e)
                notifyFail(subject)
            }
        }
    }

    private suspend fun handleApp(stream: InputStream, subject: Subject.App) {
        fun writeTee(output: OutputStream) {
            val uri =  MediaStoreUtils.getFile("${subject.title}.apk").uri
            val external = uri.outputStream()
            stream.copyAndClose(TeeOutputStream(external, output))
        }

        if (isRunningAsStub) {
            val updateApk = StubApk.update(this)
            try {
                // Download full APK to stub update path
                writeTee(updateApk.outputStream())

                if (Info.stub!!.version < subject.stub.versionCode) {
                    // Also upgrade stub
                    update(subject.notifyId) {
                        it.setProgress(0, 0, true)
                            .setContentTitle(getString(R.string.hide_app_title))
                            .setContentText("")
                    }

                    // Download
                    val apk = subject.file.toFile()
                    service.fetchFile(subject.stub.link).byteStream().writeTo(apk)

                    // Patch and install
                    val session = APKInstall.startSession(this)
                    session.openStream(this).use {
                        val label = applicationInfo.nonLocalizedLabel
                        if (!HideAPK.patch(this, apk, it, packageName, label)) {
                            throw IOException("HideAPK patch error")
                        }
                    }
                    apk.delete()
                    subject.intent = session.waitIntent()
                } else {
                    ActivityTracker.foreground?.let {
                        // Relaunch the process if we are foreground
                        StubApk.restartProcess(it)
                    } ?: run {
                        // Or else kill the current process after posting notification
                        subject.intent = Notifications.selfLaunchIntent(this)
                        subject.postDownload = { Runtime.getRuntime().exit(0) }
                    }
                    return
                }
            } catch (e: Exception) {
                // If any error occurred, do not let stub load the new APK
                updateApk.delete()
                throw e
            }
        } else {
            val session = APKInstall.startSession(this)
            writeTee(session.openStream(this))
            subject.intent = session.waitIntent()
        }
    }

    private fun handleModule(src: InputStream, file: Uri) {
        val input = ZipInputStream(src.buffered())
        val output = ZipOutputStream(file.outputStream().buffered())

        withStreams(input, output) { zin, zout ->
            zout.putNextEntry(ZipEntry("META-INF/"))
            zout.putNextEntry(ZipEntry("META-INF/com/"))
            zout.putNextEntry(ZipEntry("META-INF/com/google/"))
            zout.putNextEntry(ZipEntry("META-INF/com/google/android/"))
            zout.putNextEntry(ZipEntry("META-INF/com/google/android/update-binary"))
            assets.open("module_installer.sh").copyTo(zout)

            zout.putNextEntry(ZipEntry("META-INF/com/google/android/updater-script"))
            zout.write("#MAGISK\n".toByteArray())

            zin.forEach { entry ->
                val path = entry.name
                if (path.isNotEmpty() && !path.startsWith("META-INF")) {
                    zout.putNextEntry(ZipEntry(path))
                    if (!entry.isDirectory) {
                        zin.copyTo(zout)
                    }
                }
            }
        }
    }

    private class TeeOutputStream(
        private val o1: OutputStream,
        private val o2: OutputStream
    ) : OutputStream() {
        override fun write(b: Int) {
            o1.write(b)
            o2.write(b)
        }
        override fun write(b: ByteArray?, off: Int, len: Int) {
            o1.write(b, off, len)
            o2.write(b, off, len)
        }
        override fun close() {
            o1.close()
            o2.close()
        }
    }

    companion object {
        private const val SUBJECT_KEY = "subject"
        private const val REQUEST_CODE = 1

        fun observeProgress(owner: LifecycleOwner, callback: (Float, Subject) -> Unit) {
            progressBroadcast.value = null
            progressBroadcast.observe(owner) {
                val (progress, subject) = it ?: return@observe
                callback(progress, subject)
            }
        }

        private fun intent(context: Context, subject: Subject) =
            context.intent<DownloadService>().putExtra(SUBJECT_KEY, subject)

        @SuppressLint("InlinedApi")
        fun getPendingIntent(context: Context, subject: Subject): PendingIntent {
            val flag = FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT or FLAG_ONE_SHOT
            val intent = intent(context, subject)
            return if (Build.VERSION.SDK_INT >= 26) {
                getForegroundService(context, REQUEST_CODE, intent, flag)
            } else {
                getService(context, REQUEST_CODE, intent, flag)
            }
        }

        fun start(context: Context, subject: Subject) {
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(intent(app, subject))
            } else {
                app.startService(intent(app, subject))
            }
        }
    }
}
