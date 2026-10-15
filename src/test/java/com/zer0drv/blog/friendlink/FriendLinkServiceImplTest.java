package com.zer0drv.blog.friendlink;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.friendlink.domain.FriendLink;
import com.zer0drv.blog.friendlink.dto.FriendLinkSaveDTO;
import com.zer0drv.blog.friendlink.service.impl.FriendLinkServiceImpl;
import com.zer0drv.blog.friendlink.vo.FriendLinkVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 友情链接纯单测（MP 继承方法用 spy + doReturn 桩掉，不起容器）。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class FriendLinkServiceImplTest {

    private FriendLinkServiceImpl friendLinkService;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 SqlSessionFactory：手动初始化实体 TableInfo，
        // 否则 LambdaQueryWrapper 找不到 lambda 列缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), FriendLink.class);
    }

    @BeforeEach
    void setUp() {
        friendLinkService = spy(new FriendLinkServiceImpl());
        org.springframework.test.util.ReflectionTestUtils.setField(friendLinkService, "attachmentReferences", org.mockito.Mockito.mock(com.zer0drv.blog.attachment.service.AttachmentReferenceGuard.class));
    }

    private static FriendLink link(Long id, String name, short status) {
        FriendLink link = new FriendLink();
        link.setId(id);
        link.setName(name);
        link.setUrl("https://example.com");
        link.setLogo("");
        link.setDescription("desc");
        link.setSort(0);
        link.setStatus(status);
        return link;
    }

    private static FriendLinkSaveDTO dto(String name, String url) {
        FriendLinkSaveDTO dto = new FriendLinkSaveDTO();
        dto.setName(name);
        dto.setUrl(url);
        dto.setSort(1);
        return dto;
    }

    @Test
    void listPublic_mapsEntitiesToVO() {
        doReturn(List.of(link(1L, "A", FriendLink.STATUS_ENABLED)))
                .when(friendLinkService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        List<FriendLinkVO> result = friendLinkService.listPublic();

        assertEquals(1, result.size());
        assertEquals("A", result.get(0).getName());
        assertEquals("https://example.com", result.get(0).getUrl());
        assertEquals(FriendLink.STATUS_ENABLED, result.get(0).getStatus());
    }

    @Test
    void listPublic_secondCall_hitsCacheWithoutQuery() {
        // 启用列表缓存：第二次读取不查库（参照敏感词缓存模式）
        doReturn(List.of(link(1L, "A", FriendLink.STATUS_ENABLED)))
                .when(friendLinkService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        friendLinkService.listPublic();
        friendLinkService.listPublic();

        verify(friendLinkService, times(1)).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void add_savesEnabledAndTrimmed() {
        doReturn(true).when(friendLinkService).save(any(FriendLink.class));

        friendLinkService.add(dto("  Foo  ", "  https://foo.example  "));

        ArgumentCaptor<FriendLink> captor = ArgumentCaptor.forClass(FriendLink.class);
        verify(friendLinkService).save(captor.capture());
        assertEquals("Foo", captor.getValue().getName());
        assertEquals("https://foo.example", captor.getValue().getUrl());
        assertEquals(FriendLink.STATUS_ENABLED, captor.getValue().getStatus());
        assertEquals(1, captor.getValue().getSort());
    }

    @Test
    void add_nullableFields_defaultToEmptyString() {
        doReturn(true).when(friendLinkService).save(any(FriendLink.class));

        friendLinkService.add(dto("Foo", "https://foo.example"));

        ArgumentCaptor<FriendLink> captor = ArgumentCaptor.forClass(FriendLink.class);
        verify(friendLinkService).save(captor.capture());
        assertEquals("", captor.getValue().getLogo());
        assertEquals("", captor.getValue().getDescription());
    }

    @Test
    void add_invalidatesCache_takesEffectImmediately() {
        // add 后缓存即时失效：新友链不等 TTL 即出现在公开列表
        doReturn(List.of(link(1L, "A", FriendLink.STATUS_ENABLED)))
                .when(friendLinkService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertEquals(1, friendLinkService.listPublic().size());

        doReturn(true).when(friendLinkService).save(any(FriendLink.class));
        friendLinkService.add(dto("B", "https://b.example"));
        doReturn(List.of(link(1L, "A", FriendLink.STATUS_ENABLED), link(2L, "B", FriendLink.STATUS_ENABLED)))
                .when(friendLinkService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        assertEquals(2, friendLinkService.listPublic().size());
        // 三次读取对应两次查库：首次加载 + add 失效后重载
        verify(friendLinkService, times(2)).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void update_notExists_throwsFriendLinkNotExist() {
        doReturn(null).when(friendLinkService).getById(99L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> friendLinkService.update(99L, dto("X", "https://x.example")));
        assertEquals(StatusCode.FRIEND_LINK_NOT_EXIST.getCode(), ex.getCode());
        verify(friendLinkService, never()).updateById(any(FriendLink.class));
    }

    @Test
    void update_existing_appliesDto() {
        doReturn(link(1L, "Old", FriendLink.STATUS_ENABLED)).when(friendLinkService).getById(1L);
        doReturn(true).when(friendLinkService).updateById(any(FriendLink.class));

        friendLinkService.update(1L, dto("New", "https://new.example"));

        ArgumentCaptor<FriendLink> captor = ArgumentCaptor.forClass(FriendLink.class);
        verify(friendLinkService).updateById(captor.capture());
        assertEquals(1L, captor.getValue().getId());
        assertEquals("New", captor.getValue().getName());
        // 编辑不触碰状态字段：沿用库里读出的原值
        assertEquals(FriendLink.STATUS_ENABLED, captor.getValue().getStatus());
    }

    @Test
    void setStatus_invalidValue_throwsParamInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> friendLinkService.setStatus(1L, (short) 7));
        assertEquals(StatusCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(friendLinkService, never()).updateById(any(FriendLink.class));
    }

    @Test
    void setStatus_notExists_throwsFriendLinkNotExist() {
        doReturn(null).when(friendLinkService).getById(99L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> friendLinkService.setStatus(99L, FriendLink.STATUS_DISABLED));
        assertEquals(StatusCode.FRIEND_LINK_NOT_EXIST.getCode(), ex.getCode());
    }

    @Test
    void setStatus_disable_updatesAndInvalidatesCache() {
        // 停用后缓存即时失效：公开列表立即不再返回该链接
        doReturn(List.of(link(1L, "A", FriendLink.STATUS_ENABLED)))
                .when(friendLinkService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertEquals(1, friendLinkService.listPublic().size());

        doReturn(link(1L, "A", FriendLink.STATUS_ENABLED)).when(friendLinkService).getById(1L);
        doReturn(true).when(friendLinkService).updateById(any(FriendLink.class));
        friendLinkService.setStatus(1L, FriendLink.STATUS_DISABLED);

        ArgumentCaptor<FriendLink> captor = ArgumentCaptor.forClass(FriendLink.class);
        verify(friendLinkService).updateById(captor.capture());
        assertEquals(FriendLink.STATUS_DISABLED, captor.getValue().getStatus());

        doReturn(List.of()).when(friendLinkService)
                .list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertEquals(0, friendLinkService.listPublic().size());
        verify(friendLinkService, times(2)).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void delete_invalidatesCache_takesEffectImmediately() {
        doReturn(List.of(link(1L, "A", FriendLink.STATUS_ENABLED)))
                .when(friendLinkService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertEquals(1, friendLinkService.listPublic().size());

        doReturn(true).when(friendLinkService).removeById(1L);
        friendLinkService.delete(1L);
        doReturn(List.of()).when(friendLinkService)
                .list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        assertEquals(0, friendLinkService.listPublic().size());
        verify(friendLinkService, times(2)).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void page_mapsRecordsToPageResult() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<FriendLink> mpPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
        mpPage.setRecords(List.of(link(3L, "C", FriendLink.STATUS_DISABLED)));
        doReturn(mpPage).when(friendLinkService)
                .page(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                        any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        var result = friendLinkService.page(1, 20, "C", FriendLink.STATUS_DISABLED);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("C", result.getRecords().get(0).getName());
        assertEquals(FriendLink.STATUS_DISABLED, result.getRecords().get(0).getStatus());
    }
}
