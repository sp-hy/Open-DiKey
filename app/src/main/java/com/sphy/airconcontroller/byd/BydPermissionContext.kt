package com.sphy.airconcontroller.byd

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

/**
 * Client-side bypass for BYD's `enforceCallingOrSelfPermission` checks on `BYDAUTO_*`
 * permissions. Signature-level SET permissions often cannot be `pm grant`ed; wrapping
 * the Context passed to `BYDAutoAcDevice.getInstance` is how other DiLink apps get SET
 * methods to run. Server-side IPC checks (if any) still apply.
 */
class BydPermissionContext(base: Context) : ContextWrapper(base) {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        if (isBydAuto(permission)) return PackageManager.PERMISSION_GRANTED
        return super.checkPermission(permission, pid, uid)
    }

    override fun checkCallingPermission(permission: String): Int {
        if (isBydAuto(permission)) return PackageManager.PERMISSION_GRANTED
        return super.checkCallingPermission(permission)
    }

    override fun checkCallingOrSelfPermission(permission: String): Int {
        if (isBydAuto(permission)) return PackageManager.PERMISSION_GRANTED
        return super.checkCallingOrSelfPermission(permission)
    }

    override fun checkSelfPermission(permission: String): Int {
        if (isBydAuto(permission)) return PackageManager.PERMISSION_GRANTED
        return super.checkSelfPermission(permission)
    }

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (isBydAuto(permission)) return
        super.enforcePermission(permission, pid, uid, message)
    }

    override fun enforceCallingPermission(permission: String, message: String?) {
        if (isBydAuto(permission)) return
        super.enforceCallingPermission(permission, message)
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (isBydAuto(permission)) return
        super.enforceCallingOrSelfPermission(permission, message)
    }

    private fun isBydAuto(permission: String): Boolean =
        permission.startsWith("android.permission.BYDAUTO_")
}
