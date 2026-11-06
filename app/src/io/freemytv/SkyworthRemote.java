package io.freemytv;

/** The shortcut keys verified on the JVC G24's Skyworth firmware. Other remotes stay with the TV. */
final class SkyworthRemote {
    private SkyworthRemote() {}

    static boolean supported(String manufacturer, String brand, String model, int sdk) {
        return sdk == 30 && "JVC G24 TV".equals(model)
                && ("skyworth".equalsIgnoreCase(manufacturer) || "skyworth".equalsIgnoreCase(brand));
    }

    /**
     * Skyworth's own Google TV set (SWTV-22AE, SW6H, Google TV 14 tested): its Bluetooth remote's gear sends NOTIFICATION
     * (caught by the system first: the key service's filter sees it before), and its four colour buttons PROG_RED to
     * PROG_BLUE, which reach the app in front. By model name: other Skyworth remotes weren't checked.
     */
    static boolean googleTv(String manufacturer, String model) {
        return "skyworth".equalsIgnoreCase(manufacturer) && "SWTV-22AE".equals(model);
    }

    static boolean shortcut(int key, int scan) {
        // BUTTON_16 is shared with many app buttons: match only DailyShortcut's IR/Bluetooth scan codes.
        // BUTTON_14/240 is another DailyShortcut mapping in this firmware's global-key table.
        return (key == 203 && (scan == 383 || scan == 79)) || (key == 201 && scan == 240);
    }

    static boolean settings(int key) {
        // KEYCODE_NOTIFICATION: SystemUI otherwise requests Google TV's disabled notification panel.
        return key == 83;
    }
}
