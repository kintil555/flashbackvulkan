package com.moulberry.flashback.exporting;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * SaveableFramebuffer - Versi kompatibel Vulkan/OpenGL untuk MC 1.21.11
 *
 * Perubahan dari versi asli:
 * - DIHAPUS: GL30C.glGenBuffers(), glBindBuffer(), glReadPixels(), glMapBuffer()
 *   (semua OpenGL-only, tidak kompatibel dengan VulkanMod)
 *
 * - DIGANTI dengan:
 *   - GpuBuffer(USAGE_COPY_DST | USAGE_MAP_READ, size)
 *   - commandEncoder.copyTextureToBuffer() dengan Runnable callback
 *   - commandEncoder.mapBuffer(slice, read, write) → MappedView
 */
public class SaveableFramebuffer implements AutoCloseable {

    private @Nullable GpuBuffer downloadBuffer;
    private int allocatedWidth = -1;
    private int allocatedHeight = -1;

    public @Nullable FloatBuffer audioBuffer;

    private boolean isDownloading = false;

    public SaveableFramebuffer() {
        this.downloadBuffer = null;
    }

    public void startDownload(GpuTexture gpuTexture, int width, int height) {
        if (this.isDownloading) {
            throw new IllegalStateException("Can't start downloading while already downloading");
        }
        this.isDownloading = true;

        int requiredSize = width * height * 4; // RGBA = 4 bytes per pixel

        // Buat ulang buffer hanya kalau dimensi berubah
        if (this.downloadBuffer == null || this.allocatedWidth != width || this.allocatedHeight != height) {
            if (this.downloadBuffer != null) {
                this.downloadBuffer.close();
            }

            // USAGE_COPY_DST = tujuan transfer dari GPU
            // USAGE_MAP_READ = bisa dibaca dari CPU
            // Di OpenGL → PBO GL_STREAM_READ; di Vulkan → staging buffer
            int usage = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ;
            this.downloadBuffer = RenderSystem.getDevice().createBuffer(
                () -> "flashback:saveable_framebuffer",
                usage,
                requiredSize
            );

            this.allocatedWidth = width;
            this.allocatedHeight = height;
        }

        // copyTextureToBuffer signature MC 1.21.x:
        // (GpuTexture source, GpuBuffer target, int offset, Runnable callback, int mipLevel)
        RenderSystem.getDevice().createCommandEncoder()
            .copyTextureToBuffer(gpuTexture, this.downloadBuffer, 0, () -> {}, 0);
    }

    public NativeImage finishDownload(int width, int height) {
        if (!this.isDownloading) {
            throw new IllegalStateException("Can't finish downloading before download has started");
        }
        this.isDownloading = false;

        if (this.downloadBuffer == null) {
            throw new IllegalStateException("Download buffer is null");
        }

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        // mapBuffer via CommandEncoder — API yang benar untuk MC 1.21.6+
        // mapBuffer(GpuBufferSlice slice, boolean read, boolean write) → MappedView (AutoCloseable)
        GpuBufferSlice slice = this.downloadBuffer.slice(0, width * height * 4);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        try (GpuBuffer.MappedView mappedView = encoder.mapBuffer(slice, true, false)) {
            ByteBuffer mapped = mappedView.data();
            if (mapped == null) {
                nativeImage.close();
                throw new IllegalStateException("Failed to map download buffer");
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(mapped), nativeImage.pixels, (long) width * height * 4);
        }

        return nativeImage;
    }

    @Override
    public void close() {
        if (this.downloadBuffer != null) {
            this.downloadBuffer.close();
            this.downloadBuffer = null;
        }
        this.allocatedWidth = -1;
        this.allocatedHeight = -1;
    }

}
