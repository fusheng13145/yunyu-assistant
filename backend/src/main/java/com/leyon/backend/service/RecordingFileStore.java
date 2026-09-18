package com.leyon.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 通话录音文件存储（P2-9）
 * 收拢录音文件的全部 IO 操作，便于归档服务在单元测试中 mock。
 * 删除文件缺失不算失败；IO 异常仅记录日志并返回 false（文件清理失败不影响数据归档）。
 *
 * @author leyon
 */
@Component
public class RecordingFileStore {

    private static final Logger logger = LoggerFactory.getLogger(RecordingFileStore.class);

    private final String rootDir;

    public RecordingFileStore(@Value("${app.recording.dir:./data/recordings}") String rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * 删除单个录音文件（路径经 normalize 防穿越）
     *
     * @return true 表示文件实际被删除，false 表示缺失或删除失败（不算归档失败）
     */
    public boolean delete(String recordingName) {
        if (recordingName == null || recordingName.isBlank()) {
            return false;
        }
        try {
            Path target = Path.of(rootDir).resolve(recordingName).normalize();
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            logger.warn("删除录音文件失败: {}", recordingName, e);
            return false;
        }
    }
}