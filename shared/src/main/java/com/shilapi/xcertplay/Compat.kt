package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import java.io.File

/** getNoBackupFilesDir exists from API 21; older units fall back to the same on-disk path. */
fun Context.compatNoBackupFilesDir(): File =
    if (Build.VERSION.SDK_INT >= 21) noBackupFilesDir else File(applicationInfo.dataDir, "no_backup")
