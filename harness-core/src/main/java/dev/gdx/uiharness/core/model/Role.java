package dev.gdx.uiharness.core.model;

/** Backend-neutral semantic roles exposed by snapshot nodes. */
public enum Role {
    /** Actor without a more specific semantic role. */
    GENERIC,
    /** Container of related actors. */
    GROUP,
    /** Push button. */
    BUTTON,
    /** Check box. */
    CHECKBOX,
    /** Radio button. */
    RADIO_BUTTON,
    /** Single-line editable text field. */
    TEXT_FIELD,
    /** Multi-line editable text area. */
    TEXT_AREA,
    /** Static text label. */
    LABEL,
    /** Image or icon. */
    IMAGE,
    /** List container. */
    LIST,
    /** Item in a list. */
    LIST_ITEM,
    /** Select or drop-down control. */
    SELECT,
    /** Slider control. */
    SLIDER,
    /** Progress indicator. */
    PROGRESS_BAR,
    /** Scrollable region. */
    SCROLL_PANE,
    /** Window container. */
    WINDOW,
    /** Dialog container. */
    DIALOG,
    /** Menu container. */
    MENU,
    /** Menu item. */
    MENU_ITEM,
    /** Tooltip. */
    TOOLTIP
}
