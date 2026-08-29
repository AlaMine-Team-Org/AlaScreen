package day.alacraft.alascreen.api;

/**
 * One screenshot on its way out, with the context the site records beside it.
 *
 * @param fileName     what to call the part in the multipart body; also what the
 *                     site shows as the original name
 * @param png          the encoded image. Bytes, not a path: the capture path
 *                     never touches the disk unless the player asked for a local
 *                     copy, and a temporary file would be one more thing to leak
 * @param width        pixel width, known before sending so an oversized shot is
 *                     refused here rather than after the upload
 * @param height       pixel height
 * @param siteLocale   two-letter locale for the returned URL, e.g. {@code ru}
 * @param minecraftVersion for the metadata shown on the page
 * @param loader       {@code fabric} or {@code neoforge}
 */
public record UploadRequest(
        String fileName,
        byte[] png,
        int width,
        int height,
        String siteLocale,
        String minecraftVersion,
        String loader) {

    public UploadRequest {
        if (png == null || png.length == 0) {
            throw new IllegalArgumentException("A screenshot upload needs image bytes.");
        }
        if (fileName == null || fileName.isBlank()) {
            fileName = "screenshot.png";
        }
        if (siteLocale == null || siteLocale.isBlank()) {
            siteLocale = "en";
        }
    }

    public long bytes() {
        return png.length;
    }
}
