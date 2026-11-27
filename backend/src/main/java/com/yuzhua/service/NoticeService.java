package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.dto.NoticeForm;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.entity.Notice;
import com.yuzhua.mapper.NoticeMapper;
import com.yuzhua.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NoticeService extends ServiceImpl<NoticeMapper, Notice> {

    public static final int ST_DRAFT = 0;
    public static final int ST_PUBLISHED = 1;
    public static final int ST_OFFLINE = 2;

    public PageResult<Notice> pagePublished(PageQuery q, @Nullable Integer type, @Nullable String keyword) {
        LambdaQueryWrapper<Notice> qw = publishedQuery(type, keyword)
                .select(Notice::getId, Notice::getTitle, Notice::getCover, Notice::getSummary,
                        Notice::getType, Notice::getTopFlag, Notice::getPublishTime, Notice::getViewCount);
        return PageResult.of(page(new Page<>(q.getPageNum(), q.getPageSize()), qw));
    }

    public List<Notice> listLatest(int limit) {
        return list(publishedQuery(null, null)
                .select(Notice::getId, Notice::getTitle, Notice::getSummary, Notice::getType,
                        Notice::getTopFlag, Notice::getPublishTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 20))));
    }

    public Notice readDetail(Long id) {
        Notice n = getById(id);
        if (n == null || !Integer.valueOf(ST_PUBLISHED).equals(n.getStatus())) {
            throw BizException.notFound("公告");
        }
        update(Wrappers.<Notice>lambdaUpdate()
                .setSql("view_count = view_count + 1")
                .eq(Notice::getId, id));
        int views = n.getViewCount() == null ? 0 : n.getViewCount();
        n.setViewCount(views + 1);
        n.setPublishBy(null);
        return n;
    }

    public PageResult<Notice> pageAdmin(PageQuery q, @Nullable Integer status, @Nullable Integer type, @Nullable String keyword) {
        LambdaQueryWrapper<Notice> qw = Wrappers.<Notice>lambdaQuery()
                .eq(status != null, Notice::getStatus, status)
                .eq(type != null, Notice::getType, type)
                .like(keyword != null && !keyword.isBlank(), Notice::getTitle, keyword)
                .orderByDesc(Notice::getTopFlag)
                .orderByDesc(Notice::getPublishTime)
                .orderByDesc(Notice::getId);
        return PageResult.of(page(new Page<>(q.getPageNum(), q.getPageSize()), qw));
    }

    public Notice require(Long id) {
        Notice n = getById(id);
        if (n == null) {
            throw BizException.notFound("公告");
        }
        return n;
    }

    /** 事务内新建公告；仅当初始状态为已发布时记录发布人与发布时间。 */
    @Transactional
    public Notice create(NoticeForm form) {
        requireTitle(form.getTitle());
        int status = form.getStatus() == null ? ST_DRAFT : form.getStatus();
        Notice n = new Notice();
        n.setTitle(form.getTitle());
        n.setCover(form.getCover());
        n.setSummary(form.getSummary());
        n.setContent(form.getContent());
        n.setType(form.getType() == null ? 1 : form.getType());
        n.setTopFlag(form.getTopFlag() == null ? 0 : form.getTopFlag());
        n.setStatus(status);
        validate(n);
        n.setViewCount(0);
        if (status == ST_PUBLISHED) {
            n.setPublishTime(LocalDateTime.now());
            n.setPublishBy(UserContext.requireId());
        }
        save(n);
        return n;
    }

    /** 事务内更新公告，首次发布才补写发布人/时间，阅读量由更新策略保留。 */
    @Transactional
    public void update(Long id, NoticeForm form) {
        Notice cur = require(id);
        requireTitle(form.getTitle());
        int status = form.getStatus() != null ? form.getStatus()
                : (cur.getStatus() != null ? cur.getStatus() : ST_DRAFT);
        Notice row = new Notice();
        row.setId(id);
        row.setTitle(form.getTitle());
        row.setCover(form.getCover());
        row.setSummary(form.getSummary());
        row.setContent(form.getContent());
        row.setType(form.getType() == null ? cur.getType() : form.getType());
        row.setTopFlag(form.getTopFlag() == null ? cur.getTopFlag() : form.getTopFlag());
        row.setStatus(status);
        validate(row);
        // 原因: 仅首次发布写入发布人与发布时间，重新发布保持原有首次发布时间与累计阅读量
        if (status == ST_PUBLISHED && cur.getPublishTime() == null) {
            row.setPublishTime(LocalDateTime.now());
            row.setPublishBy(UserContext.requireId());
        }
        updateById(row);
    }

    public void changeStatus(Long id, Integer status) {
        if (status == null || status < ST_DRAFT || status > ST_OFFLINE) {
            throw new BizException("状态只能是 0 草稿 / 1 已发布 / 2 已下架");
        }
        Notice n = require(id);
        n.setStatus(status);
        validate(n);
        boolean firstPublish = status == ST_PUBLISHED && n.getPublishTime() == null;
        update(Wrappers.<Notice>lambdaUpdate()
                .set(Notice::getStatus, status)
                .set(firstPublish, Notice::getPublishTime, LocalDateTime.now())
                .set(firstPublish, Notice::getPublishBy, UserContext.requireId())
                .eq(Notice::getId, id));
    }

    public void remove(Long id) {
        removeById(id);
    }

    private LambdaQueryWrapper<Notice> publishedQuery(@Nullable Integer type, @Nullable String keyword) {
        return Wrappers.<Notice>lambdaQuery()
                .eq(Notice::getStatus, ST_PUBLISHED)
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
        if (n.getStatus() == null || n.getStatus() < ST_DRAFT || n.getStatus() > ST_OFFLINE) {
            throw new BizException("状态只能是 0 草稿 / 1 已发布 / 2 已下架");
        }
        if (n.getType() == null || n.getType() < 1 || n.getType() > 3) {
            throw new BizException("类型只能是 1 公告 / 2 活动 / 3 养宠科普");
        }
        if (n.getTopFlag() == null || (n.getTopFlag() != 0 && n.getTopFlag() != 1)) {
            throw new BizException("置顶标志只能是 0 或 1");
        }
        if (n.getTitle() == null || n.getTitle().isBlank()) throw new BizException("请填写标题");
        if (n.getStatus() == ST_PUBLISHED && (n.getContent() == null || n.getContent().isBlank())) {
            throw new BizException("发布前请填写正文");
        }
    }
}
