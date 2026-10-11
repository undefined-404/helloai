package com.helloai.core.event;

import com.helloai.common.constant.TimelineEventCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件码目录服务（REF-6.8，事件码服务端单一来源）。
 *
 * <p>目录内容驱动自 {@link TimelineEventCode} 枚举（唯一事实源）：前端不再保留
 * EVENT_META / sequenceFlow.LABEL 硬编码副本，改经 {@code /api/events/catalog} 拉取
 * （与技能目录 REF-1.2c 同一范式）。</p>
 *
 * <p>只读、无 DB：遍历枚举 {@link TimelineEventCode#values()} 下发。枚举里每条都带
 * label（收录口径 = 用户可见码），故全量下发。</p>
 */
@Service
public class EventCatalogService {

    /** 全量用户可见事件码目录（按枚举声明序，即码名序）。 */
    public List<EventCatalogItem> catalog() {
        TimelineEventCode[] values = TimelineEventCode.values();
        List<EventCatalogItem> items = new ArrayList<>(values.length);
        for (TimelineEventCode code : values) {
            items.add(new EventCatalogItem(code.code(), code.label(), code.desc()));
        }
        return items;
    }
}