package com.bot.jone.git;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * git 仓库查询配置（前缀 bot.git）。
 *
 * 设计原则：默认完全不依赖任何平台 API（GitHub/GitLab/Gitee 的 REST 接口），
 * 而是用 JGit 直连 git 协议读取远程仓库。仅当显式开启 issues 时才调用平台 API。
 *
 * 真实敏感值（如 token）建议写在外部 config/application.yml，并通过环境变量 GIT_TOKEN 注入，勿提交进仓库。
 */
@Data
@Component
@ConfigurationProperties(prefix = "bot.git")
public class GitConfig {

    /** 克隆缓存目录（相对工作目录）。每个被查询过的仓库会在此浅克隆一份，避免重复拉取。 */
    private String cacheDir = "data/git-cache";

    /** 网络超时（秒），作用于 clone / fetch / ls-remote。 */
    private int timeoutSeconds = 30;

    /** 浅克隆深度：控制 log 能回溯的历史提交数，也决定首次克隆的体积。 */
    private int cloneDepth = 20;

    /**
     * 默认仓库地址。GitTool 的 repo 参数缺省时使用。
     * 例如 https://github.com/owner/repo.git （公开库可留空 token；私有库需配 token）。
     */
    private String defaultRepo;

    /**
     * HTTPS 鉴权用户名（配合 token 作为密码）。
     * GitHub / Gitee / GitLab 的 PAT 通常接受任意非空用户名，默认 "git" 即可。
     */
    private String username = "git";

    /**
     * 个人访问令牌（PAT），用于私有仓库。
     * 优先读取环境变量 GIT_TOKEN；为空时回落到本配置项。切勿提交进仓库。
     */
    private String token;

    /**
     * 是否启用 issues 查询。默认关闭——因为 issues 是平台概念、只能用平台 API 获取，
     * 与“不依赖别人 API”的默认取向相悖。设为 true 且配置 token 后才生效。
     */
    private boolean issuesEnabled = false;

    /** issues 单次返回条数上限。 */
    private int issuesMax = 10;
}
