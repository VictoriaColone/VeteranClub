package cn.normanclub;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.auth.*;
import cn.normanclub.admin.*;
import cn.normanclub.business.*;
import cn.normanclub.business.Rows.*;
import cn.normanclub.business.Rows.Order;
import cn.normanclub.business.Rows.Profile;
import cn.normanclub.common.ApiException;
import cn.normanclub.config.ClubConfig;
import cn.normanclub.jobs.*;
import cn.normanclub.payment.*;
import cn.normanclub.security.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.normanclub.business.Rows.map;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "club.development=true","club.cookie-secure=false","club.jobs-enabled=false","club.allowed-origins=http://127.0.0.1:4180",
    "club.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","club.hash-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
    "logging.file.name=target/integration.log"})
@Import(ServerIntegrationTest.Fixtures.class)
class ServerIntegrationTest {
    static final AtomicInteger SEQUENCE=new AtomicInteger(new java.security.SecureRandom().nextInt(90_000_000));
    static final String PASSWORD="TestOnly_42LongPassword";
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv().getOrDefault("TEST_DB_URL","jdbc:h2:mem:norman;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        r.add("spring.datasource.username",()->System.getenv().getOrDefault("TEST_DB_USERNAME","sa"));
        r.add("spring.datasource.password",()->System.getenv().getOrDefault("TEST_DB_PASSWORD",""));
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary TestClock testClock(){return new TestClock();}
        @Bean @Primary RecordingSms sms(){return new RecordingSms();}
        @Bean StubGateway stubGateway(){return new StubGateway();}
    }
    static class TestClock extends Clock {
        final AtomicLong now=new AtomicLong(Instant.parse("2026-10-25T01:00:00Z").toEpochMilli());
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now.get());}void advance(long millis){now.addAndGet(millis);}
    }
    static class RecordingSms implements SmsSender {final Map<String,String> codes=new ConcurrentHashMap<>();public void send(String phone,String code){codes.put(phone,code);}}
    static class StubGateway implements PaymentGateway {
        public String channel(){return "test";}public boolean configured(){return true;}public String merchantId(){return "test-merchant";}
        public Map<String,Object> create(Payment p){return Map.of("testOnly",true);}
        public VerifiedPayment verify(Map<String,String> headers,byte[] body){throw new UnsupportedOperationException("no public test callback");}
        public Status query(Payment p){return new Status("pending",null);}public Status close(Payment p){return new Status("closed",null);}
        public RefundStatus refund(Refund r,Payment p){return new RefundStatus("refunded",r.id,"stub-refund-"+r.id,r.amount);}
    }
    @Autowired AuthService auth;@Autowired AuthMapper authDb;@Autowired CatalogService catalog;@Autowired CatalogMapper profiles;
    @Autowired OrderService orders;@Autowired OrderMapper orderDb;@Autowired LedgerMapper ledgerDb;@Autowired LedgerService ledger;
    @Autowired PaymentService payments;@Autowired TaskService tasks;@Autowired TaskMapper taskDb;@Autowired TaskRunner runner;
    @Autowired StaffService staff;@Autowired AdminService admin;@Autowired RecordingSms sms;@Autowired TestClock clock;
    @Autowired AbuseService abuse;@Autowired ClubConfig config;@Autowired Crypto crypto;@Autowired JsonMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    final Actor operator=new Actor("integration-operator","测试管理员","admin",null,"","staff",false);
    record User(Actor actor,String token,String phone) { }
    String phone(){return "139"+String.format(Locale.ROOT,"%08d",SEQUENCE.incrementAndGet());}
    String unique(){return UUID.randomUUID().toString().replace("-","");}
    User user(){String phone=phone(),ip="198.51.100."+(SEQUENCE.get()%200+1);auth.sendCode(phone,ip);var login=auth.smsLogin(phone,sms.codes.get(phone),ip,true);return new User(auth.authenticate(login.token(),"user",true),login.token(),phone);}
    User qualified(String type){User u=user();var application=catalog.apply(u.actor,map("serviceType",type,"gameIds",List.of("delta"),"introduction","这是独立考核测试申请，有丰富的协作经验。"));String id=(String)application.get("id");catalog.review(operator,id,map("status","assessment","note","资料核验通过，安排考核"));catalog.review(operator,id,map("status","approved","score",88,"note","专项考核记录核实通过"));catalog.availability(u.actor,map("available",true));return u;}
    Order create(User u,String type,int amount){Map<String,Object> body=map("gameId","delta","serviceType",type,"region","国服","quantity",1,"note","集成测试服务需求");if(type.equals("escort")){body.put("task","100w纯带出");body.put("taskPrice",amount);}return orderDb.order((String)orders.create(u.actor,body,unique()).get("id"));}
    Payment paid(User u,Order order){String id=(String)payments.create(u.actor,order.id,map("channel","test"),unique()).get("id");payments.prepare(id);Payment p=ledgerDb.payment(id);payments.acceptVerified(new PaymentGateway.VerifiedPayment("test",p.id,"txn-"+p.id,"test-merchant",p.amount,"CNY","event-"+p.id));return ledgerDb.payment(id);}
    String staffAccount(String role){String username="staff_"+unique().substring(0,12);staff.create(operator,map("username",username,"name","测试员工","role",role,"password",PASSWORD));return username;}
    HttpResponse<String> request(String method,String path,Object body,Map<String,String> headers)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");headers.forEach(builder::header);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response){return json.readTree(response.body()).path("data");}
    Map<String,String> bearer(User user){return Map.of("Authorization","Bearer "+user.token,"X-Session-Audience","user","X-Client-Type","mini-program");}
    Map<String,String> cookieHeaders(HttpResponse<String> login,boolean csrf){
        Map<String,String> headers=new HashMap<>();headers.put("X-Session-Audience","staff");StringJoiner cookies=new StringJoiner("; ");for(String value:login.headers().allValues("set-cookie")){String pair=value.split(";",2)[0];cookies.add(pair);if(csrf&&pair.startsWith("norman-staff-csrf="))headers.put("X-CSRF-Token",pair.substring("norman-staff-csrf=".length()));}headers.put("Cookie",cookies.toString());return headers;
    }
    @Test void smsCreatesUnqualifiedUserAndCodeIsSingleUse(){
        User u=user();Profile p=profiles.byUser(u.actor.id());assertFalse(p.approved);assertTrue(profiles.qualifications(p.id).isEmpty());assertThrows(ApiException.class,()->auth.smsLogin(u.phone,sms.codes.get(u.phone),"192.0.2.1",true));
        AuthMapper.Identity id=authDb.phone(crypto.digest("phone:"+u.phone));assertFalse(id.subjectCipher.contains(u.phone));assertEquals(u.phone,crypto.decrypt(id.subjectCipher));assertEquals(u.phone.substring(0,3)+"****"+u.phone.substring(7),id.maskedValue);
    }
    @Test void wrongSmsAttemptsPersistAndExpiryIsEnforced(){
        String phone=phone(),ip="192.0.2.11";auth.sendCode(phone,ip);String correct=sms.codes.get(phone),wrong=correct.equals("000000")?"111111":"000000";
        for(int n=0;n<5;n++)assertThrows(ApiException.class,()->auth.smsLogin(phone,wrong,ip,true));assertThrows(ApiException.class,()->auth.smsLogin(phone,correct,ip,true));assertEquals(6,authDb.sms(crypto.digest("phone:"+phone)).attempts);
        String expired=phone();auth.sendCode(expired,"192.0.2.12");clock.advance(300_001);assertThrows(ApiException.class,()->auth.smsLogin(expired,sms.codes.get(expired),"192.0.2.12",true));
    }
    @Test void smsSendRateLimitIsPersistentAcrossServiceCalls(){String phone=phone();auth.sendCode(phone,"192.0.2.13");ApiException error=assertThrows(ApiException.class,()->auth.sendCode(phone,"192.0.2.14"));assertEquals(429,error.status);}
    @Test void totpUsesRfc6238VectorAndRejectsReplay(){
        String secret="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";assertEquals("287082",Totp.code(secret,59/30));long step=Totp.verify(secret,"287082",59_000,-1);assertEquals(1,step);assertEquals(-1,Totp.verify(secret,"287082",59_000,step));
    }
    @Test void staffEnrollmentCookieCsrfLogoutAndMfaReplay()throws Exception{
        String username=staffAccount("admin");var first=request("POST","/v1/auth/staff/login",map("username",username,"password",PASSWORD),Map.of("X-Session-Audience","staff"));assertEquals(200,first.statusCode());JsonNode challenge=data(first);assertEquals("enroll",challenge.path("step").asText());String secret=challenge.path("secret").asText(),token=challenge.path("challengeToken").asText(),code=Totp.code(secret,clock.millis()/30_000);
        var login=request("POST","/v1/auth/staff/verify",map("challengeToken",token,"code",code),Map.of("X-Session-Audience","staff"));assertEquals(200,login.statusCode());assertEquals("",data(login).path("token").asText());assertTrue(login.headers().allValues("set-cookie").stream().anyMatch(c->c.contains("HttpOnly")&&c.contains("SameSite=Strict")));
        assertEquals(401,request("GET","/v1/auth/session",null,Map.of("X-Session-Audience","staff")).statusCode());
        Map<String,String> headers=cookieHeaders(login,true),without=cookieHeaders(login,false);assertEquals(200,request("GET","/v1/admin/users",null,headers).statusCode());assertEquals(403,request("POST","/v1/admin/staff",Map.of(),without).statusCode());
        assertEquals(200,request("POST","/v1/auth/logout",null,headers).statusCode());assertEquals(401,request("GET","/v1/auth/session",null,headers).statusCode());
        var again=data(request("POST","/v1/auth/staff/login",map("username",username,"password",PASSWORD),Map.of("X-Session-Audience","staff")));assertEquals(401,request("POST","/v1/auth/staff/verify",map("challengeToken",again.path("challengeToken").asText(),"code",code),Map.of("X-Session-Audience","staff")).statusCode());
    }
    @Test void userCannotCallAdminApisOrSwitchAudience()throws Exception{User u=user();assertEquals(403,request("GET","/v1/admin/users",null,bearer(u)).statusCode());Map<String,String> h=new HashMap<>(bearer(u));h.put("X-Session-Audience","staff");assertEquals(401,request("GET","/v1/admin/users",null,h).statusCode());}
    @Test void disablingAccountRevokesExistingSessions()throws Exception{User u=user();admin.disableUser(operator,u.actor.id(),true);assertNull(auth.authenticate(u.token,"user",true));assertEquals(401,request("GET","/v1/orders",null,bearer(u)).statusCode());}
    @Test void qualificationTypesRemainIndependentAndRegisteredUsersAreListed(){
        User u=qualified("hourly");assertEquals(1,profiles.qualifications(u.actor.playerId()).size());assertTrue(servicesQualified(u,"hourly"));assertFalse(servicesQualified(u,"escort"));assertTrue(admin.users(operator).stream().anyMatch(v->v.id.equals(u.actor.id())));
        assertThrows(ApiException.class,()->catalog.publish(u.actor,map("gameId","delta","serviceType","escort","title","测试护航商品","description","没有护航资格不得发布","price",1000,"task","100w纯带出")));
    }
    boolean servicesQualified(User u,String type){return catalog.qualified(profiles.profile(u.actor.playerId()),type,"delta");}
    @Test void productCanBeBookedRepeatedlyWhileOfflineAndCannotBeBoughtBySeller(){
        User seller=qualified("escort"),buyer=user();Product product=catalog.publish(seller.actor,map("gameId","delta","serviceType","escort","title","长期任务护航","description","可以重复预约的个人护航服务","price",101,"task","100w纯带出"));catalog.availability(seller.actor,map("available",false));
        Map<String,Object> body=map("productId",product.id,"gameId","delta","serviceType","escort","region","国服","quantity",1,"taskPrice",99999,"task","篡改任务");
        String first=(String)orders.create(buyer.actor,body,unique()).get("id"),second=(String)orders.create(buyer.actor,body,unique()).get("id");assertNotEquals(first,second);assertEquals(101,orderDb.order(first).amount);assertEquals("100w纯带出",orderDb.order(first).task);assertTrue(profiles.product(product.id).active);assertThrows(ApiException.class,()->orders.create(seller.actor,body,unique()));
    }
    @Test void orderCreationIsIdempotentAndRejectsRequestKeyReuse(){
        qualified("hourly");User u=user();Map<String,Object> body=map("gameId","delta","serviceType","hourly","region","国服","quantity",1);String key=unique();var one=orders.create(u.actor,body,key);var two=orders.create(u.actor,body,key);assertEquals(one.get("id"),two.get("id"));Map<String,Object> altered=new HashMap<>(body);altered.put("quantity",2);assertThrows(ApiException.class,()->orders.create(u.actor,altered,key));
    }
    @Test void concurrentAcceptAllowsOnlyOneWinner()throws Exception{
        User buyer=user();Order order=create(buyer,"escort",103);paid(buyer,order);List<User> members=List.of(qualified("escort"),qualified("escort"),qualified("escort"));CountDownLatch start=new CountDownLatch(1);AtomicInteger winners=new AtomicInteger();
        try(ExecutorService pool=Executors.newFixedThreadPool(3)){List<Future<?>> futures=new ArrayList<>();for(User u:members)futures.add(pool.submit(()->{try{start.await();orders.act(u.actor,order.id,"accept",Map.of());winners.incrementAndGet();}catch(Exception expected){}}));start.countDown();for(Future<?> f:futures)f.get(15,TimeUnit.SECONDS);}
        assertEquals(1,winners.get());assertEquals(1,orderDb.members(order.id).size());
    }
    @Test void sameMemberCannotOccupyTwoOrdersConcurrently()throws Exception{
        User buyer=user(),member=qualified("escort");Order a=create(buyer,"escort",105),b=create(buyer,"escort",107);paid(buyer,a);paid(buyer,b);CountDownLatch start=new CountDownLatch(1);AtomicInteger winners=new AtomicInteger();
        try(ExecutorService pool=Executors.newFixedThreadPool(2)){List<Future<?>> work=new ArrayList<>();for(Order o:List.of(a,b))work.add(pool.submit(()->{try{start.await();orders.act(member.actor,o.id,"accept",Map.of());winners.incrementAndGet();}catch(Exception expected){}}));start.countDown();for(Future<?> f:work)f.get(15,TimeUnit.SECONDS);}
        assertEquals(1,winners.get());assertNotNull(orderDb.busy(member.actor.playerId()));
    }
    @Test void collaborationSplitsPenniesOnceAndDoesNotClaimTransfersWerePaid(){
        User buyer=user(),lead=qualified("escort"),assistant=qualified("escort");Order o=create(buyer,"escort",104);paid(buyer,o);orders.act(lead.actor,o.id,"accept",Map.of());orders.act(lead.actor,o.id,"add-assistant",map("playerId",assistant.actor.playerId()));orders.act(lead.actor,o.id,"start",Map.of());assertThrows(ApiException.class,()->orders.act(lead.actor,o.id,"remove-assistant",map("playerId",assistant.actor.playerId())));orders.act(lead.actor,o.id,"finish",map("taskCompleted",true,"note","任务已按约定完成"));orders.act(buyer.actor,o.id,"confirm",Map.of());orders.act(buyer.actor,o.id,"confirm",Map.of());
        Income a=ledgerDb.income(o.id,lead.actor.playerId()),b=ledgerDb.income(o.id,assistant.actor.playerId());assertEquals(83,a.amount+b.amount);assertTrue(Math.abs(a.amount-b.amount)<=1);assertEquals("credited",a.status);assertNull(a.paidAt);assertNull(orderDb.busy(lead.actor.playerId()));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM income_entry WHERE order_id=?",Integer.class,o.id));assertEquals(0L,ledger.wallet(lead.actor).get("settled"));
    }
    @Test void partialRefundRemainsFrozenUntilVerifiedChannelResult(){
        User buyer=user(),lead=qualified("escort");Order o=create(buyer,"escort",101);paid(buyer,o);orders.act(lead.actor,o.id,"accept",Map.of());orders.act(lead.actor,o.id,"start",Map.of());orders.act(lead.actor,o.id,"finish",map("taskCompleted",true,"note","等待用户验收"));orders.act(buyer.actor,o.id,"refund",map("amount",50,"reason","申请退还未达成的部分"));assertThrows(ApiException.class,()->orders.act(buyer.actor,o.id,"confirm",Map.of()));
        orders.act(operator,o.id,"review-refund",map("approve",true,"note","核实后批准部分退款"));assertEquals("processing",orderDb.order(o.id).refundStatus);assertNull(ledgerDb.income(o.id,lead.actor.playerId()));payments.processRefund(orderDb.order(o.id).refundId);orders.act(buyer.actor,o.id,"confirm",Map.of());assertEquals(40,ledgerDb.income(o.id,lead.actor.playerId()).amount);assertThrows(ApiException.class,()->orders.act(buyer.actor,o.id,"refund",map("amount",1,"reason","再次申请退款测试")));
    }
    @Test void hourlyOrdersUseServerTimeAndRealServerHasNoDemoElapsedEndpoint(){
        User member=qualified("hourly"),buyer=user();Order o=create(buyer,"hourly",0);paid(buyer,o);orders.act(member.actor,o.id,"accept",Map.of());orders.act(member.actor,o.id,"start",Map.of());assertThrows(ApiException.class,()->orders.act(member.actor,o.id,"finish",map("note","时长尚未走满")));assertThrows(ApiException.class,()->orders.act(member.actor,o.id,"demo-elapse",Map.of()));clock.advance(3_600_000);orders.act(member.actor,o.id,"finish",map("note","已完成约定时长"));assertEquals("confirming",orderDb.order(o.id).status);
    }
    @Test void paymentVerificationRejectsTamperingAndRepeatedCallbacksAreIdempotent(){
        User buyer=user();Order o=create(buyer,"escort",100);String id=(String)payments.create(buyer.actor,o.id,map("channel","test"),unique()).get("id");Payment p=ledgerDb.payment(id);
        assertThrows(ApiException.class,()->payments.acceptVerified(new PaymentGateway.VerifiedPayment("test",id,"transaction_"+id,"wrong-merchant",100,"CNY",unique())));assertThrows(ApiException.class,()->payments.acceptVerified(new PaymentGateway.VerifiedPayment("test",id,"transaction_"+id,"test-merchant",1,"CNY",unique())));assertEquals("unpaid",orderDb.order(o.id).status);
        var event=new PaymentGateway.VerifiedPayment("test",id,"transaction_"+id,"test-merchant",100,"CNY",unique());payments.acceptVerified(event);payments.acceptVerified(event);assertEquals("waiting",orderDb.order(o.id).status);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_notification WHERE payment_id=?",Integer.class,id));
    }
    @Test void disabledPaymentPortsCannotBeUsedToFakePaidOrders()throws Exception{
        User buyer=user();Order o=create(buyer,"escort",100);assertThrows(ApiException.class,()->orders.act(buyer.actor,o.id,"pay",Map.of()));assertThrows(ApiException.class,()->payments.create(buyer.actor,o.id,map("channel","wechat"),unique()));assertEquals(503,request("POST","/v1/payments/notify/wechat",Map.of(),Map.of()).statusCode());assertEquals("unpaid",orderDb.order(o.id).status);assertTrue(ledgerDb.payments(o.id).isEmpty());
    }
    @Test void timeoutTaskSurvivesWithoutClientRequestsAndLatePaymentCannotReopenOrder(){
        User buyer=user();Order plain=create(buyer,"escort",100),paidLate=create(buyer,"escort",100);String id=(String)payments.create(buyer.actor,paidLate.id,map("channel","test"),unique()).get("id");payments.prepare(id);clock.advance(600_000);
        assertFalse(orders.closeExpired(paidLate.id));assertTrue(payments.query(id));assertTrue(orders.closeExpired(paidLate.id));
        var recreated=new TaskService(taskDb,clock);for(int n=0;n<200;n++){Task task=recreated.claim();if(task==null)break;runner.execute(task);}assertEquals("cancelled",orderDb.order(plain.id).status);
        payments.acceptVerified(new PaymentGateway.VerifiedPayment("test",id,"late-"+id,"test-merchant",100,"CNY",unique()));assertEquals("cancelled",orderDb.order(paidLate.id).status);assertEquals("late_paid",ledgerDb.payment(id).status);
    }
    @Test void sqlInjectionUnknownFieldsAndOtherUsersOrdersAreRejected()throws Exception{
        User owner=user(),other=user();Order o=create(owner,"escort",100);assertEquals(403,request("GET","/v1/orders/"+o.id,null,bearer(other)).statusCode());String query=java.net.URLEncoder.encode("' OR 1=1 --",java.nio.charset.StandardCharsets.UTF_8);var result=request("GET","/v1/orders?query="+query,null,bearer(other));assertEquals(200,result.statusCode());assertEquals(0,data(result).size());
        Map<String,Object> body=map("gameId","delta","serviceType","escort","region","国服","quantity",1,"task","测试任务","taskPrice",100,"commissionBps",0);Map<String,String> headers=new HashMap<>(bearer(owner));headers.put("Idempotency-Key",unique());assertEquals(400,request("POST","/v1/orders",body,headers).statusCode());assertEquals(2000,orderDb.order(o.id).commissionBps);assertEquals(401,request("POST","/v1/auth/staff/login",map("username","' OR 1=1 --","password","anything"),Map.of()).statusCode());
    }
    @Test void csrfOriginLimitsAndSpoofedIpCannotBypassBan()throws Exception{
        assertEquals(403,request("POST","/v1/auth/sms/send",map("phone",phone()),Map.of("Origin","https://attacker.example")).statusCode());
        String ip="203.0.113.9";abuse.ban(ip,"集成测试临时封禁",clock.millis()+60000,"测试");MockHttpServletRequest req=new MockHttpServletRequest("GET","/v1/club");req.setRemoteAddr(ip);req.addHeader("X-Real-IP","198.51.100.99");req.addHeader("X-Forwarded-For","198.51.100.99");MockHttpServletResponse res=new MockHttpServletResponse();AtomicBoolean passed=new AtomicBoolean();new ApiSecurityFilter(auth,abuse,config,json).doFilter(req,res,(r,s)->passed.set(true));assertEquals(403,res.getStatus());assertFalse(passed.get());
        String key=unique();for(int n=0;n<5;n++)assertTrue(abuse.allow("atomic-test",key,5,60000));assertFalse(abuse.allow("atomic-test",key,5,60000));
    }
    @Test void automaticIpBanSurvivesFailedBusinessTransaction(){
        String ip="192.0.2.242";for(int i=0;i<20;i++)abuse.loginFailed(ip);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(s->{abuse.loginFailed(ip);throw new IllegalStateException("rollback simulated business");}));
        assertEquals(403,assertThrows(ApiException.class,()->abuse.check(ip)).status);
        assertThrows(ApiException.class,()->AbuseService.literal("deadbeef"));assertThrows(ApiException.class,()->AbuseService.literal("127.0.0.1.attacker.example"));
    }
    @Test void concurrencyDoesNotBypassPersistentRateLimit()throws Exception{
        String key=unique();CountDownLatch start=new CountDownLatch(1);AtomicInteger accepted=new AtomicInteger();
        try(ExecutorService pool=Executors.newFixedThreadPool(6)){
            List<Future<?>> work=new ArrayList<>();for(int i=0;i<12;i++)work.add(pool.submit(()->{try{start.await();if(abuse.allow("parallel-rate",key,5,60000))accepted.incrementAndGet();}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            start.countDown();for(Future<?> f:work)f.get(15,TimeUnit.SECONDS);
        }
        assertEquals(5,accepted.get());
    }
    @Test void staffRoleBoundariesAndBlankMoneyCannotBeBypassed(){
        User buyer=user(),member=qualified("escort");Order o=create(buyer,"escort",100);paid(buyer,o);
        Actor customerService=new Actor("service-test","客服","customer_service",null,"","staff",false);
        orders.act(customerService,o.id,"assign",map("playerId",member.actor.playerId()));orders.act(buyer.actor,o.id,"refund",map("amount",1,"reason","申请核实退款内容"));
        assertEquals(403,assertThrows(ApiException.class,()->orders.act(customerService,o.id,"review-refund",map("approve",true,"note","越权审批测试"))).status);
        assertEquals(403,assertThrows(ApiException.class,()->catalog.updateProfile(customerService,member.actor.playerId(),map("price",100))).status);
        assertThrows(ApiException.class,()->catalog.updateProfile(operator,member.actor.playerId(),map("price",0)));
        assertThrows(ApiException.class,()->catalog.updateProfile(operator,member.actor.playerId(),map("approved",true)));
    }
    @Test void streamingBodyLimitAndMethodOverrideAreEnforced()throws Exception{
        User u=user();Map<String,String> headers=new HashMap<>(bearer(u));headers.put("X-HTTP-Method-Override","DELETE");assertEquals(400,request("POST","/v1/products/x",Map.of(),headers).statusCode());
        String large="x".repeat(66_000);assertEquals(413,request("POST","/v1/auth/sms/send",map("phone",large),Map.of()).statusCode());
        String unusedPhone=phone();byte[] chunked=(" ".repeat(66000)+json.writeValueAsString(map("phone",unusedPhone))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var chunkedRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/auth/sms/send")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(chunked))).build();
        int rejected=HttpClient.newHttpClient().send(chunkedRequest,HttpResponse.BodyHandlers.ofString()).statusCode();assertTrue(rejected==400||rejected==413);assertFalse(sms.codes.containsKey(unusedPhone));
        assertThrows(ApiException.class,()->orders.create(u.actor,map("gameId","delta","serviceType","escort","region","国服","quantity",1,"task",List.of("注入对象"),"taskPrice",100),unique()));
    }
    @Test void dashboardUsesDatabaseFinancialAggregates(){
        var before=admin.dashboard(operator);User buyer=user();Order o=create(buyer,"escort",123);paid(buyer,o);var after=admin.dashboard(operator);
        assertEquals(((Number)before.get("todayRevenue")).longValue()+123,((Number)after.get("todayRevenue")).longValue());
        assertEquals(((Number)before.get("todayOrders")).longValue()+1,((Number)after.get("todayOrders")).longValue());
        assertFalse(((List<?>)after.get("recentOrders")).isEmpty());assertEquals(7,((List<?>)after.get("revenueTrend")).size());
    }
}
