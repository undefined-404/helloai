package com.helloai.api.dto.system;

/**
 * 公告草稿保存请求（MSG-6）。
 */
public record AnnouncementDraftRequest(String title, String body) {
}