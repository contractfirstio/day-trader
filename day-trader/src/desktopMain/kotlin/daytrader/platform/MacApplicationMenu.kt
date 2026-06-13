package daytrader.platform

import java.awt.Desktop
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

object MacApplicationMenu {
    private var applicationName: String = "Day Trader"

    var onAbout: () -> Unit = { showDefaultAboutDialog(applicationName) }
    var onQuitRequest: () -> Unit = {}
    var onOpenIbSettings: () -> Unit = {}
    var onChangeBrokerMode: () -> Unit = {}

    fun install(applicationName: String) {
        if (!isMacOs()) return

        this.applicationName = applicationName
        onAbout = { showDefaultAboutDialog(applicationName) }
        System.setProperty("apple.laf.useScreenMenuBar", "true")

        SwingUtilities.invokeAndWait {
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.APP_MENU_BAR)) return@invokeAndWait

            desktop.setAboutHandler { onAbout() }

            // Empty title: macOS already labels the app menu from apple.awt.application.name.
            val appMenu = JMenu("").apply {
                add(
                    JMenuItem("About $applicationName").apply {
                        addActionListener { onAbout() }
                    }
                )
                addSeparator()
                add(
                    JMenuItem("Quit $applicationName").apply {
                        addActionListener { onQuitRequest() }
                    }
                )
            }
            val settingsMenu = JMenu("Settings").apply {
                add(
                    JMenuItem("Change Broker Mode…").apply {
                        addActionListener { onChangeBrokerMode() }
                    }
                )
                addSeparator()
                add(
                    JMenuItem("Interactive Brokers…").apply {
                        addActionListener { onOpenIbSettings() }
                    }
                )
            }

            desktop.setDefaultMenuBar(JMenuBar().apply {
                add(appMenu)
                add(settingsMenu)
            })
        }
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

    private fun showDefaultAboutDialog(applicationName: String) {
        JOptionPane.showMessageDialog(
            null,
            "$applicationName\nVersion 1.0.0",
            "About $applicationName",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
}
