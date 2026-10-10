package com.zer0drv.blog.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.core.importer.ImportOption;
import com.zer0drv.blog.admin.service.impl.AdminArticleServiceImpl;
import com.zer0drv.blog.admin.service.impl.AdminStatsServiceImpl;
import com.zer0drv.blog.category.service.impl.CategoryServiceImpl;
import com.zer0drv.blog.tag.service.impl.TagServiceImpl;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Issue #24 第四步（收官步）：架构边界红线测试。
 *
 * <p>前三步（PR #27/#28/#29）已把 article 域对外契约收窄到 {@code article.api} 包
 * （ArticleCatalog/ArticleRef/ArticleFeedEntry/ArticleCascade），本步用 ArchUnit 把
 * "域外不得触碰 article 内部"固化为 CI 红线：新增违例会让测试失败，而不是悄悄累积。
 *
 * <p>分析范围仅含主代码（{@code DoNotIncludeTests}），规则对自身豁免清单显式枚举——
 * 豁免即文档，只允许缩小、不允许扩大。
 *
 * @author Yoruhaki
 */
@AnalyzeClasses(packages = "com.zer0drv.blog", importOptions = ImportOption.DoNotIncludeTests.class)
class ArticleBoundaryArchTest {

    /**
     * 核心红线已知残留违例豁免清单——<b>只允许缩小，不允许扩大</b>。
     *
     * <p>每条约清一个违例，就从这里删掉对应一行；任何新增违例的正确归宿是重构代码，
     * 而不是加进这张表。来源：Issue #24 前三步 PR 的残留清单。
     */
    private static final Class<?>[] ARTICLE_INTERNALS_KNOWN_VIOLATORS = {
            // PR #28 残留：回收站 SQL、任意状态分页、标记写操作——管理端有权绕过可见性契约
            AdminArticleServiceImpl.class,
            // PR #28 残留：统计口径（仅 status=PUBLISHED，无 publish_time 条件），与可见性谓词语义不符
            AdminStatsServiceImpl.class,
            // PR #28 残留：delete 分类前引用计数需覆盖任意状态文章
            CategoryServiceImpl.class,
            // 本步（④）新发现，不在 PR #28/#29 清单内：delete 标签时清理 article_tag 关联行。
            // 与 CategoryServiceImpl.delete 同类问题，建议后续以 ArticleCascade 式 SPI
            // 或 ArticleCatalog 方法收口，收口后从此清单删除
            TagServiceImpl.class,
    };

    /**
     * 核心红线：article 域内部包（mapper / domain / service.impl）只允许被
     * {@code ..article..} 包内访问——域外唯一合法依赖是 {@code article.api} 契约包
     * （及约定俗成的 {@code article.vo} 视图投影）。
     */
    @ArchTest
    static final ArchRule article_internals_only_accessible_within_article =
            noClasses()
                    .that().resideOutsideOfPackage("..article..")
                    .and().doNotBelongToAnyOf(ARTICLE_INTERNALS_KNOWN_VIOLATORS)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..article.mapper..", "..article.domain..", "..article.service.impl..")
                    .as("article 域内部包（mapper/domain/service.impl）只允许被 article 包内访问，"
                            + "域外唯一契约是 article.api（豁免见 ARTICLE_INTERNALS_KNOWN_VIOLATORS）")
                    .allowEmptyShould(true);

    /**
     * api 纯度：{@code article.api} 契约包不得依赖 MyBatis-Plus——这是外模块单测
     * 摆脱 {@code TableInfoHelper} 初始化样板的前提（PR #28 引入该包的动机）。
     */
    @ArchTest
    static final ArchRule article_api_stays_free_of_mybatis_plus =
            noClasses()
                    .that().resideInAPackage("..article.api..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.baomidou.mybatisplus..")
                    .as("article.api 是跨模块窄契约，不得依赖 MyBatis-Plus 类型")
                    .allowEmptyShould(true);

    /**
     * 全局兜底：Controller 不直连任何模块的 Mapper，持久化访问一律走 service/api 层。
     * 当前零违例、零豁免；留着它挡住"顺手在 Controller 里注入 Mapper"的未来退化。
     */
    @ArchTest
    static final ArchRule controllers_do_not_depend_on_mappers =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..mapper..")
                    .as("Controller 不得直接依赖任何模块的 Mapper 包")
                    .allowEmptyShould(true);
}
