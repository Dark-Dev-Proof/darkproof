package it.blackout.mesh

/** How to reach the developer (Settings, "Help and safety"); app stores ask apps with public posts to show this. */
object Contact {
    const val EMAIL = "support@darkproof.app"
    const val MAIL = "mailto:support@darkproof.app?subject=DarkProof%20report"
    const val PRIVACY = "https://darkproof.app/privacy"
    const val TERMS = "https://darkproof.app/terms"
    fun open(c: android.content.Context, uri: String) {
        runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))) }
            .onFailure { android.widget.Toast.makeText(c, if (uri.startsWith("mailto")) "Write to $EMAIL" else uri, android.widget.Toast.LENGTH_LONG).show() }
    }
}
