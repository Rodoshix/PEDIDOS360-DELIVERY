package cl.duoc.pedidos360.rabbitadmin;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.LoggerFactory;

/** No URL/query/body/token/exception logging, including on rejected requests. */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminAuditFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws ServletException,IOException {
        String id=UUID.randomUUID().toString();
        try (var ignored=org.slf4j.MDC.putCloseable("rabbitAdminRequestId",id)) {
            boolean completed=false;
            try { chain.doFilter(request,response); completed=true; }
            finally { LoggerFactory.getLogger(getClass()).info("RabbitAdmin requestId={} status={}",id,completed?response.getStatus():500); }
        }
    }
}
