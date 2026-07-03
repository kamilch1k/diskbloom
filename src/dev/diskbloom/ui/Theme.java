package dev.diskbloom.ui;

import javafx.scene.Scene;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A modern flat dark theme, applied as a data-URI stylesheet so there's no
 * resource file to copy and no dependency to add. Restyles the default JavaFX
 * (Modena) controls — buttons, combos, fields, lists, checkboxes, scrollbars,
 * menus, dialogs — into something that doesn't look like Windows 7.
 */
public final class Theme {

    public static void apply(Scene scene) {
        String uri = "data:text/css;base64," + Base64.getEncoder().encodeToString(CSS.getBytes(StandardCharsets.UTF_8));
        scene.getStylesheets().add(uri);
    }

    private static final String CSS = """
        .root {
            -fx-font-family: 'Segoe UI', 'Inter', sans-serif;
            -fx-font-size: 12px;
            -fx-base: #1e1e1e;
            -fx-background: #1e1e1e;
            -fx-control-inner-background: #262627;
            -fx-accent: #4c8bf5;
            -fx-focus-color: #4c8bf5;
            -fx-faint-focus-color: rgba(76,139,245,0.15);
            -fx-text-fill: #e6e6e6;
        }

        .button, .toggle-button {
            -fx-background-color: #2e2e30;
            -fx-text-fill: #e6e6e6;
            -fx-background-radius: 6;
            -fx-border-radius: 6;
            -fx-border-color: #3a3a3d;
            -fx-border-width: 1;
            -fx-padding: 6 12 6 12;
            -fx-cursor: hand;
        }
        .button:hover, .toggle-button:hover { -fx-background-color: #3a3a3d; -fx-border-color: #4a4a4e; }
        .button:pressed { -fx-background-color: #292a2c; }
        .button:focused { -fx-border-color: -fx-accent; }
        .button:disabled { -fx-opacity: 0.45; }

        .button.accent { -fx-background-color: -fx-accent; -fx-text-fill: white; -fx-border-color: transparent; }
        .button.accent:hover { -fx-background-color: #3f79dd; }
        .button.danger { -fx-background-color: #7a2e2e; -fx-text-fill: #ffe1e1; -fx-border-color: #9a3b3b; }
        .button.danger:hover { -fx-background-color: #8a3636; }

        .combo-box {
            -fx-background-color: #262627;
            -fx-background-radius: 6;
            -fx-border-radius: 6;
            -fx-border-color: #3a3a3d;
            -fx-border-width: 1;
        }
        .combo-box:hover { -fx-border-color: #4a4a4e; }
        .combo-box .list-cell { -fx-text-fill: #e6e6e6; -fx-background-color: transparent; -fx-padding: 4 8; }
        .combo-box-popup .list-view { -fx-background-color: #262627; -fx-border-color: #3a3a3d; -fx-background-radius: 6; -fx-border-radius: 6; }
        .combo-box-popup .list-cell:filled:hover { -fx-background-color: #34343a; }
        .combo-box-popup .list-cell:filled:selected { -fx-background-color: -fx-accent; -fx-text-fill: white; }

        .text-field, .text-area {
            -fx-text-fill: #e6e6e6;
            -fx-prompt-text-fill: #7a7a7a;
            -fx-background-radius: 6;
            -fx-border-radius: 6;
            -fx-border-color: #3a3a3d;
            -fx-border-width: 1;
            -fx-highlight-fill: -fx-accent;
        }
        .text-field { -fx-background-color: #262627; }
        .text-area, .text-area .content { -fx-background-color: #1c1c1c; -fx-background-radius: 6; }
        .text-field:focused, .text-area:focused { -fx-border-color: -fx-accent; }

        .list-view { -fx-background-color: #252526; -fx-border-color: transparent; }
        .list-view .list-cell { -fx-background-color: transparent; -fx-text-fill: #e6e6e6; -fx-padding: 3 6; }
        .list-view .list-cell:filled:hover { -fx-background-color: #2f2f31; }
        .list-view .list-cell:filled:selected { -fx-background-color: #34343a; -fx-text-fill: white; }

        .check-box { -fx-text-fill: #e6e6e6; }
        .check-box .box { -fx-background-color: #262627; -fx-border-color: #4a4a4e; -fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 2; }
        .check-box:selected .box { -fx-background-color: -fx-accent; -fx-border-color: -fx-accent; }
        .check-box:selected .mark { -fx-background-color: white; }

        .scroll-bar { -fx-background-color: transparent; }
        .scroll-bar .track { -fx-background-color: transparent; }
        .scroll-bar .thumb { -fx-background-color: #3a3a3d; -fx-background-radius: 6; }
        .scroll-bar .thumb:hover { -fx-background-color: #4a4a4e; }
        .scroll-bar .increment-button, .scroll-bar .decrement-button { -fx-opacity: 0; -fx-padding: 0 6 0 6; }
        .scroll-pane, .scroll-pane > .viewport { -fx-background-color: transparent; }

        .progress-bar > .track { -fx-background-color: #2a2a2c; -fx-background-radius: 6; }
        .progress-bar > .bar { -fx-background-color: -fx-accent; -fx-background-radius: 6; -fx-background-insets: 0; }
        .progress-indicator { -fx-progress-color: -fx-accent; }

        .tooltip { -fx-background-color: #2e2e30; -fx-text-fill: #e6e6e6; -fx-background-radius: 6; -fx-border-color: #3a3a3d; -fx-border-radius: 6; -fx-font-size: 11px; }

        .context-menu { -fx-background-color: #262627; -fx-border-color: #3a3a3d; -fx-background-radius: 6; -fx-border-radius: 6; }
        .menu-item { -fx-background-color: transparent; }
        .menu-item .label { -fx-text-fill: #e6e6e6; }
        .menu-item:focused { -fx-background-color: #34343a; }
        .menu-item:focused .label { -fx-text-fill: white; }

        .dialog-pane { -fx-background-color: #232324; }
        .dialog-pane .label { -fx-text-fill: #e6e6e6; }
        .dialog-pane .header-panel { -fx-background-color: #2a2a2c; }
        .dialog-pane .header-panel .label { -fx-text-fill: #e6e6e6; -fx-font-size: 14px; }
        """;

    private Theme() {}
}
