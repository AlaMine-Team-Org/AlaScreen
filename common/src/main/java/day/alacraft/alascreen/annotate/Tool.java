package day.alacraft.alascreen.annotate;

/** What a stroke on the confirmation screen draws. */
public enum Tool {
    ARROW,
    FRAME,
    PEN,
    /** A solid block, never a blur: pixelated text is readable again with the right font, and ours is known. */
    REDACT
}
