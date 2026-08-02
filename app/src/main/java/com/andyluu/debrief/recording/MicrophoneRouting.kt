package com.andyluu.debrief.recording

import android.media.AudioDeviceInfo

/**
 * Higher values win when more than one physical input is available. Only device
 * types that can reasonably represent a user-connected microphone are eligible;
 * telephony, remote-submix, and tuner sources are deliberately excluded.
 */
internal fun externalMicrophonePriority(deviceType: Int): Int = when (deviceType) {
    AudioDeviceInfo.TYPE_USB_HEADSET -> 100
    AudioDeviceInfo.TYPE_USB_DEVICE -> 95
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> 90
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> 85
    AudioDeviceInfo.TYPE_BLE_HEADSET -> 80
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 75
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> 70
    AudioDeviceInfo.TYPE_LINE_ANALOG -> 65
    AudioDeviceInfo.TYPE_AUX_LINE -> 60
    AudioDeviceInfo.TYPE_DOCK,
    AudioDeviceInfo.TYPE_DOCK_ANALOG,
    AudioDeviceInfo.TYPE_BUS -> 50
    else -> 0
}

internal fun isExternalMicrophoneType(deviceType: Int): Boolean =
    externalMicrophonePriority(deviceType) > 0

internal fun microphoneRouteForDeviceType(deviceType: Int?): RecordingInputRoute =
    if (deviceType != null && isExternalMicrophoneType(deviceType)) {
        RecordingInputRoute.EXTERNAL
    } else {
        RecordingInputRoute.INTERNAL
    }

internal fun microphoneDisplayName(
    route: RecordingInputRoute,
    productName: CharSequence?,
): String {
    val product = productName?.toString()?.trim().orEmpty()
    return when {
        route == RecordingInputRoute.INTERNAL -> "Phone microphone"
        product.isBlank() || product.equals("unknown", ignoreCase = true) -> "External microphone"
        else -> product.take(80)
    }
}
