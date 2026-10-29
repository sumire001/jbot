package com.bot.jone.sekai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 拉取玩家详细资料。对应 lunabot：
 *   - profile.get_detailed_profile()（绑定解析 / 隐藏校验 / 缓存兜底）
 *
 * 后端改为 Haruki 工具箱「公有读」接口（公开读，无需 token / OAuth2）：
 *   GET https://suite-api.haruki.seiunx.com/public/{region}/suite/{uid}
 *   - 只需 User-Agent，无需 Authorization（仅写入才需 OAuth2 鉴权）
 *   - 不支持 mode / filter（直接返回完整 profile，含 userMusicResults）
 *   - 404 = 玩家未上传到 Haruki 工具箱 / ID 错误，body: {"status":404,"message":"not found"}
 *
 * 用项目惯例的原生 HttpURLConnection（对齐 ImageDownloader / GalleryService），零额外依赖。
 * 内存缓存交给 SekaiService（java 自带 Map + TTL）。
 */
@Slf4j
@Component
public class SekaiFetcher {

    /**
     * 公开读拉取玩家 profile。
     *
     * @param baseUrl 含 {uid} 占位的数据源地址（如 Haruki 公有接口）
     * @param gameUid 游戏内 ID
     * @return profile 原始 JSON 字符串
     * @throws ProfileFetchException 携带状态码与友好提示；404 区分"玩家未找到"
     */
    public String fetchProfile(String baseUrl, long gameUid) {
        String url = baseUrl.replace("{uid}", String.valueOf(gameUid));
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            // Haruki 公开读：仅 User-Agent，无需 Authorization
            conn.setRequestProperty("User-Agent", "J-One-Sekai/1.0");
            int code = conn.getResponseCode();
            if (code == 404) {
                // 玩家未上传到 Haruki 工具箱，或游戏ID错误
                throw new ProfileFetchException(404,
                        "未找到该玩家（未上传到 Haruki 工具箱，或游戏ID错误）");
            }
            if (code != 200) {
                String body = readString(conn.getErrorStream());
                throw new ProfileFetchException(code, body);
            }
            return readString(conn.getInputStream());
        } catch (ProfileFetchException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Sekai] 拉取 profile 失败: {}", url, e);
            throw new RuntimeException("拉取玩家数据失败: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 拉取失败时抛出，携带状态码与响应体（用于区分 local/haruki 错误）。 */
    public static class ProfileFetchException extends RuntimeException {
        public final int status;
        public final String body;

        public ProfileFetchException(int status, String body) {
            super("profile fetch failed: " + status + " " + body);
            this.status = status;
            this.body = body;
        }
    }

    private static String readString(InputStream is) {
        if (is == null) {
            return "";
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
