/*
 * samples/swinginterop — exercises the javafx.swing interop module after the
 * jdk.unsupported.desktop removal. Deliberately standalone (not part of the
 * ensemble) so it stays buildable in isolation.
 */
module openjfx.samples.swinginterop {
    requires javafx.controls;   // pulls in javafx.graphics + javafx.base
    requires javafx.swing;      // JFXPanel (FX-in-Swing) + SwingNode (Swing-in-FX)
    requires java.desktop;      // Swing/AWT (JFrame, JButton, ...)
}
