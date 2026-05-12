package net.rpcsx.performance

import android.os.Build
import android.os.Process
import android.util.Log
import net.rpcsx.RPCSX
import net.rpcsx.utils.GeneralSettings

object ThorPerformanceProfile {
    private const val TAG = "ThorPerformanceProfile"
    private const val PROFILE_VERSION = 5
    private const val PROFILE_PREF = "thor_compile_profile_version"
    private const val PERFORMANCE_CORE_MASK = 0xF8

    data class ApplyResult(
        val applied: Boolean,
        val affinityApplied: Boolean,
        val changedSettings: List<String>,
        val failedSettings: List<String>
    )

    fun isThorTarget(): Boolean {
        val deviceText = listOf(
            Build.MODEL,
            Build.DEVICE,
            Build.BOARD,
            Build.HARDWARE,
            Build.PRODUCT,
            Build.MANUFACTURER
        ).joinToString(" ").lowercase()

        return "ayn" in deviceText || "thor" in deviceText || "kalama" in deviceText
    }

    fun applyStartupDefaults(force: Boolean = false): ApplyResult {
        if (!isThorTarget()) {
            return ApplyResult(
                applied = false,
                affinityApplied = false,
                changedSettings = emptyList(),
                failedSettings = emptyList()
            )
        }

        val affinityApplied = applyRuntimeAffinity()
        val alreadyApplied = (GeneralSettings[PROFILE_PREF] as? Int ?: 0) >= PROFILE_VERSION
        if (alreadyApplied && !force) {
            return ApplyResult(
                applied = false,
                affinityApplied = affinityApplied,
                changedSettings = emptyList(),
                failedSettings = emptyList()
            )
        }

        val changed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        setSetting("Core@@Max LLVM Compile Threads", "2", "Max LLVM Compile Threads", changed, failed)
        setSetting("Core@@LLVM Precompilation", "false", "LLVM Precompilation", changed, failed)
        setSetting("Core@@SPU Cache", "true", "SPU Cache", changed, failed)
        setSetting("Core@@Use LLVM CPU", "\"cortex-a78\"", "Use LLVM CPU", changed, failed)
        setSetting("Video@@Disable On-Disk Shader Cache", "false", "On-Disk Shader Cache", changed, failed)

        if (failed.isEmpty()) {
            GeneralSettings[PROFILE_PREF] = PROFILE_VERSION
            GeneralSettings.sync()
        }

        Log.i(
            TAG,
            "Thor compile profile applied=${failed.isEmpty()} affinity=$affinityApplied changed=$changed failed=$failed"
        )

        return ApplyResult(
            applied = failed.isEmpty(),
            affinityApplied = affinityApplied,
            changedSettings = changed,
            failedSettings = failed
        )
    }

    fun forceApplyCompileDefaults(): ApplyResult = applyStartupDefaults(force = true)

    private fun setSetting(
        path: String,
        value: String,
        label: String,
        changed: MutableList<String>,
        failed: MutableList<String>
    ) {
        val ok = runCatching {
            RPCSX.instance.settingsSet(path, value)
        }.getOrElse {
            Log.w(TAG, "Failed to set $path", it)
            false
        }

        if (ok) {
            changed += label
        } else {
            failed += label
        }
    }

    fun applyRuntimeAffinity(): Boolean {
        if (!isThorTarget()) {
            return false
        }

        val result = runCatching {
            RPCSX.instance.setProcessAffinityMask(PERFORMANCE_CORE_MASK)
        }.getOrElse {
            Log.w(TAG, "Could not set Thor performance-core affinity", it)
            false
        }

        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
        }.onFailure {
            Log.w(TAG, "Could not raise thread priority", it)
        }

        return result
    }
}
