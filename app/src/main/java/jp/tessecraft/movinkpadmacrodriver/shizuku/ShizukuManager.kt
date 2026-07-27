package jp.tessecraft.movinkpadmacrodriver.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {

    const val PERMISSION_REQUEST_CODE = 1001

    enum class Status {
        BINDER_UNAVAILABLE,
        PERMISSION_REQUIRED,
        PERMISSION_DENIED,
        READY
    }

    fun getStatus(): Status {
        if (!Shizuku.pingBinder()) {
            return Status.BINDER_UNAVAILABLE
        }

        return when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                Status.READY

            Shizuku.shouldShowRequestPermissionRationale() ->
                Status.PERMISSION_DENIED

            else ->
                Status.PERMISSION_REQUIRED
        }
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    fun getServerUid(): Int? {
        if (getStatus() != Status.READY) {
            return null
        }

        return runCatching {
            Shizuku.getUid()
        }.getOrNull()
    }
}