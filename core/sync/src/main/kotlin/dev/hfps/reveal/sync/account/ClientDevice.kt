package dev.hfps.reveal.sync.account

/**
 * How this installation describes itself to hoard-server (Hoard ADR 0017 "Model"): [app] is the app id with its build
 * variant (`reveal`, `reveal-debug`, `reveal-bench`: variants are distinct apps of one phone), [manufacturer] and
 * [model] are `Build.MANUFACTURER` / `Build.MODEL` (at most 64 characters; for matching and display only).
 */
data class ClientDevice(val app: String, val manufacturer: String?, val model: String?) {
    companion object {
        const val APP = "reveal"
        private const val MAX_APP = 32
        private const val MAX_MODEL = 64
        private val APP_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

        /**
         * The app id of package [packageName]: `dev.hfps.reveal` is the release (`reveal`), any suffix is the build
         * variant (`dev.hfps.reveal.debug` -> `reveal-debug`). Anything unexpected falls back to `reveal`.
         */
        fun appId(packageName: String): String {
            val variant = packageName.removePrefix("dev.hfps.reveal").trim('.').lowercase()
                .replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val id = if (variant.isEmpty()) APP else "$APP-$variant"
            return id.takeIf { it.length <= MAX_APP && APP_PATTERN.matches(it) } ?: APP
        }

        fun of(packageName: String, manufacturer: String?, model: String?) = ClientDevice(
            app = appId(packageName),
            manufacturer = manufacturer?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_MODEL),
            model = model?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_MODEL),
        )
    }
}
