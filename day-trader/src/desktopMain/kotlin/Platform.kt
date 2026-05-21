actual object Platform {
    actual val name: String = "Desktop"
    actual val osVersion: String = System.getProperty("os.name") + " " + System.getProperty("os.version")
}

