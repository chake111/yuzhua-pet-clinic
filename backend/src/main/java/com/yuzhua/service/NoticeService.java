package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.domain.enums.NoticeStatus;
import com.yuzhua.domain.enums.NoticeType;
import com.yuzhua.dto.NoticeForm;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.entity.Notice;
import com.yuzhua.mapper.NoticeMapper;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.NoticeVo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NoticeService extends ServiceImpl<NoticeMapper, Notice> {

    private static final int MAX_LATEST_LIMIT = 20;

    public PageResult<NoticeVo> pagePublished(PageQuery q, @Nullable NoticeType type, @Nullable String keyword) {
        LambdaQueryWrapper<Notice> qw = publishedQuery(type, keyword)
                .select(Notice::getId, Notice::getTitle, Notice::getCover, Notice::getSummary,
                        Notice::getType, Notice::getTopFlag, Notice::getPublishTime, Notice::getViewCount);
        Page<Notice> page = page(new Page<>(q.getPageNum(), q.getPageSize()), qw);
        return PageResult.of(page, page.getRecords().stream().map(NoticeService::toVo).toList());
    }

    public List<NoticeVo> listLatest(int limit) {
        return list(publishedQuery(null, null)
                .select(Notice::getId, Notice::getTitle, Notice::getSummary, Notice::getType,
                        Notice::getTopFlag, Notice::getPublishTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, MAX_LATEST_LIMIT))))
                .stream().map(NoticeService::toVo).toList();
    }

    public NoticeVo readDetail(Long id) {
        Notice n = getById(id);
        if (n == null || n.getStatus() != NoticeStatus.PUBLISHED) {
            throw BizException.notFound("公告");
        }
        update(Wrappers.<Notice>lambdaUpdate()
                .setSql("view_count = view_count + 1")
                .eq(Notice::getId, id));
        int views = n.getViewCount() == null ? 0 : n.getViewCount();
        n.setViewCount(views + 1);
        n.setPublishBy(null);
        return toVo(n);
    }

    public PageResult<NoticeVo> pageAdmin(PageQuery q, @Nullable NoticeStatus status,
                                          @Nullable NoticeType type, @Nullable String keyword) {
        LambdaQueryWrapper<Notice> qw = Wrappers.<Notice>lambdaQuery()
                .eq(status != null, Notice::getStatus, status)
                .eq(type != null, Notice::getType, type)
                .like(keyword != null && !keyword.isBlank(), Notice::getTitle, keyword)
                .orderByDesc(Notice::getTopFlag)
                .orderByDesc(Notice::getPublishTime)
                .orderByDesc(Notice::getId);
        Page<Notice> page = page(new Page<>(q.getPageNum(), q.getPageSize()), qw);
        return PageResult.of(page, page.getRecords().stream().map(NoticeService::toVo).toList());
    }

    public NoticeVo detail(Long id) {
        return toVo(require(id));
    }

    public Notice require(Long id) {
        Notice n = getById(id);
        if (n == null) {
            throw BizException.notFound("公告");
        }
        return n;
    }

    /**
     * 事务内新建公告：缺省 {@code status}/{@code type}/{@code topFlag} 分别回退 {@code DRAFT}/{@code NOTICE}/0，
     * 仅当初始状态为 {@code PUBLISHED} 时记录发布人与发布时间。
     */
    @Transactional
    public NoticeVo create(NoticeForm form) {
        requireTitle(form.title());
        NoticeStatus status = form.status() == null ? NoticeStatus.DRAFT : form.status();
        Notice n = new Notice();
        n.setTitle(form.title());
        n.setCover(form.cover());
        n.setSummary(form.summary());
        n.setContent(form.content());
        n.setType(form.type() == null ? NoticeType.NOTICE : form.type());
        n.setTopFlag(form.topFlag() == null ? 0 : form.topFlag());
        n.setStatus(status);
        validate(n);
        n.setViewCount(0);
        if (status == NoticeStatus.PUBLISHED) {
            n.setPublishTime(LocalDateTime.now());
            n.setPublishBy(UserContext.requireId());
        }
        save(n);
        return toVo(n);
    }

    /**
     * 事务内更新公告（不存在抛 404）：缺省 {@code status}/{@code type}/{@code topFlag} 沿用原记录值，
     * 仅首次发布补写发布人与发布时间，{@code viewCount} 不写入以保留原阅读量。
     */
    @Transactional
    public void update(Long id, NoticeForm form) {
        Notice cur = require(id);
        requireTitle(form.title());
        NoticeStatus status = form.status() != null ? form.status()
                : (cur.getStatus() != null ? cur.getStatus() : NoticeStatus.DRAFT);
        Notice row = new Notice();
        row.setId(id);
        row.setTitle(form.title());
        row.setCover(form.cover());
        row.setSummary(form.summary());
        row.setContent(form.content());
        row.setType(form.type() == null ? cur.getType() : form.type());
        row.setTopFlag(form.topFlag() == null ? cur.getTopFlag() : form.topFlag());
        row.setStatus(status);
        validate(row);
        // 原因: 仅首次发布写入发布人与发布时间，重新发布保持原有首次发布时间与累计阅读量
        if (status == NoticeStatus.PUBLISHED && cur.getPublishTime() == null) {
            row.setPublishTime(LocalDateTime.now());
            row.setPublishBy(UserContext.requireId());
        }
        updateById(row);
    }

    public void changeStatus(Long id, @Nullable NoticeStatus status) {
        if (status == null) {
            throw new BizException("状态只能是 0 草稿 / 1 已发布 / 2 已下架");
        }
        Notice n = require(id);
        n.setStatus(status);
        validate(n);
        boolean firstPublish = status == NoticeStatus.PUBLISHED && n.getPublishTime() == null;
        update(Wrappers.<Notice>lambdaUpdate()
                .set(Notice::getStatus, status)
                .set(firstPublish, Notice::getPublishTime, LocalDateTime.now())
                .set(firstPublish, Notice::getPublishBy, UserContext.requireId())
                .eq(Notice::getId, id));
    }

    public void remove(Long id) {
        removeById(id);
    }

    private LambdaQueryWrapper<Notice> publishedQuery(@Nullable NoticeType type, @Nullable String keyword) {
        return Wrappers.<Notice>lambdaQuery()
                .eq(Notice::getStatus, NoticeStatus.PUBLISHED)
                .eq(type != null, Notice::getType, type)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Notice::getTitle, keyword).or().like(Notice::getSummary, keyword))
                .orderByDesc(Notice::getTopFlag)
                .orderByDesc(Notice::getPublishTime)
                .orderByDesc(Notice::getId);
    }

    private void requireTitle(@Nullable String title) {
        if (title == null || title.isBlank()) {
            throw new BizException("请填写标题");
        }
    }

    static void validate(Notice n) {
        if (n.getStatus() == null) {
            throw new BizException("状态只能是 0 草稿 / 1 已发布 / 2 已下架");
        }
        if (n.getType() == null) {
            throw new BizException("类型只能是 1 公告 / 2 活动 / 3 养宠科普");
        }
        if (n.getTopFlag() == null || (n.getTopFlag() != 0 && n.getTopFlag() != 1)) {
            throw new BizException("置顶标志只能是 0 或 1");
        }
        if (n.getTitle() == null || n.getTitle().isBlank()) throw new BizException("请填写标题");
        if (n.getStatus() == NoticeStatus.PUBLISHED && (n.getContent() == null || n.getContent().isBlank())) {
            throw new BizException("发布前请填写正文");
        }
    }

    private static NoticeVo toVo(Notice n) {
        return new NoticeVo(
                n.getId(),
                n.getTitle(),
                n.getCover(),
                n.getSummary(),
                n.getContent(),
                n.getType(),
                n.getTopFlag(),
                n.getStatus(),
                n.getViewCount(),
                n.getPublishTime(),
                n.getPublishBy(),
                n.getCreateTime()
        );
    }
}
