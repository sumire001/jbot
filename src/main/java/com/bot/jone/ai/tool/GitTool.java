package com.bot.jone.ai.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.git.GitConfig;
import com.bot.jone.git.GitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 远程 git 仓库查询工具：让 LLM 在对话中主动查看远程仓库，
 * 例如“我们仓库最近改了啥”“读一下 README”“现在有哪些分支/PR”。
 *
 * 内部用 JGit 直连 git 协议，默认不依赖任何平台 API（仅 issues 可选开启）。
 * 实现 BotTool 后由 Spring 自动收集进 ToolRegistry，无需手工登记。
 */
@Component
public class GitTool implements BotTool {

    @Autowired
    private GitService gitService;

    @Autowired
    private GitConfig gitConfig;

    @Override
    public String name() {
        return "git_query";
    }

    @Override
    public String description() {
        return "查询一个远程 git 仓库的信息（如 GitHub/GitLab/Gitee 或任意 git 远程地址）。"
                + "可执行的动作 action 包括："
                + "branches（列出分支）、tags（列出标签）、pr_list（列出 PR/MR）、"
                + "log（最近提交记录，可指定 branch 与 limit 条数）、"
                + "read_file（读取某个文件内容，需 path 参数，可指定 ref）、"
                + "diff（两个引用间的变更文件清单，可指定 base 与 head）、"
                + "issues（列出打开中的 issues，需显式在配置中开启）。"
                + "当用户想了解某个代码仓库的状态、历史、文件内容或变更时调用本工具，"
                + "用返回的准确事实回答，而不是凭记忆编造。repo 缺省时使用配置的默认仓库。";
    }

    @Override
    public JSONObject getParameters() {
        JSONObject params = new JSONObject();
        params.put("type", "object");

        JSONObject props = new JSONObject();
        props.put("action", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description",
                        "要执行的查询动作：branches / tags / pr_list / log / read_file / diff / issues"));
        props.put("repo", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description", "远程仓库地址（如 https://github.com/owner/repo.git）。"
                        + "省略则使用配置的默认仓库 bot.git.default-repo。"));
        props.put("branch", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description", "分支名或引用，用于 log 动作（缺省为默认分支 HEAD）。"));
        props.put("path", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description", "仓库内相对路径，用于 read_file 动作（如 README.md、src/main/java/A.java）。"));
        props.put("ref", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description", "引用（分支名/commit sha/tag），用于 read_file 读取特定版本的文件（缺省 HEAD）。"));
        props.put("base", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description", "diff 的基准引用（缺省 HEAD~1）。"));
        props.put("head", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description", "diff 的目标引用（缺省 HEAD）。"));
        props.put("limit", new JSONObject()
                .fluentPut("type", "integer")
                .fluentPut("description", "log 返回的提交条数上限（缺省 10）。"));

        params.put("properties", props);
        params.put("required", new JSONArray().fluentAdd("action"));
        return params;
    }

    @Override
    public String execute(JSONObject args) {
        String action = args == null ? null : args.getString("action");
        if (action == null || action.trim().isEmpty()) {
            return "[参数缺失] git_query 需要 action 参数";
        }
        String repo = args.getString("repo");
        if (repo == null || repo.trim().isEmpty()) {
            repo = gitConfig.getDefaultRepo();
        }
        if (repo == null || repo.trim().isEmpty()) {
            return "[参数缺失] 未提供 repo，且未配置默认仓库 bot.git.default-repo";
        }

        switch (action.trim().toLowerCase()) {
            case "branches":
                return gitService.getBranches(repo);
            case "tags":
                return gitService.getTags(repo);
            case "pr_list":
            case "pr":
                return gitService.getPrList(repo);
            case "log":
                int limit = args.containsKey("limit") ? args.getInteger("limit") : 10;
                return gitService.getLog(repo, args.getString("branch"), limit);
            case "read_file":
            case "read":
                return gitService.readFile(repo, args.getString("path"), args.getString("ref"));
            case "diff":
                return gitService.getDiff(repo, args.getString("base"), args.getString("head"));
            case "issues":
                return gitService.getIssues(repo);
            default:
                return "[未知动作] 不支持的 action: " + action
                        + "（可选：branches / tags / pr_list / log / read_file / diff / issues）";
        }
    }
}
