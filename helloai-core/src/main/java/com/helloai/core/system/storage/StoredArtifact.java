package com.helloai.core.system.storage;

/**
 * 产物存储写入结果：storageUrl 为附件元数据落库的权威地址，
 * bucketName/objectKey/fileSize 供 attachment 表冗余记录。
 */
public record StoredArtifact(String storageUrl, String bucketName, String objectKey, long fileSize) {
}
