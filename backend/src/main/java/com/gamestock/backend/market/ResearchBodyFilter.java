package com.gamestock.backend.market;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;

/** Bounds new editor/community payloads before JSON parsing, including chunked requests. */
@Component
public class ResearchBodyFilter extends OncePerRequestFilter {
    static final int LIMIT=131072;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !(path.startsWith("/api/quant/") || path.matches("/api/stocks/[^/]+/(community(?:/.*)?|chart-analysis)"))
            || !java.util.Set.of("POST","PUT","PATCH").contains(request.getMethod());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getContentLengthLong()>LIMIT) {reject(response);return;}
        byte[] bytes=request.getInputStream().readNBytes(LIMIT+1);
        if(bytes.length>LIMIT) {reject(response);return;}
        HttpServletRequestWrapper wrapped=new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input=new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    public int read() {return input.read();}
                    public int read(byte[] b,int off,int len) {return input.read(b,off,len);}
                    public boolean isFinished() {return input.available()==0;}
                    public boolean isReady() {return true;}
                    public void setReadListener(ReadListener listener) {throw new UnsupportedOperationException("Synchronous JSON request");}
                };
            }
            @Override public BufferedReader getReader() {return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        };
        chain.doFilter(wrapped,response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"Request body exceeds 128 KiB\"}");
    }
}
