package com.shilapi.xcertplay.transport

/**
 * Largest single bulk transfer the pre-Android-P usbfs accepts. `drivers/usb/core/dev.c` caps a
 * USBDEVFS_BULK buffer at 16 KiB and fails the submit outright above it, so `bulkTransfer()` on
 * Android 4.x returns -1 rather than a short read — which a caller cannot tell apart from a
 * timeout. This is the single definition of that bound: [UsbReadQueuePolicy] caps the first
 * async submission at it too.
 */
internal const val USBFS_BULK_URB_CEILING_BYTES = 16 * 1024
