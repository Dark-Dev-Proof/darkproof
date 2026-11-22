package it.blackout.mesh

import android.content.Context
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Opens Google's QR scanner (no camera permission needed) and passes on the group name and code of a DarkProof invite, and the admins it names. */
fun scanInvite(c: Context, got: (SavedGroup) -> Unit) {
    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    runCatching {
        GmsBarcodeScanning.getClient(c, opts).startScan()
            .addOnSuccessListener { b ->
                val invite = b.rawValue?.let(Groups::readInvite)
                if (invite != null) got(invite) else Toast.makeText(c, "This QR code isn't a DarkProof invite.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { Toast.makeText(c, "The scanner isn't ready on this phone. Point the camera app at the code, or type it.", Toast.LENGTH_LONG).show() }
    }.onFailure { Toast.makeText(c, "The scanner isn't ready on this phone. Point the camera app at the code, or type it.", Toast.LENGTH_LONG).show() }
}

/** Opens the same scanner for a person code (Check): passes on whatever text the QR code holds, for [PersonCode] to judge. */
fun scanCode(c: Context, got: (String) -> Unit) {
    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    val notReady = { Toast.makeText(c, "The scanner isn't ready on this phone. Compare the emoji instead.", Toast.LENGTH_LONG).show() }
    runCatching { GmsBarcodeScanning.getClient(c, opts).startScan().addOnSuccessListener { b -> got(b.rawValue ?: "") }.addOnFailureListener { notReady() } }.onFailure { notReady() }
}
