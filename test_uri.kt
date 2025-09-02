import android.net.Uri

fun main() {
    val testUris = listOf(
        "https://app.example.com",
        "http://localhost:3000", 
        "https://app.com/events",
        "app://custom-scheme",
        "",
        "not-a-uri",
        "ftp://unsupported.com"
    )
    
    testUris.forEach { uriString ->
        try {
            val uri = Uri.parse(uriString)
            println("URI: '$uriString' -> scheme: '${uri.scheme}', host: '${uri.host}', authority: '${uri.authority}'")
        } catch (e: Exception) {
            println("URI: '$uriString' -> ERROR: $e")
        }
    }
}
