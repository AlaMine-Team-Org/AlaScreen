package day.alacraft.alascreen.mc;

import com.mojang.blaze3d.platform.NativeImage;
import day.alacraft.alascreen.annotate.Canvas;

/** A native frame as something the rasteriser can paint on. {@code setPixel} takes ARGB, checked with javap. */
record ImageCanvas(NativeImage image) implements Canvas {

    @Override
    public int width() {
        return image.getWidth();
    }

    @Override
    public int height() {
        return image.getHeight();
    }

    @Override
    public void set(int x, int y, int argb) {
        image.setPixel(x, y, argb);
    }
}
