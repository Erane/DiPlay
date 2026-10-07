package com.shilapi.xcertplay.legacy

import android.os.Build

/**
 * Tells a non-technical owner their head unit's *real* Android version and API level.
 *
 * Car ROMs routinely rewrite `Build.VERSION.RELEASE` (and the settings screen) to a marketing
 * number the platform never was — this project's target unit claims "6.1.1" while the framework
 * API level is 19 (Android 4.4.2). `SDK_INT` is the value Android itself uses to gate every API, so
 * it, corroborated by the version baked into `Build.FINGERPRINT` and `Build.ID`, is the trustworthy
 * signal. This object compares the advertised version against the detected one and explains the
 * mismatch in plain language.
 *
 * Every field read here exists since API 1, so the check itself runs on 4.3/4.4.
 */
object AndroidVersionProbe {

    /** Marketing version for a runtime API level; "API N" fallback for unmapped/preview levels. */
    fun androidVersionForApi(sdk: Int): String = when (sdk) {
        in 1..7 -> "Android 1.x/2.x (API $sdk)"
        8 -> "Android 2.2 (Froyo)"
        9, 10 -> "Android 2.3 (Gingerbread)"
        11, 12, 13 -> "Android 3.x/4.x (Honeycomb, API $sdk)"
        14 -> "Android 4.0 (Ice Cream Sandwich)"
        15 -> "Android 4.0.3/4.0.4 (Ice Cream Sandwich MR1)"
        16 -> "Android 4.1 (Jelly Bean)"
        17 -> "Android 4.2 (Jelly Bean MR1)"
        18 -> "Android 4.3 (Jelly Bean MR2)"
        19 -> "Android 4.4 / 4.4.2 (KitKat)"
        20 -> "Android 4.4W (KitKat Wear)"
        21 -> "Android 5.0 (Lollipop)"
        22 -> "Android 5.1 (Lollipop MR1)"
        23 -> "Android 6.0 (Marshmallow)"
        24 -> "Android 7.0 (Nougat)"
        25 -> "Android 7.1 (Nougat MR1)"
        26 -> "Android 8.0 (Oreo)"
        27 -> "Android 8.1 (Oreo MR1)"
        28 -> "Android 9 (Pie)"
        29 -> "Android 10 (Q)"
        30 -> "Android 11 (R)"
        31 -> "Android 12 (S)"
        32 -> "Android 12L"
        33 -> "Android 13 (Tiramisu)"
        34 -> "Android 14 (Upside-down Cake)"
        35 -> "Android 15 (Vanilla Ice Cream)"
        36 -> "Android 16"
        else -> "Android (API $sdk, 未知对应版本)"
    }

    /** Version segment inside the platform fingerprint: `...device:4.4.2/KOT49H/...:eng/keys`. */
    fun fingerprintAndroidVersion(fingerprint: String): String {
        val afterColon = fingerprint.substringAfter(':', "")
        return afterColon.substringBefore('/', "").trim()
    }

    /** Major component, e.g. "4.4.2" -> "4", "6.1.1" -> "6". Empty when not numeric. */
    private fun major(version: String): String =
        version.trim().takeWhile { it.isDigit() }.substringBefore('.')

    private fun isAdvertisedMismatching(detectedSdk: Int, nominal: String, fingerprint: String): Boolean {
        val detectedMajor = major(androidVersionForApi(detectedSdk))
        val nominalMajor = major(nominal)
        if (detectedMajor.isNotEmpty() && nominalMajor.isNotEmpty() && detectedMajor != nominalMajor) return true
        val fpMajor = major(fingerprintAndroidVersion(fingerprint))
        return detectedMajor.isNotEmpty() && fpMajor.isNotEmpty() && detectedMajor != fpMajor
    }

    /** Multi-line, plain-language block for a layperson, safe to embed on-screen or in a log. */
    fun report(): String {
        val nominal = Build.VERSION.RELEASE ?: "?"
        val sdk = Build.VERSION.SDK_INT
        val detected = androidVersionForApi(sdk)
        val fpVersion = fingerprintAndroidVersion(Build.FINGERPRINT ?: "")
        val codename = Build.VERSION.CODENAME?.takeIf { it.isNotBlank() && it != "REL" }
        val mismatch = isAdvertisedMismatching(sdk, nominal, Build.FINGERPRINT ?: "")
        return buildString {
            appendLine("==== 安卓版本真伪检测 ====")
            appendLine("标称版本（设置页/ROM 显示的，可能造假）: $nominal${codename?.let { " ($it)" } ?: ""}")
            appendLine("检测推定版本（以运行时 API 为准，可靠）: $detected")
            appendLine("运行时 API 等级: $sdk")
            if (fpVersion.isNotEmpty()) {
                appendLine("旁证 · 系统指纹内嵌版本: $fpVersion")
            }
            appendLine("旁证 · Build.ID: ${Build.ID ?: "?"}")
            appendLine()
            if (mismatch) {
                appendLine("结论: ⚠ 标称与检测不一致 —— 这台车机真实版本约为「$detected (API $sdk)」。")
                appendLine("      设置页显示的「Android $nominal」是 ROM 伪装/改名的版本号，并非真实系统。")
                appendLine("      判断以运行时 API=$sdk 为准：应用的兼容性与系统能力都按 $detected 处理。")
            } else {
                appendLine("结论: 标称与检测一致 —— 真实版本约为「$detected (API $sdk)」。")
            }
        }
    }

    /** One short sentence for the home screen; points to the full check in 诊断信息. */
    fun summaryLine(): String {
        val sdk = Build.VERSION.SDK_INT
        val detected = androidVersionForApi(sdk)
        val mismatch = isAdvertisedMismatching(sdk, Build.VERSION.RELEASE ?: "?", Build.FINGERPRINT ?: "")
        return if (mismatch) {
            "⚠ 版本异常：标称 Android ${Build.VERSION.RELEASE}，检测实为 $detected (API $sdk)。点【诊断信息】看详情。"
        } else {
            "系统版本：$detected (API $sdk)，标称与检测一致。"
        }
    }
}
