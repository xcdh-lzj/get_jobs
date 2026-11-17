package com.getjobs.application.config;

import com.getjobs.application.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.regex.Pattern;

@Configuration
@RequiredArgsConstructor
public class BossAccountWebConfig implements WebMvcConfigurer {
    private final BossAccountService accounts;
    private final BossAccountLocks locks;
    private static final String EDIT = BossAccountWebConfig.class.getName()+".editing";
    private static final Pattern ACCOUNT_PATH = Pattern.compile("/api/boss/accounts/(\\d+)(?:/.*)?");

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                BossAccountContext.clear();
                if ("OPTIONS".equals(request.getMethod())) return true;
                long id=1;
                String raw=request.getParameter("accountId");
                try { if (raw!=null) id=Long.parseLong(raw); }
                catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账号 ID 无效"); }
                var match=ACCOUNT_PATH.matcher(request.getRequestURI());
                if (match.matches()) {
                    long pathId;
                    try { pathId=Long.parseLong(match.group(1)); }
                    catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账号 ID 无效"); }
                    if (raw!=null && id!=pathId) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账号参数不一致");
                    id=pathId;
                }
                accounts.require(id);
                String path=request.getRequestURI();
                boolean editing=(!"GET".equals(request.getMethod()) &&
                        (path.startsWith("/api/boss/config") || path.startsWith("/api/boss/accounts/") || path.endsWith("/logout") || path.endsWith("/review/settings")))
                        || path.endsWith("/reload");
                if (editing) { locks.beginEdit(id); request.setAttribute(EDIT,true); }
                BossAccountContext.use(id);
                return true;
            }
            private void clear(HttpServletRequest request) {
                BossAccountContext.clear();
                if (Boolean.TRUE.equals(request.getAttribute(EDIT))) { request.removeAttribute(EDIT); locks.endEdit(); }
            }
            @Override public void afterCompletion(HttpServletRequest r,HttpServletResponse s,Object h,Exception e) { clear(r); }
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest r,HttpServletResponse s,Object h) { clear(r); }
        }).addPathPatterns("/api/boss/**");
    }
}
