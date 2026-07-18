package com.helloai.api.controller;

import com.helloai.api.dto.CreateReviewRequest;
import com.helloai.api.dto.review.ReviewResponse;
import com.helloai.api.interceptor.AuthInterceptor;
import com.helloai.common.base.R;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.ReviewResult;
import com.helloai.core.entity.ReviewRecord;
import com.helloai.core.task.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    public R<ReviewResponse> create(@Valid @RequestBody CreateReviewRequest request,
                                   @RequestHeader(value = "X-Agent-Id", required = false) Long agentId,
                                   HttpServletRequest httpRequest) {
        ReviewResult result = ReviewResult.valueOf(request.getResult().toUpperCase());

        // v1.1 修复：优先从 Header 取 reviewerAgentId；若 Header 缺失，从 AuthInterceptor 上下文取
        Long reviewerAgentId = agentId;
        if (reviewerAgentId == null) {
            Object attr = httpRequest.getAttribute(AuthInterceptor.AUTH_ID_KEY);
            if (attr instanceof Long) {
                reviewerAgentId = (Long) attr;
            } else if (attr instanceof Number) {
                reviewerAgentId = ((Number) attr).longValue();
            }
        }
        if (reviewerAgentId == null) {
            throw new BizException("无法识别审查者身份，请通过 X-Agent-Id Header 或 Bearer 认证");
        }

        ReviewRecord record = reviewService.createReview(
                request.getSubTaskId(), reviewerAgentId, result,
                request.getScore() != null ? request.getScore() : 3,
                request.getIssues(), request.getComment(), request.getReworkAgentId());
        return R.ok(toResponse(record));
    }

    @GetMapping
    public R<List<ReviewResponse>> list(@RequestParam(value = "subTaskId", required = false) Long subTaskId) {
        if (subTaskId != null) {
            return R.ok(reviewService.getBySubTaskId(subTaskId).stream().map(this::toResponse).toList());
        }
        return R.ok(reviewService.list().stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public R<ReviewResponse> getById(@PathVariable("id") Long id) {
        ReviewRecord record = reviewService.getById(id);
        if (record == null) {
            return R.fail("审查记录不存在");
        }
        return R.ok(toResponse(record));
    }

    private ReviewResponse toResponse(ReviewRecord record) {
        ReviewResponse response = new ReviewResponse();
        response.setId(record.getId());
        response.setSubTaskId(record.getSubTaskId());
        response.setReviewerAgent(record.getReviewerAgent());
        response.setResult(record.getResult());
        response.setScore(record.getScore());
        response.setIssues(record.getIssues());
        response.setComment(record.getComment());
        response.setRound(record.getRound());
        response.setCreateTime(record.getCreateTime());
        response.setUpdateTime(record.getUpdateTime());
        response.setRemark(record.getRemark());
        return response;
    }
}
