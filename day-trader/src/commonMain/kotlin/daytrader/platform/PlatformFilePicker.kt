package daytrader.platform

expect object PlatformFilePicker {
    fun pickCsvFile(title: String = "Select symbol CSV"): String?
    fun readText(path: String): String?
}
