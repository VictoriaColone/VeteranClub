package cn.normanclub;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.acceptance.AcceptanceGuard;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "ACCEPTANCE_MODE=true","server.address=127.0.0.1","club.development=true","club.cookie-secure=false","club.jobs-enabled=false","club.sms-provider=disabled",
    "club.allowed-origins=http://127.0.0.1:4190","spring.datasource.url=jdbc:h2:mem:norman_acceptance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=",
    "club.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","club.hash-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","logging.file.name=target/acceptance/integration.log"})
class AcceptanceIntegrationTest {
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry registry){
        if(System.getenv("TEST_ACCEPTANCE_DB_URL")!=null){
            registry.add("spring.datasource.url",()->System.getenv("TEST_ACCEPTANCE_DB_URL"));
            registry.add("spring.datasource.username",()->System.getenv("TEST_ACCEPTANCE_DB_USERNAME"));
            registry.add("spring.datasource.password",()->System.getenv("TEST_ACCEPTANCE_DB_PASSWORD"));
        }
    }
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired cn.normanclub.admin.StaffService staff;
    @org.springframework.beans.factory.annotation.Autowired cn.normanclub.auth.AuthMapper authDb;
    @org.springframework.beans.factory.annotation.Autowired cn.normanclub.auth.AuthService auth;
    @org.springframework.beans.factory.annotation.Autowired cn.normanclub.acceptance.AcceptanceSeed seed;
    static final String STAFF_PASSWORD="AcceptanceOnly_42Password";
    void manager(){staff.provisionManager(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(STAFF_PASSWORD));}
    @org.springframework.beans.factory.annotation.Autowired cn.normanclub.payment.PaymentService payments;
    @org.springframework.beans.factory.annotation.Autowired AcceptanceGuard guard;
    final JsonMapper json=new JsonMapper();
    HttpClient client;Map<String,String> csrfTokens;
    @BeforeEach void client(){manager();client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();csrfTokens=new HashMap<>();}
    HttpResponse<String> request(String method,String path,String data,String audience)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Origin","http://127.0.0.1:4190").header("X-Client-Type","h5").header("X-Session-Audience",audience).header("Content-Type","application/json");
        String csrf=csrfTokens.get(audience);if(csrf!=null)builder.header("X-CSRF-Token",csrf);
        if(path.equals("/v1/orders")&&method.equals("POST"))builder.header("Idempotency-Key",UUID.randomUUID().toString());
        return client.send(builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode login(String role,String user)throws Exception{
        String audience=role.equals("user")?"user":"staff";
        var response=role.equals("user")?request("POST","/v1/auth/sms/login/acceptance",json.writeValueAsString(Map.of("role",role,"accountId",user)),audience):request("POST","/v1/auth/staff/login",json.writeValueAsString(Map.of("username","norman_app_manager","password",STAFF_PASSWORD)),audience);assertEquals(200,response.statusCode(),response.body());
        for(String cookie:response.headers().allValues("Set-Cookie"))if(cookie.startsWith("norman-"+(role.equals("user")?"user":"staff")+"-csrf="))csrfTokens.put(role.equals("user")?"user":"staff",cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';')));
        return json.readTree(response.body()).path("data");
    }
    @Test void productionAndPublicDatabaseSettingsCannotStartAcceptancePackage(){
        var origins=List.of("http://127.0.0.1:4190");String database="jdbc:mysql://127.0.0.1:3308/norman_acceptance?sslMode=REQUIRED";
        assertDoesNotThrow(()->AcceptanceGuard.validate(true,"true","127.0.0.1",database,origins));
        assertThrows(IllegalStateException.class,()->AcceptanceGuard.validate(false,"true","127.0.0.1",database,origins));
        assertThrows(IllegalStateException.class,()->AcceptanceGuard.validate(true,"false","127.0.0.1",database,origins));
        assertThrows(IllegalStateException.class,()->AcceptanceGuard.validate(true,"true","0.0.0.0",database,origins));
        assertThrows(IllegalStateException.class,()->AcceptanceGuard.validate(true,"true","127.0.0.1",database.replace("norman_acceptance","norman_club"),origins));
        assertThrows(IllegalStateException.class,()->AcceptanceGuard.validate(true,"true","127.0.0.1",database,List.of("https://public.example.com")));
        var external=new org.springframework.mock.web.MockHttpServletRequest();external.setRemoteAddr("203.0.113.10");external.addHeader("X-Client-Type","h5");external.addHeader("Origin",origins.getFirst());
        assertThrows(cn.normanclub.common.ApiException.class,()->guard.check(external));
    }
    @Test void rdsRequiresExactHostDedicatedDatabaseAndVerifiedTls(){
        String host="rm-example.mysql.rds.aliyuncs.com",url="jdbc:mysql://"+host+":3306/norman_acceptance?sslMode=VERIFY_IDENTITY";
        var origins=List.of("http://127.0.0.1:4190");
        assertDoesNotThrow(()->AcceptanceGuard.validate(true,"true","127.0.0.1",url,origins,host));
        assertThrows(IllegalStateException.class,()->AcceptanceGuard.validate(true,"true","127.0.0.1",url,origins));
        for(String invalid:List.of(url.replace(host,"rm-other.mysql.rds.aliyuncs.com"),url.replace("norman_acceptance","norman_club"),
            url.replace("VERIFY_IDENTITY","REQUIRED"),url.replace(":3306/",":3307/"),url+"&SSLMODE=DISABLED",
            url+"&socketFactory=custom.Factory",url+"&useSSL=false",url+"&trustCertificateKeyStoreUrl=file:/tmp/other.jks"))
            assertThrows(IllegalStateException.class,()->AcceptanceGuard.validate(true,"true","127.0.0.1",invalid,origins,host),invalid);
        assertThrows(IllegalStateException.class,()->AcceptanceGuard.validate(true,"true","0.0.0.0",url,origins,host));
        assertThrows(IllegalStateException.class,()->AcceptanceGuard.validate(true,"true","127.0.0.1",url,List.of("https://public.example.com"),host));
    }
    @Test void demoIdentityHasDatabaseQualificationsAndNormalApiStillNeedsSession()throws Exception{
        assertEquals(401,request("GET","/v1/orders",null,"user").statusCode());assertEquals("u-boss",login("user","u-boss").path("userId").asText());
        assertEquals(200,request("GET","/v1/products",null,"user").statusCode());assertEquals(403,request("GET","/v1/admin/users",null,"user").statusCode());
        assertEquals(403,request("POST","/v1/auth/sms/login/acceptance","{\"role\":\"user\",\"accountId\":\"other\"}","user").statusCode());
        for(String role:List.of("admin","customer_service","finance"))assertEquals(403,request("POST","/v1/auth/sms/login/acceptance",json.writeValueAsString(Map.of("role",role)),"staff").statusCode());
        assertEquals(401,request("GET","/v1/admin/users",null,"staff").statusCode());
        assertEquals("admin",login("admin","").path("role").asText());assertEquals(200,request("GET","/v1/admin/users",null,"staff").statusCode());
        var foreign=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/auth/sms/login/acceptance")).header("Origin","https://evil.example.com").header("X-Client-Type","h5").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"role\":\"admin\"}")).build();
        assertEquals(403,client.send(foreign,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void legacyDemoStaffSessionsAreRevokedOnStartup(){
        var legacy=new cn.normanclub.auth.AuthMapper.Staff();legacy.id="acceptance-admin";legacy.username="acceptance-admin";legacy.name="旧演示管理员";legacy.role="admin";legacy.passwordHash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(STAFF_PASSWORD);legacy.createdAt=System.currentTimeMillis();legacy.lastTotpStep=-1;authDb.insertStaff(legacy);
        var old=auth.staffLogin(legacy.username,STAFF_PASSWORD,"192.0.2.200");assertNotNull(auth.authenticate(old.token(),"staff",false));
        seed.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
        assertTrue(authDb.staff(legacy.id).disabled);assertNull(auth.authenticate(old.token(),"staff",false));assertThrows(cn.normanclub.common.ApiException.class,()->auth.staffLogin(legacy.username,STAFF_PASSWORD,"192.0.2.200"));
    }
    @Test void escortFlowPersistsPaymentMembersAndAutomaticCreditWithoutRealMoney()throws Exception{
        login("user","u-boss");
        var created=request("POST","/v1/orders","{\"gameId\":\"delta\",\"region\":\"国服\",\"serviceType\":\"escort\",\"task\":\"100w纯带出\",\"taskPrice\":12800,\"quantity\":1}","user");assertEquals(200,created.statusCode(),created.body());String id=json.readTree(created.body()).path("data").path("id").asText();
        var paid=request("POST","/v1/acceptance/orders/"+id+"/pay","{}","user");assertEquals(200,paid.statusCode(),paid.body());assertEquals("waiting",json.readTree(paid.body()).path("data").path("status").asText());
        assertEquals(200,request("POST","/v1/acceptance/orders/"+id+"/pay","{}","user").statusCode());
        login("user","u-player");assertEquals(200,request("POST","/v1/orders/"+id+"/accept","{}","user").statusCode());
        assertEquals(200,request("POST","/v1/orders/"+id+"/add-assistant","{\"playerId\":\"p7\"}","user").statusCode());
        assertEquals(200,request("POST","/v1/orders/"+id+"/start","{}","user").statusCode());
        assertEquals(200,request("POST","/v1/orders/"+id+"/finish","{\"taskCompleted\":true,\"note\":\"验收任务已达成\"}","user").statusCode());
        login("user","u-boss");var confirmed=request("POST","/v1/orders/"+id+"/confirm","{}","user");assertEquals(200,confirmed.statusCode(),confirmed.body());assertEquals("credited",json.readTree(confirmed.body()).path("data").path("settlementStatus").asText());
        login("admin","");var list=request("GET","/v1/orders",null,"staff");assertEquals(200,list.statusCode());assertTrue(list.body().contains(id));
    }
    @Test void hourlyFastForwardAndRefundStillUseDatabaseStateAndChannelConfirmation()throws Exception{
        login("user","u-boss");
        var created=request("POST","/v1/orders","{\"gameId\":\"delta\",\"region\":\"国服\",\"serviceType\":\"hourly\",\"playerId\":\"p-hourly\",\"quantity\":1}","user");assertEquals(200,created.statusCode(),created.body());String id=json.readTree(created.body()).path("data").path("id").asText();
        assertEquals(200,request("POST","/v1/acceptance/orders/"+id+"/pay","{}","user").statusCode());
        login("user","u-hourly");assertEquals(200,request("POST","/v1/orders/"+id+"/accept","{}","user").statusCode());assertEquals(200,request("POST","/v1/orders/"+id+"/start","{}","user").statusCode());
        assertEquals(409,request("POST","/v1/orders/"+id+"/finish","{\"note\":\"还没有达到服务时长\"}","user").statusCode());
        assertEquals(200,request("POST","/v1/acceptance/orders/"+id+"/demo-elapse","{}","user").statusCode());assertEquals(200,request("POST","/v1/orders/"+id+"/finish","{\"note\":\"时长验收已完成\"}","user").statusCode());
        login("user","u-boss");assertEquals(200,request("POST","/v1/orders/"+id+"/refund","{\"amount\":1000,\"reason\":\"内部验收部分退款流程\"}","user").statusCode());
        login("admin","");var reviewed=request("POST","/v1/orders/"+id+"/review-refund","{\"approve\":true,\"note\":\"同意验收退款\"}","staff");assertEquals(200,reviewed.statusCode(),reviewed.body());
        var reviewing=json.readTree(reviewed.body()).path("data");assertEquals("processing",reviewing.path("refundStatus").asText());assertTrue(payments.processRefund(reviewing.path("refundId").asText()));
        login("user","u-boss");var completed=request("POST","/v1/orders/"+id+"/confirm","{}","user");assertEquals(200,completed.statusCode(),completed.body());assertEquals(4640,json.readTree(completed.body()).path("data").path("earnings").asInt());
    }
}
