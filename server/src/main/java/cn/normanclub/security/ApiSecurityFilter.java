package cn.normanclub.security;

import java.io.*;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.auth.AuthService;
import cn.normanclub.common.*;
import cn.normanclub.config.ClubConfig;
import static cn.normanclub.common.ApiException.require;

public class ApiSecurityFilter extends OncePerRequestFilter {
    private final AuthService auth;private final AbuseService abuse;private final ClubConfig config;private final JsonMapper json;
    public ApiSecurityFilter(AuthService auth,AbuseService abuse,ClubConfig config,JsonMapper json){this.auth=auth;this.abuse=abuse;this.config=config;this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String requestId=UUID.randomUUID().toString();request.setAttribute("requestId",requestId);response.setHeader("X-Request-ID",requestId);response.setHeader("Cache-Control","no-store");
        try{
            require(Set.of("GET","POST","PATCH","DELETE","OPTIONS").contains(request.getMethod()),405,"请求方法不支持");
            require(request.getContentLengthLong()<=65_536,413,"请求内容过大");
            String origin=request.getHeader("Origin");require(origin==null||config.allowedOrigins.contains(origin),403,"请求来源不受信任");
            String ip=abuse.ip(request);abuse.check(ip);abuse.limit("api-ip",ip,1200,60_000);
            String audience="staff".equals(request.getHeader("X-Session-Audience"))?"staff":"user";
            String header=request.getHeader("Authorization");boolean bearer=header!=null&&header.startsWith("Bearer ");
            String token=bearer?header.substring(7):null;
            if(!bearer&&request.getCookies()!=null)for(Cookie cookie:request.getCookies())if(cookie.getName().equals("norman-"+audience+"-session"))token=cookie.getValue();
            Actor actor=auth.authenticate(token,audience,bearer);
            if(actor!=null){
                if(!Set.of("GET","OPTIONS").contains(request.getMethod())&&!actor.bearer())auth.csrf(actor,request.getHeader("X-CSRF-Token"));
                abuse.limit("api-account",actor.id(),300,60_000);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_"+actor.role().toUpperCase(Locale.ROOT)))));
            }
            String override=request.getHeader("X-HTTP-Method-Override");
            HttpServletRequest bounded=new BoundedRequest(request);
            if(override!=null){
                require(request.getMethod().equals("POST")&&override.equals("PATCH")&&(request.getRequestURI().matches("/v1/products/[A-Za-z0-9_-]+")||request.getRequestURI().matches("/v1/admin/players/[A-Za-z0-9_-]+")),400,"方法覆盖不受支持");
                bounded=new HttpServletRequestWrapper(bounded){@Override public String getMethod(){return "PATCH";}};
            }
            chain.doFilter(bounded,response);
        }catch(ApiException ex){response.setStatus(ex.status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),new ApiResponse<>(ex.status,ex.getMessage(),null,requestId));}
    }
    static class BoundedRequest extends HttpServletRequestWrapper {
        BoundedRequest(HttpServletRequest request){super(request);}
        @Override public ServletInputStream getInputStream()throws IOException {
            ServletInputStream input=super.getInputStream();return new ServletInputStream(){int read;
                private void count(int n)throws IOException{read+=Math.max(n,0);if(read>65_536)throw new IOException("Request body exceeds limit");}
                @Override public int read()throws IOException{int n=input.read();if(n>=0)count(1);return n;}
                @Override public int read(byte[] b,int off,int len)throws IOException{int n=input.read(b,off,Math.min(len,65_537-read));count(n);return n;}
                @Override public boolean isFinished(){return input.isFinished();}@Override public boolean isReady(){return input.isReady();}@Override public void setReadListener(ReadListener listener){input.setReadListener(listener);}
            };
        }
        @Override public BufferedReader getReader()throws IOException{return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
    }
}
