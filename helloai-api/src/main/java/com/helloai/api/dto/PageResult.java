package com.helloai.api.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

@Data
public class PageResult<T> {
    private List<T> list;
    private long total;
    private long pages;
    private long current;

    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> r = new PageResult<>();
        r.setList(page.getRecords());
        r.setTotal(page.getTotal());
        r.setPages(page.getPages());
        r.setCurrent(page.getCurrent());
        return r;
    }

    public static <S, T> PageResult<T> of(IPage<S> page, Function<S, T> mapper) {
        PageResult<T> r = new PageResult<>();
        List<T> list = page.getRecords().stream()
                .map(item -> mapper.apply(item))
                .toList();
        r.setList(list);
        r.setTotal(page.getTotal());
        r.setPages(page.getPages());
        r.setCurrent(page.getCurrent());
        return r;
    }
}
