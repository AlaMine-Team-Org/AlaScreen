package day.alacraft.alascreen.clipboard;

/**
 * How an attempt to put a picture on the clipboard ended.
 *
 * <p>Four outcomes rather than a boolean, for the same reason the API has an
 * error code per failure: "it did not work" leaves the player with nothing to
 * do, while "that file is gone" and "this system cannot do it" lead to two
 * different next steps, and only one of them is worth reporting as a bug.
 *
 * <p>Each outcome carries its own translation key, so the mapping lives in the
 * enum rather than in a switch somewhere else that can fall out of step with it.
 */
public enum CopyOutcome {

    /** The picture is on the clipboard. */
    COPIED("alascreen.chat.copied_image"),

    /**
     * The local file is not there any more.
     *
     * <p>Expected, not exceptional: the screenshot folder is the player's own,
     * and a link shared last week outlives the file behind it.
     */
    FILE_MISSING("alascreen.error.local_file_missing"),

    /**
     * This runtime cannot put a picture on the clipboard at all.
     *
     * <p>Minecraft turns AWT off, and on macOS it must stay off — AWT and GLFW
     * cannot share the first thread. A runtime built without {@code java.desktop}
     * lands here too.
     */
    UNSUPPORTED("alascreen.error.clipboard_unavailable"),

    /** The system refused the write — another application holding the clipboard, most often. */
    FAILED("alascreen.error.clipboard_failed");

    private final String messageKey;

    CopyOutcome(String messageKey) {
        this.messageKey = messageKey;
    }

    /** The translation key describing this outcome to the player. */
    public String messageKey() {
        return messageKey;
    }

    /** Whether this is the good ending — the only one shown in the normal colour. */
    public boolean succeeded() {
        return this == COPIED;
    }
}
