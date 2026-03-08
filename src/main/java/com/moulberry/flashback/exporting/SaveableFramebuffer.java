package com.moulberry.flashback.exporting;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * SaveableFramebuffer - Versi kompatibel Vulkan/OpenGL
 *
 * Perubahan dari versi asli:
 * - DIHAPUS: GL30C.glGenBuffers(), glBindBuffer(), glReadPixels(), glMapBuffer()
 *   (semua ini OpenGL-only, tidak kompatibel dengan VulkanMod)
 *
 * - DIGANTI dengan:
 *   - GpuBuffer (abstraction layer Minecraft 1.21+)
 *   - commandEncoder.copyTextureToBuffer() untuk download GPU → CPU
 *   - GpuBuffer.slice().map() untuk baca data
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

        // Buat ulang buffer hanya kalau ukuran berubah
        if (this.downloadBuffer == null || this.allocatedWidth != width || this.allocatedHeight != height) {
            if (this.downloadBuffer != null) {
                this.downloadBuffer.close();
            }

            // GpuBuffer.Usage.DOWNLOAD = buffer untuk transfer GPU ke CPU
            // Di OpenGL ini jadi PBO, di Vulkan ini jadi staging buffer — otomatis
            this.downloadBuffer = RenderSystem.getDevice().createBuffer(
                () -> "flashback:saveable_framebuffer",
                GpuBuffer.Usage.DOWNLOAD,
                requiredSize
            );

            this.allocatedWidth = width;
            this.allocatedHeight = height;
        }

        // Copy texture dari GPU ke buffer — tidak ada GL call langsung
        // VulkanMod handle ini via vkCmdCopyImageToBuffer secara internal
        RenderSystem.getDevice().createCommandEncoder()
            .copyTextureToBuffer(gpuTexture, this.downloadBuffer, 0, width, height);
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

        // Map buffer untuk baca dari CPU — bukan glMapBuffer langsung
        GpuBufferSlice slice = this.downloadBuffer.slice(0, width * height * 4);
        ByteBuffer mapped = slice.map();

        if (mapped == null) {
            nativeImage.close();
            throw new IllegalStateException("Failed to map download buffer");
        }

        try {
            MemoryUtil.memCopy(MemoryUtil.memAddress(mapped), nativeImage.pixels, nativeImage.size);
        } finally {
            slice.unmap();
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
