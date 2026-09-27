package com.leyon.backend.interceptor;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import com.leyon.backend.service.OpenApiDenialMeter;
import com.leyon.backend.service.RateLimitService;
import com.leyon.backend.util.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * 开放 OpenAPI 语音 WebSocket 握手鉴权拦截器（v2.16 开放语音）
 * 拦截 /api/open/ws-voice/* 端点，以 API Key 鉴权（与 JWT 通道隔离）：
 * - 浏览器 WebSocket 无法设置自定义请求头，故历史上进过 URL 查询参数 ?apiKey=xxx；
 *   **自 v2.50 起该通道默认关闭**，须显式开 {@code OPENAPI_WS_URL_KEY_ALLOWED=true} 才采纳（详见下方 v2.50 段）；
 * - 非浏览器客户端（curl/mobile/server）可附带 X-API-Key 请求头。
 * 校验通过后将第三方应用属主身份（userId/appId）与通道标记写入会话属性，
 * VoiceSignalingHandler 检测到 userId 非空即视为已认证，全链路复用。
 * <p>
 * v2.45 在此补两道，且顺序刻意为「限流 → 验 Key → 验能力」：
 * ① **握手限流**（OPEN_WS 档，每来源 10 次/分钟）。握手不经 {@code HandlerInterceptor}，
 * 所以把路径加进 {@code RateLimitInterceptor} 是无效的——必须在握手链里自己调
 * {@link RateLimitService}。放在验 Key 之前，否则这个端点等于一台免费的密钥在线试错机（每次尝试都查库）。
 * ② **能力判定**（需要 {@code voice}）。此前 scopes 全仓无读判点，任何有效 Key 都能发起语音会话。
 * <p>
 * v2.50（候选 ㉙）：URL 查询参数通道改为**显式开闸**（{@code app.openapi.ws-url-key-allowed}，默认 false）。
 * 理由是长期凭据一旦进过 URL 就已经泄露——nginx {@code access_log}、浏览器历史、任何中间件都可能留着它，
 * 而本项目的语音能力尚未对外开放（见手册 6.7 决策 3），此刻收口的代价是零。
 * 浏览器直连的替代通道是"短寿命一次性握手票据"，属语音二期前置（手册 7.4 候选 ㉞）。
 * 开关打开后每次走 URL 通道都会记 {@code URL_KEY_USED} 进 {@link OpenApiDenialMeter}，
 * 让"还剩多少集成在用旧通道"成为可查的数字，而不是靠猜。
 *
 * @author leyon
 */
@Component
public class OpenApiWebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OpenApiWebSocketAuthInterceptor.class);

    /** API Key 请求头 */
    private static final String HEADER_API_KEY = "X-API-Key";
    /** URL 查询参数名 */
    private static final String PARAM_API_KEY = "apiKey";

    /** 会话属性：用户ID */
    public static final String SESSION_ATTR_USER_ID = "userId";
    /** 会话属性：第三方应用ID */
    public static final String SESSION_ATTR_APP_ID = "appId";
    /** 会话属性：OpenAPI 通道标记 */
    public static final String SESSION_ATTR_OPEN_API = "openApi";

    private final ApiAppService apiAppService;

    /** 握手限流的计数单点（与 HTTP 侧同实现、同档表，v2.45） */
    private final RateLimitService rateLimitService;

    /** 来源地址判定：握手与 HTTP 共用同一份代理信任配置（v2.44 的判据，v2.45 接到握手上） */
    private final ClientIpResolver clientIpResolver;

    /** 拒绝与风险事件台账（v2.50 · 候选 ㉜） */
    private final OpenApiDenialMeter denialMeter;

    /** 是否采纳 URL 查询参数里的 API Key（v2.50 · 候选 ㉙：默认不采纳，长期凭据不该出现在 URL 里） */
    private final boolean urlKeyAllowed;

    public OpenApiWebSocketAuthInterceptor(ApiAppService apiAppService,
                                           RateLimitService rateLimitService,
                                           ClientIpResolver clientIpResolver,
                                           OpenApiDenialMeter denialMeter,
                                           @Value("${app.openapi.ws-url-key-allowed:false}") boolean urlKeyAllowed) {
        this.apiAppService = apiAppService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.denialMeter = denialMeter;
        this.urlKeyAllowed = urlKeyAllowed;
    }

    /**
     * 握手前拦截：限流 → 校验 API Key → 校验能力，通过后注入属主身份到会话属性
     */
    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
        // 1. 握手限流（先于查库：不限流就等于把"每次尝试都打一次 DB 的密钥校验口"开放出来）
        String clientIp = clientIpResolver.resolve(request);
        RateLimitService.Decision decision =
                rateLimitService.tryAcquire(RateLimitService.Tier.OPEN_WS, clientIp);
        if (!decision.allowed()) {
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            response.getHeaders().add("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            log.warn("开放语音握手超限：来源 {} 建议 {} 秒后重试", clientIp, decision.retryAfterSeconds());
            denialMeter.record(OpenApiDenialMeter.Kind.RATE_LIMITED, null);
            return false;
        }
        // 2. 提取并校验 API Key（缺少 Key 直接拒绝，开放语音无延后认证，必须在握手阶段完成）
        KeyCandidate candidate = extractApiKey(request);
        // 开关关闭时 URL 通道按"根本没带凭据"处理，但单独记一格：
        // "还有多少集成在用旧通道"正是决定这个开关能不能一直关着的依据，不能混进 KEY_MISSING
        if (candidate != null && candidate.fromUrl() && !urlKeyAllowed) {
            log.warn("开放语音握手带 URL 查询参数 Key，但通道自 v2.50 起默认关闭 ⇒ 按未携带凭据拒绝");
            denialMeter.record(OpenApiDenialMeter.Kind.URL_KEY_REJECTED, null);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (candidate == null) {
            denialMeter.record(OpenApiDenialMeter.Kind.KEY_MISSING, null);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        ApiApp app = apiAppService.authByApiKey(candidate.key());
        if (app == null || app.getEnabled() == null || app.getEnabled() != ApiApp.ENABLED) {
            denialMeter.record(OpenApiDenialMeter.Kind.KEY_INVALID, null);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        // 3. 能力判定：语音会话需要 voice（缺它回 403，与"Key 不对"的 401 区分开）
        if (!app.hasScope(ApiApp.SCOPE_VOICE)) {
            log.warn("开放语音握手被拒：appId={} 未开通 {}", app.getId(), ApiApp.SCOPE_VOICE);
            denialMeter.record(OpenApiDenialMeter.Kind.SCOPE_DENIED, app.getId());
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        // 凭据已从 URL 换取会话：把这次"旧通道使用"记进台账（不记 Key 本身，也不记 URL）
        if (candidate.fromUrl()) {
            log.warn("开放语音握手仍走 URL 查询参数带 Key：appId={}，该凭据可能已进入中间件日志（v2.50 起默认关闭此通道）",
                    app.getId());
            denialMeter.record(OpenApiDenialMeter.Kind.URL_KEY_USED, app.getId());
        }
        // 以第三方应用属主身份接入，与普通用户同路径执行助手归属校验与配额统计
        attributes.put(SESSION_ATTR_USER_ID, app.getUserId());
        attributes.put(SESSION_ATTR_APP_ID, app.getId());
        attributes.put(SESSION_ATTR_OPEN_API, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler, @Nullable Exception exception) {
        // 无需处理
    }

    /** 提取结果：凭据值 + 它是不是从 URL 查询参数来的 */
    private record KeyCandidate(String key, boolean fromUrl) {
    }

    /**
     * 多方式提取 API Key：X-API-Key 请求头优先，其次 URL 查询参数 apiKey。
     * <p>
     * 这里**照旧识别** URL 通道并由调用方决定是否采纳（见 {@code beforeHandshake} 的开关判定）：
     * 若在提取处就直接丢弃，"还有多少集成在用旧通道"这一格就永远记不上，关闸后就成了瞎子。
     */
    private KeyCandidate extractApiKey(ServerHttpRequest request) {
        String headerKey = request.getHeaders().getFirst(HEADER_API_KEY);
        if (headerKey != null && !headerKey.isBlank()) {
            return new KeyCandidate(headerKey, false);
        }
        URI uri = request.getURI();
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        String urlKey = parseApiKeyFromQuery(query);
        return urlKey == null ? null : new KeyCandidate(urlKey, true);
    }

    /**
     * 解析 URL 查询参数中的 apiKey（app_key 为 UUID hex，无需 URL 解码）
     */
    private String parseApiKeyFromQuery(String query) {
        String[] params = query.split("&");
        for (String param : params) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && PARAM_API_KEY.equals(kv[0]) && !kv[1].isBlank()) {
                return kv[1];
            }
        }
        return null;
    }
}