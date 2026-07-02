package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.common.constant.ReviewResult;
import com.helloai.api.dto.CreateReviewRequest;
import com.helloai.core.entity.ReviewRecord;
import com.helloai.core.service.ReviewService;
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
    public R<ReviewRecord> create(@RequestBody CreateReviewRequest request) {
        ReviewResult result = ReviewResult.valueOf(request.getResult().toUpperCase());
        ReviewRecord record = reviewService.createReview(
                request.getSubTaskId(), null, result, request.getScore(),
                request.getIssues(), request.getComment(), request.getReworkAgentId());
        return R.ok(record);
    }

    @GetMapping
    public R<List<ReviewRecord>> list(@RequestParam(required = false) Long subTaskId) {
        if (subTaskId != null) {
            return R.ok(reviewService.getBySubTaskId(subTaskId));
        }
        return R.ok(reviewService.list());
    }

    @GetMapping("/{id}")
    public R<ReviewRecord> getById(@PathVariable Long id) {
        ReviewRecord record = reviewService.getById(id);
        if (record == null) {
            return R.fail("审查记录不存在");
        }
        return R.ok(record);
    }
}
