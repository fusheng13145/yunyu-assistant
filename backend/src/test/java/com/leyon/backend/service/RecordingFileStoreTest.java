package com.leyon.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 录音文件存储单测（P2-9）
 * 覆盖：存在删除成功 / 缺失返回 false / 空名返回 false（使用真实临时目录）
 *
 * @author leyon
 */
class RecordingFileStoreTest {

    @TempDir
    Path tempDir;

    private RecordingFileStore newStore() {
        return new RecordingFileStore(tempDir.toString());
    }

    @Test
    void delete_existingFile_returnsTrue() throws Exception {
        Files.write(tempDir.resolve("call-1.webm"), new byte[]{1, 2, 3});
        assertThat(newStore().delete("call-1.webm")).isTrue();
        assertThat(tempDir.resolve("call-1.webm")).doesNotExist();
    }

    @Test
    void delete_missingFile_returnsFalse() {
        assertThat(newStore().delete("ghost.webm")).isFalse();
    }

    @Test
    void delete_blankName_returnsFalse() {
        RecordingFileStore store = newStore();
        assertThat(store.delete(null)).isFalse();
        assertThat(store.delete("")).isFalse();
        assertThat(store.delete("  ")).isFalse();
    }
}