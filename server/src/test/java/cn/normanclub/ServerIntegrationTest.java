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
    "club.sms-daily-limit=2000","club.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","club.hash-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
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
        final Map<String,Status> queryResults=new ConcurrentHashMap<>(),closeResults=new ConcurrentHashMap<>();
        VerifiedPayment notification;
        public String channel(){return "test";}public boolean configured(){return true;}public String merchantId(){return "test-merchant";}
        public Map<String,Object> create(Payment p){return Map.of("testOnly",true);}
        public VerifiedPayment verify(Map<String,String> headers,byte[] body){if(notification!=null)return notification;throw new UnsupportedOperationException("no public test callback");}
        public Status query(Payment p){return queryResults.getOrDefault(p.id,new Status("pending",null));}public Status close(Payment p){return closeResults.getOrDefault(p.id,new Status("closed",null));}
        public RefundStatus refund(Refund r,Payment p){return new RefundStatus("refunded",r.id,"stub-refund-"+r.id,r.amount);}
    }
    @Autowired AuthService auth;@Autowired AuthMapper authDb;@Autowired CatalogService catalog;@Autowired CatalogMapper profiles;
    @Autowired UserIdentityService identities;@Autowired UserIdentityMapper identityDb;
    @Autowired AuditService identityAudit;@Autowired AccountClosureService closure;
    @Autowired OrderService orders;@Autowired OrderMapper orderDb;@Autowired LedgerMapper ledgerDb;@Autowired LedgerService ledger;
    @Autowired PaymentService payments;@Autowired TaskService tasks;@Autowired TaskMapper taskDb;@Autowired TaskRunner runner;
    @Autowired StaffService staff;@Autowired AdminService admin;@Autowired RecordingSms sms;@Autowired TestClock clock;
    @Autowired AbuseService abuse;@Autowired ClubConfig config;@Autowired Crypto crypto;@Autowired JsonMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired cn.normanclub.wallet.WalletService wallets;
    @Autowired cn.normanclub.wallet.WalletMapper walletDb;
    @Autowired StubGateway gateway;
    @LocalServerPort int port;
    final Actor operator=new Actor("integration-operator","测试管理员","admin",null,"","staff",false);
    record User(Actor actor,String token,String phone) { }
    String phone(){return "139"+String.format(Locale.ROOT,"%08d",SEQUENCE.incrementAndGet());}
    String unique(){return UUID.randomUUID().toString().replace("-","");}
    User rawUser(){String phone=phone(),ip="198.51.100."+(SEQUENCE.get()%200+1);auth.sendCode(phone,ip,"register");var login=auth.smsRegister(phone,sms.codes.get(phone),PASSWORD,ip,true);return new User(auth.authenticate(login.token(),"user",true),login.token(),phone);}
    User user(){User value=rawUser();identities.submit(value.actor,identityInput(syntheticIdentity("19900101")));return value;}
    User qualified(String type){User u=user();var application=catalog.apply(u.actor,map("serviceType",type,"gameIds",List.of("delta"),"introduction","这是独立考核测试申请，有丰富的协作经验。"));String id=(String)application.get("id");catalog.review(operator,id,map("status","assessment","note","资料核验通过，安排考核"));catalog.review(operator,id,map("status","approved","score",88,"note","专项考核记录核实通过"));catalog.updateProfile(operator,u.actor.playerId(),map("available",true));return u;}
    Order create(User u,String type,int amount){
        Product item=new Product();item.id="fixture-"+unique();item.sellerType="club";item.sellerName="诺曼部落";item.gameId="delta";item.serviceType=type;item.title="测试商品";item.description="集成测试专用定价商品";item.price=type.equals("escort")?amount:6800;item.task=type.equals("escort")?"100w纯带出":"";item.active=true;item.createdAt=clock.millis();profiles.insertProduct(item);
        return orderDb.order((String)orders.create(u.actor,map("productId",item.id,"gameId","delta","serviceType",type,"region","国服","contactPhone","13800000000","quantity",1,"note","集成测试服务需求"),unique()).get("id"));
    }
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
    Map<String,Object> identityInput(String number){return map("name","测试身份","idNumber",number,"consent",true,"consentVersion",UserIdentityService.CONSENT_VERSION);}
    String syntheticIdentity(String birth){return ResidentIdentityTest.number(birth,SEQUENCE.incrementAndGet());}
    @Test void registrationRejectsExistingPhoneAndLoginDoesNotSilentlyRegister(){
        User existing=user();clock.advance(60_001);auth.sendCode(existing.phone,"192.0.2.180","register");String code=sms.codes.get(existing.phone);
        assertEquals(409,assertThrows(ApiException.class,()->auth.smsRegister(existing.phone,code,PASSWORD,"192.0.2.180",true)).status);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM login_identity WHERE subject_hash=?",Integer.class,crypto.digest("phone:"+existing.phone)));
        clock.advance(60_001);auth.sendCode(existing.phone,"192.0.2.180");
        assertEquals(existing.actor.id(),auth.smsLogin(existing.phone,sms.codes.get(existing.phone),"192.0.2.180",true).session().get("userId"));
        String fresh=phone();auth.sendCode(fresh,"192.0.2.181");
        assertEquals(404,assertThrows(ApiException.class,()->auth.smsLogin(fresh,sms.codes.get(fresh),"192.0.2.181",true)).status);
        assertNull(authDb.phone(crypto.digest("phone:"+fresh)));
    }
    @Test void identityIsEncryptedMaskedImmutableAndOnlyVisibleToItsOwner()throws Exception{
        User one=rawUser(),two=rawUser();String number=syntheticIdentity("19900101");
        assertEquals(401,request("GET","/v1/account/identity",null,Map.of()).statusCode());
        assertEquals("missing",identities.current(one.actor).get("status"));
        var saved=request("POST","/v1/account/identity",identityInput(number),bearer(one));assertEquals(200,saved.statusCode());
        assertEquals("self_declared",data(saved).path("status").asText());assertTrue(data(saved).path("adult").asBoolean());assertEquals("not_verified",data(saved).path("verification").asText());
        assertFalse(saved.body().contains(number));assertFalse(saved.body().contains("测试身份"));
        var row=identityDb.get(one.actor.id());assertNotEquals(number,row.idNumberCipher);assertEquals(number,crypto.decrypt(row.idNumberCipher));assertEquals("测试身份",crypto.decrypt(row.realNameCipher));assertNotEquals(Crypto.sha256(number),row.idNumberHash);
        assertEquals("missing",data(request("GET","/v1/account/identity?userId="+one.actor.id(),null,bearer(two))).path("status").asText());
        assertEquals(409,assertThrows(ApiException.class,()->identities.submit(one.actor,identityInput(syntheticIdentity("19800101")))).status);
        assertEquals(403,assertThrows(ApiException.class,()->identities.current(operator)).status);
        assertFalse(auth.current(one.actor).containsKey("idNumber"));assertFalse(auth.current(one.actor).containsKey("realName"));
    }
    @Test void identityValidationRequiresConsentAndRejectsForgedAgeFlagsAndUnderFourteen(){
        User user=rawUser();String number=syntheticIdentity("19900101");Map<String,Object> input=identityInput(number);input.put("consent",false);
        assertEquals(400,assertThrows(ApiException.class,()->identities.submit(user.actor,input)).status);
        Map<String,Object> forged=identityInput(number);forged.put("adult",true);assertEquals(400,assertThrows(ApiException.class,()->identities.submit(user.actor,forged)).status);
        assertEquals(400,assertThrows(ApiException.class,()->identities.submit(user.actor,identityInput(number.substring(0,17)+"!"))).status);
        assertEquals(403,assertThrows(ApiException.class,()->identities.submit(user.actor,identityInput(syntheticIdentity("20200101")))).status);
        assertNull(identityDb.get(user.actor.id()).identitySubmittedAt);
        assertEquals(false,identities.submit(user.actor,identityInput(syntheticIdentity("20100101"))).get("adult"));
    }
    @Test void sameIdentityCannotBindToTwoUsersIncludingConcurrentSubmissions()throws Exception{
        User first=rawUser(),second=rawUser();String number=syntheticIdentity("19900101");CountDownLatch start=new CountDownLatch(1);ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            List<Future<Integer>> results=new ArrayList<>();for(User user:List.of(first,second))results.add(executor.submit(()->{start.await();try{identities.submit(user.actor,identityInput(number));return 200;}catch(ApiException e){return e.status;}}));
            start.countDown();List<Integer> statuses=new ArrayList<>();for(var result:results)statuses.add(result.get(10,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM club_user WHERE id_number_hash=?",Integer.class,crypto.digest("resident-id:"+number)));
        }finally{executor.shutdownNow();}
    }
    @Test void staleBitmapNegativeCannotBypassDatabaseUniqueness(){
        User first=rawUser(),second=rawUser();String number=syntheticIdentity("19900101");identities.submit(first.actor,identityInput(number));
        var empty=org.mockito.Mockito.mock(UserIdentityMapper.class);org.mockito.Mockito.when(empty.hashes("")).thenReturn(List.of());
        var stale=new UserIdentityService(identityDb,authDb,crypto,clock,new IdentityBitmap(empty),abuse,identityAudit,profiles);
        assertEquals(409,assertThrows(ApiException.class,()->new TransactionTemplate(transactions).execute(s->stale.submit(second.actor,identityInput(number)))).status);
        assertNull(identityDb.get(second.actor.id()).identitySubmittedAt);
    }
    @Test void orderingRequiresAdultDeclarationOrActiveOnboardingAndAlsoProtectsPayment(){
        User missing=rawUser(),minor=rawUser(),adult=user();
        assertEquals(403,assertThrows(ApiException.class,()->create(missing,"hourly",6800)).status);
        identities.submit(minor.actor,identityInput(syntheticIdentity("20100101")));
        assertEquals(403,assertThrows(ApiException.class,()->create(minor,"hourly",6800)).status);
        assertDoesNotThrow(()->create(adult,"hourly",6800));
        approveGame(applyGame(minor,"hourly","delta"));assertEquals(true,identities.current(minor.actor).get("canOrder"));
        var order=create(minor,"hourly",6800);catalog.updateProfile(operator,minor.actor.playerId(),map("approved",false));
        assertEquals(false,identities.current(minor.actor).get("canOrder"));
        assertEquals(403,assertThrows(ApiException.class,()->payments.create(minor.actor,order.id,map("channel","test"),"pay-"+unique())).status);
        User onboarded=rawUser();approveGame(applyGame(onboarded,"hourly","delta"));assertDoesNotThrow(()->create(onboarded,"hourly",6800));
    }
    @Test void userPasswordsAreHashedResetRevokesSessionsAndSmsPurposesCannotBeMixed(){
        User user=user();assertTrue(authDb.userPassword(user.actor.id()).startsWith("$2a$12$"));assertNotEquals(PASSWORD,authDb.userPassword(user.actor.id()));
        assertEquals(user.actor.id(),auth.passwordLogin(user.phone,PASSWORD,"192.0.2.185",true).session().get("userId"));
        assertEquals("手机号或密码不正确",assertThrows(ApiException.class,()->auth.passwordLogin(user.phone,"incorrect","192.0.2.185",true)).getMessage());
        assertEquals("手机号或密码不正确",assertThrows(ApiException.class,()->auth.passwordLogin(phone(),"incorrect","192.0.2.185",true)).getMessage());
        clock.advance(60_001);auth.sendCode(user.phone,"192.0.2.185","login");String loginCode=sms.codes.get(user.phone);
        assertEquals(401,assertThrows(ApiException.class,()->auth.resetPassword(user.phone,loginCode,"NewPassword_43","192.0.2.185")).status);
        clock.advance(60_001);auth.sendCode(user.phone,"192.0.2.185","reset");String resetCode=sms.codes.get(user.phone);
        auth.resetPassword(user.phone,resetCode,"NewPassword_43","192.0.2.185");assertNull(auth.authenticate(user.token,"user",true));
        assertThrows(ApiException.class,()->auth.passwordLogin(user.phone,PASSWORD,"192.0.2.185",true));assertNotNull(auth.passwordLogin(user.phone,"NewPassword_43","192.0.2.185",true));
        assertThrows(ApiException.class,()->auth.resetPassword(user.phone,resetCode,"OtherPassword_44","192.0.2.185"));
    }
    @Test void userPasswordLoginIsThrottledPerAccountAcrossFailedRequests(){
        User user=user();for(int i=0;i<10;i++)assertEquals(401,assertThrows(ApiException.class,()->auth.passwordLogin(user.phone,"incorrect",unique(),true)).status);
        assertEquals(429,assertThrows(ApiException.class,()->auth.passwordLogin(user.phone,PASSWORD,"192.0.2.186",true)).status);
        for(String weak:List.of("12345678","abcdefgh","short1","a1".repeat(40)))assertThrows(ApiException.class,()->AuthService.validateUserPassword(weak));
    }
    @Test void passwordResetDuringLoginCannotIssueASessionWithTheOldPassword()throws Exception{
        User user=user();clock.advance(60_001);auth.sendCode(user.phone,"192.0.2.189","reset");String code=sms.codes.get(user.phone);
        CountDownLatch verified=new CountDownLatch(1),resume=new CountDownLatch(1);
        var delegate=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);
        var paused=new org.springframework.security.crypto.password.PasswordEncoder(){
            public String encode(CharSequence raw){return delegate.encode(raw);}
            public boolean matches(CharSequence raw,String hash){boolean result=delegate.matches(raw,hash);verified.countDown();try{if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return result;}
        };
        AuthService concurrent=new AuthService(authDb,profiles,crypto,clock,sms,abuse,paused,identityAudit,config);ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            Future<Integer> result=executor.submit(()->{try{new TransactionTemplate(transactions).execute(s->concurrent.passwordLogin(user.phone,PASSWORD,"192.0.2.189",true));return 200;}catch(ApiException e){return e.status;}});
            assertTrue(verified.await(10,TimeUnit.SECONDS));auth.resetPassword(user.phone,code,"NewPassword_45","192.0.2.189");resume.countDown();assertEquals(401,result.get(10,TimeUnit.SECONDS));
        }finally{resume.countDown();executor.shutdownNow();}
    }
    @Test void accountClosureRequiresFreshOwnedSmsBlocksOpenOrdersAndReleasesBindings(){
        User user=user();String originalHash=identityDb.get(user.actor.id()).idNumberHash;String number=crypto.decrypt(identityDb.get(user.actor.id()).idNumberCipher);
        assertThrows(ApiException.class,()->closure.close(user.actor,map("code","000000","confirm",true),"192.0.2.187"));
        var order=create(user,"hourly",6800);clock.advance(60_001);auth.sendCode(user.phone,"192.0.2.187","close");
        assertEquals(409,assertThrows(ApiException.class,()->closure.close(user.actor,map("code",sms.codes.get(user.phone),"confirm",true),"192.0.2.187")).status);assertNotNull(auth.authenticate(user.token,"user",true));
        orders.act(user.actor,order.id,"cancel",Map.of());clock.advance(60_001);auth.sendCode(user.phone,"192.0.2.187","close");
        closure.close(user.actor,map("code",sms.codes.get(user.phone),"confirm",true),"192.0.2.187");assertNull(auth.authenticate(user.token,"user",true));
        assertNull(authDb.phone(crypto.digest("phone:"+user.phone)));assertNull(identityDb.owner(originalHash));assertNull(identityDb.get(user.actor.id()).idNumberCipher);assertNull(authDb.userPassword(user.actor.id()));
        assertNotNull(orderDb.order(order.id));assertThrows(ApiException.class,()->admin.disableUser(operator,user.actor.id(),false));
        clock.advance(60_001);auth.sendCode(user.phone,"192.0.2.187","register");var again=auth.smsRegister(user.phone,sms.codes.get(user.phone),PASSWORD,"192.0.2.187",true);
        assertNotEquals(user.actor.id(),again.session().get("userId"));var actor=auth.authenticate(again.token(),"user",true);assertDoesNotThrow(()->identities.submit(actor,identityInput(number)));
    }
    @Test void registrationContactUsesAuthenticatedSmsIdentityWithoutPuttingPhoneInSession()throws Exception{
        User one=user(),two=user();
        assertEquals(401,request("GET","/v1/auth/contact",null,Map.of()).statusCode());
        var first=request("GET","/v1/auth/contact?userId="+two.actor.id(),null,bearer(one));
        assertEquals(200,first.statusCode());assertEquals(one.phone,data(first).path("phone").asText());assertEquals(one.actor.id(),data(first).path("userId").asText());assertFalse(first.body().contains(two.phone));
        assertTrue(first.headers().firstValue("cache-control").orElse("").contains("no-store"));
        var restored=auth.authenticate(one.token,"user",true);assertEquals(one.phone,auth.registrationContact(restored).get("phone"));
        assertFalse(auth.current(restored).containsKey("phone"));assertFalse(request("GET","/v1/auth/session",null,bearer(one)).body().contains(one.phone));
        assertEquals(403,assertThrows(ApiException.class,()->auth.registrationContact(operator)).status);
        String username=staffAccount("admin");var staffLogin=request("POST","/v1/auth/staff/login",map("username",username,"password",PASSWORD),Map.of("X-Session-Audience","staff"));
        assertEquals(403,request("GET","/v1/auth/contact",null,cookieHeaders(staffLogin,true)).statusCode());
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
    @Test void staffPasswordLoginUsesCookieCsrfAndRevocableSession()throws Exception{
        String username=staffAccount("admin");
        var wrong=request("POST","/v1/auth/staff/login",map("username",username,"password","wrong-password"),Map.of());
        var missing=request("POST","/v1/auth/staff/login",map("username","missing_"+unique(),"password","wrong-password"),Map.of());
        assertEquals(401,wrong.statusCode());assertEquals(401,missing.statusCode());assertEquals(json.readTree(wrong.body()).path("message"),json.readTree(missing.body()).path("message"));
        var login=request("POST","/v1/auth/staff/login",map("username",username,"password",PASSWORD),Map.of("X-Session-Audience","staff"));assertEquals(200,login.statusCode());
        assertEquals("admin",data(login).path("role").asText());assertEquals("",data(login).path("token").asText());assertFalse(login.body().contains("passwordHash"));assertFalse(login.body().contains("secret"));
        assertTrue(login.headers().allValues("set-cookie").stream().anyMatch(c->c.contains("HttpOnly")&&c.contains("SameSite=Strict")));
        assertNotEquals(PASSWORD,authDb.staffByUsername(username).passwordHash);assertTrue(authDb.staffByUsername(username).passwordHash.startsWith("$2a$12$"));
        assertEquals(401,request("GET","/v1/admin/users",null,Map.of("X-Session-Audience","staff")).statusCode());
        Map<String,String> headers=cookieHeaders(login,true),without=cookieHeaders(login,false);
        assertEquals(200,request("GET","/v1/admin/users",null,headers).statusCode());assertEquals(403,request("POST","/v1/admin/staff",Map.of(),without).statusCode());
        assertEquals(200,request("POST","/v1/auth/logout",null,headers).statusCode());assertEquals(401,request("GET","/v1/auth/session",null,headers).statusCode());
        assertEquals(401,request("GET","/v1/admin/users",null,headers).statusCode());
    }
    @Test void staffSessionsExpireAndDisabledAccountsCannotLogin(){
        String username=staffAccount("admin");var login=auth.staffLogin(username,PASSWORD,"192.0.2.101");assertNotNull(auth.authenticate(login.token(),"staff",false));
        clock.advance(1_800_001);assertNull(auth.authenticate(login.token(),"staff",false));
        var again=auth.staffLogin(username,PASSWORD,"192.0.2.101");staff.disable(operator,authDb.staffByUsername(username).id,true);
        assertNull(auth.authenticate(again.token(),"staff",false));assertThrows(ApiException.class,()->auth.staffLogin(username,PASSWORD,"192.0.2.101"));
    }
    @Test void staffLoginThrottlesPersistAfterFailedTransactions(){
        String username=staffAccount("admin");
        for(int attempt=0;attempt<10;attempt++){int index=attempt;assertEquals(401,assertThrows(ApiException.class,()->auth.staffLogin(username,"bad-password","198.51.100."+(index+1))).status);}
        assertEquals(429,assertThrows(ApiException.class,()->auth.staffLogin(username,PASSWORD,"198.51.100.22")).status);
    }
    @Test void managerProvisioningIsIdempotentAndAcceptsConfiguredPasswordPolicy(){
        String hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(PASSWORD);
        staff.provisionManager(hash);var manager=authDb.staffByUsername("norman_app_manager");assertEquals("admin",manager.role);assertFalse(manager.disabled);
        staff.provisionManager(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("OtherPassword_42"));
        assertEquals(hash,authDb.staffByUsername("norman_app_manager").passwordHash);
        assertThrows(ApiException.class,()->staff.provisionManager("plaintext-is-not-a-hash"));
        assertDoesNotThrow(()->StaffService.validatePassword("Example_42"));assertThrows(ApiException.class,()->StaffService.validatePassword("short1"));
    }
    @Test void userCannotCallAdminApisOrSwitchAudience()throws Exception{User u=user();assertEquals(403,request("GET","/v1/admin/users",null,bearer(u)).statusCode());Map<String,String> h=new HashMap<>(bearer(u));h.put("X-Session-Audience","staff");assertEquals(401,request("GET","/v1/admin/users",null,h).statusCode());}
    @Test void disablingAccountRevokesExistingSessions()throws Exception{User u=user();admin.disableUser(operator,u.actor.id(),true);assertNull(auth.authenticate(u.token,"user",true));assertEquals(401,request("GET","/v1/orders",null,bearer(u)).statusCode());}
    @Test void qualificationTypesRemainIndependentAndRegisteredUsersAreListed(){
        User u=qualified("hourly");assertEquals(1,profiles.qualifications(u.actor.playerId()).size());assertTrue(servicesQualified(u,"hourly"));assertFalse(servicesQualified(u,"escort"));assertTrue(admin.users(operator).stream().anyMatch(v->v.id.equals(u.actor.id())));
        assertThrows(ApiException.class,()->catalog.publish(u.actor,map("gameId","delta","serviceType","escort","title","测试护航商品","description","没有护航资格不得发布","price",1000,"task","100w纯带出")));
    }
    boolean servicesQualified(User u,String type){return catalog.qualified(profiles.profile(u.actor.playerId()),type,"delta");}
    String applyGame(User u,String type,String game){return (String)catalog.apply(u.actor,map("serviceType",type,"gameIds",List.of(game),"introduction","单个游戏单种服务独立申请，参与专项考核。")).get("id");}
    void approveGame(String id){catalog.review(operator,id,map("status","assessment","note","该项资料审核通过"));catalog.review(operator,id,map("status","approved","score",90,"note","该游戏该服务专项考核通过"));}
    @Test void gameAndServiceAreIndependentAcrossApplicationHallAcceptAndAssignment(){
        User member=user(),buyer=user();String hourlyDelta=applyGame(member,"hourly","delta"),escortDelta=applyGame(member,"escort","delta"),hourlyValorant=applyGame(member,"hourly","valorant");
        assertThrows(ApiException.class,()->applyGame(member,"hourly","delta"));approveGame(hourlyDelta);approveGame(escortDelta);catalog.updateProfile(operator,member.actor.playerId(),map("available",true));
        assertThrows(ApiException.class,()->applyGame(member,"hourly","delta"));
        assertThrows(ApiException.class,()->catalog.apply(member.actor,map("serviceType","escort","gameIds",List.of("valorant","lol"),"introduction","不能将多个游戏合在同一次考核中")));
        for(String game:List.of("delta","valorant","lol"))for(String type:List.of("hourly","escort")){
            boolean allowed=game.equals("delta");assertEquals(allowed,catalog.qualified(profiles.profile(member.actor.playerId()),type,game));
            String region=((List<String>)CatalogService.game(game).get("regions")).getFirst();
            String id=(String)orders.create(buyer.actor,map("productId","club-"+type+"-"+game,"gameId",game,"serviceType",type,"region",region,"contactPhone","13800000000","quantity",1),unique()).get("id");paid(buyer,orderDb.order(id));
            assertEquals(allowed,orders.list(member.actor,Map.of("scope","hall")).stream().anyMatch(o->id.equals(o.get("id"))));
            if(allowed)assertEquals("accepted",orders.act(member.actor,id,"accept",Map.of()).get("status"));
            else {
                assertThrows(ApiException.class,()->orders.act(member.actor,id,"accept",Map.of()));
                assertThrows(ApiException.class,()->orders.act(operator,id,"assign",map("playerId",member.actor.playerId(),"reason","校验跨游戏派单限制")));
                assertThrows(ApiException.class,()->catalog.publish(member.actor,map("gameId",game,"serviceType",type,"title","越界游戏商品","description","未通过该游戏该服务不能发布商品","price",6800,"task","100w纯带出")));
            }
            orders.act(buyer.actor,id,"refund",map("amount",orderDb.order(id).amount,"reason","结束独立资格测试"));
            orders.act(operator,id,"review-refund",map("approve",true,"note","测试结束，模拟退回款项"));payments.processRefund(orderDb.order(id).refundId);
        }
        approveGame(hourlyValorant);assertTrue(catalog.qualified(profiles.profile(member.actor.playerId()),"hourly","valorant"));assertFalse(catalog.qualified(profiles.profile(member.actor.playerId()),"escort","valorant"));assertTrue(catalog.qualified(profiles.profile(member.actor.playerId()),"escort","delta"));
        assertNotNull(applyGame(member,"hourly","lol"));assertNotNull(applyGame(member,"escort","valorant"));
        var view=catalog.profileView(profiles.profile(member.actor.playerId()));assertEquals(List.of("delta"),((Map<?,?>)view.get("qualificationGames")).get("escort"));
    }
    @Autowired PublicOrderPool publicPool;
    @Test void officialCatalogPriceAndPoolRecoverFromDatabaseAndRefundWithoutReason(){
        User buyer=user(),member=qualified("escort");
        var official=catalog.products(buyer.actor,false).stream().filter(p->p.id.equals("club-escort-delta")).findFirst().orElseThrow();
        assertEquals("club",official.sellerType);assertNull(official.playerId);assertEquals("诺曼部落",official.sellerName);
        var input=map("productId",official.id,"gameId","delta","serviceType","escort","region","国服","contactPhone","13800000000","quantity",1,"taskPrice",1,"task","试图修改任务");
        Order o=orderDb.order((String)orders.create(buyer.actor,input,unique()).get("id"));assertEquals(12800,o.amount);assertEquals("100w纯带出",o.task);assertNull(o.preferredPlayerId);
        assertFalse(publicPool.orders().stream().anyMatch(v->v.id.equals(o.id)));paid(buyer,o);
        assertTrue(orders.list(member.actor,Map.of("scope","hall")).stream().anyMatch(v->v.get("id").equals(o.id)));
        var rebuilt=new PublicOrderPool(orderDb);assertTrue(rebuilt.orders().stream().anyMatch(v->v.id.equals(o.id)));
        orders.act(buyer.actor,o.id,"refund",map("amount",o.amount));assertEquals("用户申请售后",orderDb.order(o.id).refundReason);
        assertFalse(publicPool.orders().stream().anyMatch(v->v.id.equals(o.id)));
        orders.act(operator,o.id,"review-refund",map("approve",false,"note","核实后恢复订单"));
        assertTrue(publicPool.orders().stream().anyMatch(v->v.id.equals(o.id)));
        orders.act(member.actor,o.id,"accept",Map.of());assertFalse(publicPool.orders().stream().anyMatch(v->v.id.equals(o.id)));
        assertThrows(ApiException.class,()->orders.create(buyer.actor,map("productId",official.id,"gameId","delta","serviceType","escort","region","国服","contactPhone","13800000000","quantity",1,"playerId",member.actor.playerId()),unique()));
    }
    @Test void privateProductNeverEntersPoolAndShortRefundReasonsAreAccepted(){
        User buyer=user(),member=qualified("hourly");Product product=catalog.publish(member.actor,map("gameId","delta","serviceType","hourly","title","指定组队商品","description","认真完成约定时长服务","price",8800));
        String id=(String)orders.create(buyer.actor,map("productId",product.id,"gameId","delta","serviceType","hourly","region","国服","contactPhone","13800000000","quantity",2),unique()).get("id");
        Order o=orderDb.order(id);assertEquals(member.actor.playerId(),o.preferredPlayerId);assertEquals(member.actor.name(),o.preferredPlayerName);assertEquals(17600,o.amount);paid(buyer,o);
        assertFalse(publicPool.orders().stream().anyMatch(v->v.id.equals(id)));
        orders.act(buyer.actor,id,"refund",map("amount",100,"reason","退"));assertEquals("pending",orderDb.order(id).refundStatus);
    }
    @Test void productCanBeBookedRepeatedlyWhileOfflineAndCannotBeBoughtBySeller(){
        User seller=qualified("escort"),buyer=user();Product product=catalog.publish(seller.actor,map("gameId","delta","serviceType","escort","title","长期任务护航","description","可以重复预约的个人护航服务","price",101,"task","100w纯带出"));catalog.updateProfile(operator,seller.actor.playerId(),map("available",false));
        Map<String,Object> body=map("productId",product.id,"gameId","delta","serviceType","escort","region","国服","contactPhone","13800000000","quantity",1,"taskPrice",99999,"task","篡改任务");
        String first=(String)orders.create(buyer.actor,body,unique()).get("id"),second=(String)orders.create(buyer.actor,body,unique()).get("id");assertNotEquals(first,second);assertEquals(101,orderDb.order(first).amount);assertEquals("100w纯带出",orderDb.order(first).task);assertTrue(profiles.product(product.id).active);assertThrows(ApiException.class,()->orders.create(seller.actor,body,unique()));
    }
    @Test void memberCannotChangeOwnPlatformControlledStatus(){
        User member=qualified("escort");
        for(boolean value:List.of(false,true)){
            assertEquals(403,assertThrows(ApiException.class,()->catalog.availability(member.actor,map("available",value))).status);
            assertTrue(profiles.profile(member.actor.playerId()).available);
            assertThrows(ApiException.class,()->catalog.updateProfile(member.actor,member.actor.playerId(),map("available",value)));
        }
        catalog.updateProfile(operator,member.actor.playerId(),map("available",false));
        assertEquals(403,assertThrows(ApiException.class,()->catalog.availability(member.actor,map("available",true))).status);
        assertFalse(profiles.profile(member.actor.playerId()).available);
        catalog.updateProfile(operator,member.actor.playerId(),map("available",true));assertTrue(profiles.profile(member.actor.playerId()).available);
    }
    @Test void acceptingPublicOrPrivateOrderUpdatesReadOnlyStatusUntilServiceCompletes(){
        for(boolean invited:List.of(false,true)){
            User member=qualified("escort"),buyer=user();Order order;
            if(invited){Product product=catalog.publish(member.actor,map("gameId","delta","serviceType","escort","title","指定护航服务","description","验证邀请接单后的服务状态","price",12800,"task","100w纯带出"));order=orderDb.order((String)orders.create(buyer.actor,map("productId",product.id,"gameId","delta","serviceType","escort","region","国服","contactPhone","13800000000","quantity",1),unique()).get("id"));}
            else order=create(buyer,"escort",12800);
            paid(buyer,order);orders.act(member.actor,order.id,"accept",Map.of());
            assertEquals(true,catalog.profileView(profiles.profile(member.actor.playerId())).get("busy"));
            assertEquals(403,assertThrows(ApiException.class,()->catalog.availability(member.actor,map("available",true))).status);
            orders.act(member.actor,order.id,"start",Map.of());orders.act(member.actor,order.id,"finish",map("taskCompleted",true,"note","本次任务已经达成"));orders.act(buyer.actor,order.id,"confirm",Map.of());
            var idle=catalog.profileView(profiles.profile(member.actor.playerId()));assertEquals(false,idle.get("busy"));assertEquals(true,idle.get("available"));
        }
    }
    @Test void presenceStreamRequiresAuthenticationAndDeliversCommittedChanges()throws Exception{
        assertEquals(401,request("GET","/v1/players/events",null,Map.of()).statusCode());
        User watcher=user(),member=qualified("escort");
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/players/events")).GET();bearer(watcher).forEach(builder::header);
        var response=HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        try(var input=new java.io.BufferedReader(new java.io.InputStreamReader(response.body()));var executor=Executors.newSingleThreadExecutor()){
            assertEquals("event:presence",input.readLine());while(!input.readLine().isEmpty()){}
            catalog.updateProfile(operator,member.actor.playerId(),map("available",false));
            var received=executor.submit(()->{String line;while((line=input.readLine())!=null)if(line.equals("event:presence"))return true;return false;});assertTrue(received.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void orderCreationIsIdempotentAndRejectsRequestKeyReuse(){
        qualified("hourly");User u=user();Map<String,Object> body=map("gameId","delta","serviceType","hourly","region","国服","contactPhone","13800000000","quantity",1);String key=unique();var one=orders.create(u.actor,body,key);var two=orders.create(u.actor,body,key);assertEquals(one.get("id"),two.get("id"));Map<String,Object> altered=new HashMap<>(body);altered.put("quantity",2);assertThrows(ApiException.class,()->orders.create(u.actor,altered,key));
    }
    @Test void multipleCopiesCreateOneOrderWithTotalIncomeAndFullDuration(){
        for(String type:List.of("hourly","escort")){
            User buyer=user(),member=qualified(type);int hours=type.equals("hourly")?6:1;int copies=type.equals("hourly")?1:3;
            Map<String,Object> body=map("productId","club-"+type+"-delta","gameId","delta","serviceType",type,"region","国服","contactPhone","13800000000","quantity",hours,"copies",copies,"taskPrice",1);
            String key=unique();var created=orders.create(buyer.actor,body,key);String id=(String)created.get("id");assertEquals(id,orders.create(buyer.actor,body,key).get("id"));
            Map<String,Object> changed=new HashMap<>(body);changed.put("copies",4);assertEquals(409,assertThrows(ApiException.class,()->orders.create(buyer.actor,changed,key)).status);
            Order order=orderDb.order(id);assertEquals(copies,order.copies);assertEquals(order.unitPrice*hours*copies,order.amount);assertEquals((int)((long)order.amount*8/10),order.earnings);
            paid(buyer,order);assertEquals(1,orders.list(member.actor,Map.of("scope","hall")).stream().filter(o->o.get("id").equals(id)).count());
            orders.act(member.actor,id,"accept",Map.of());orders.act(member.actor,id,"start",Map.of());
            if(type.equals("hourly")){clock.advance(2*3_600_000L);assertThrows(ApiException.class,()->orders.act(member.actor,id,"finish",map("note","还没有完成全部份数")));clock.advance(4*3_600_000L);}
            else assertThrows(ApiException.class,()->orders.act(member.actor,id,"finish",map("note","任务尚未全部完成","taskCompleted",false)));
            orders.act(member.actor,id,"finish",map("note","全部三份约定服务已完成","taskCompleted",true));orders.act(buyer.actor,id,"confirm",Map.of());assertEquals(order.earnings,ledgerDb.income(id,member.actor.playerId()).amount);
        }
    }
    @Test void copyCountIsStrictlyValidatedAndOmittedCountRemainsOne(){
        User buyer=user();Map<String,Object> body=map("productId","club-escort-delta","gameId","delta","serviceType","escort","region","国服","contactPhone","13800000000","quantity",1);
        String old=(String)orders.create(buyer.actor,body,unique()).get("id");assertEquals(1,orderDb.order(old).copies);
        for(Object invalid:new Object[]{0,-1,100,1.5,"3","1 OR 1=1",true,null}){Map<String,Object> input=new HashMap<>(body);input.put("copies",invalid);assertEquals(400,assertThrows(ApiException.class,()->orders.create(buyer.actor,input,unique())).status);}
        body.put("copies",99);String max=(String)orders.create(buyer.actor,body,unique()).get("id");assertEquals(12800*99,orderDb.order(max).amount);
    }
    @Test void orderContactsAreRequiredEncryptedAndVisibleOnlyToAuthorizedParticipants(){
        User buyer=user(),member=qualified("escort");
        Map<String,Object> body=map("productId","club-escort-delta","gameId","delta","serviceType","escort","region","国服","quantity",1,"contactPhone","13800000000","contactWechat","test_wechat");
        for(Object value:new Object[]{"",123,"abc","123","1".repeat(25)}){
            var invalid=new HashMap<>(body);invalid.put("contactPhone",value);assertEquals(400,assertThrows(ApiException.class,()->orders.create(buyer.actor,invalid,unique())).status);
        }
        var absent=new HashMap<>(body);absent.remove("contactPhone");assertEquals(400,assertThrows(ApiException.class,()->orders.create(buyer.actor,absent,unique())).status);
        var created=orders.create(buyer.actor,body,unique());String id=(String)created.get("id");Order stored=orderDb.order(id);
        assertEquals("13800000000",created.get("contactPhone"));assertEquals("test_wechat",created.get("contactWechat"));
        assertTrue(stored.contactPhoneEnc.startsWith("v1:"));assertFalse(stored.contactPhoneEnc.contains("13800000000"));assertFalse(created.containsKey("contactPhoneEnc"));
        paid(buyer,stored);
        var publicView=orders.list(member.actor,Map.of("scope","hall")).stream().filter(o->id.equals(o.get("id"))).findFirst().orElseThrow();
        assertFalse(publicView.containsKey("contactPhone"));assertFalse(publicView.containsKey("contactWechat"));assertFalse(publicView.containsKey("contactPhoneEnc"));
        assertEquals("13800000000",orders.act(member.actor,id,"accept",Map.of()).get("contactPhone"));
        assertEquals("test_wechat",orders.get(operator,id).get("contactWechat"));
        var hourly=new HashMap<>(body);hourly.put("productId","club-hourly-delta");hourly.put("serviceType","hourly");hourly.put("copies",2);
        assertEquals(400,assertThrows(ApiException.class,()->orders.create(buyer.actor,hourly,unique())).status);
    }
    @Test void concurrentAcceptAllowsOnlyOneWinner()throws Exception{
        User buyer=user();Order order=create(buyer,"escort",103);paid(buyer,order);List<User> members=List.of(qualified("escort"),qualified("escort"),qualified("escort"));CountDownLatch start=new CountDownLatch(1);AtomicInteger winners=new AtomicInteger();
        try(ExecutorService pool=Executors.newFixedThreadPool(3)){List<Future<?>> futures=new ArrayList<>();for(User u:members)futures.add(pool.submit(()->{try{start.await();orders.act(u.actor,order.id,"accept",Map.of());winners.incrementAndGet();}catch(Exception expected){}}));start.countDown();for(Future<?> f:futures)f.get(15,TimeUnit.SECONDS);}
        assertEquals(1,winners.get());assertEquals(1,orderDb.members(order.id).size());
    }
    @Test void cachedPublicPoolConcurrentGrabSkipsOrdersAlreadyClaimed()throws Exception{
        User buyer=user();List<User> members=List.of(qualified("escort"),qualified("escort"),qualified("escort"));
        for(User u:members){Qualification q=new Qualification();q.playerId=u.actor.playerId();q.serviceType="escort";q.gameId="lol";q.grantedAt=clock.millis();profiles.grant(q);}
        Set<String> expected=new HashSet<>();for(int n=0;n<2;n++){String id=(String)orders.create(buyer.actor,map("productId","club-escort-lol","gameId","lol","serviceType","escort","region","艾欧尼亚","contactPhone","13800000000","quantity",1),unique()).get("id");expected.add(id);paid(buyer,orderDb.order(id));}
        publicPool.orders();Set<String> taken=java.util.concurrent.ConcurrentHashMap.newKeySet();CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService workers=Executors.newFixedThreadPool(3)){List<Future<?>> futures=new ArrayList<>();for(User u:members)futures.add(workers.submit(()->{try{start.await();taken.add((String)orders.grab(u.actor,map("serviceType","escort","gameId","lol")).get("id"));}catch(ApiException unavailable){assertEquals(404,unavailable.status);}catch(InterruptedException e){throw new RuntimeException(e);}}));start.countDown();for(Future<?> f:futures)f.get(15,TimeUnit.SECONDS);}
        assertEquals(expected,taken);for(String id:expected)assertEquals(1,orderDb.members(id).size());
    }
    @Test void sameMemberCannotOccupyTwoOrdersConcurrently()throws Exception{
        User buyer=user(),member=qualified("escort");Order a=create(buyer,"escort",105),b=create(buyer,"escort",107);paid(buyer,a);paid(buyer,b);CountDownLatch start=new CountDownLatch(1);AtomicInteger winners=new AtomicInteger();
        try(ExecutorService pool=Executors.newFixedThreadPool(2)){List<Future<?>> work=new ArrayList<>();for(Order o:List.of(a,b))work.add(pool.submit(()->{try{start.await();orders.act(member.actor,o.id,"accept",Map.of());winners.incrementAndGet();}catch(Exception expected){}}));start.countDown();for(Future<?> f:work)f.get(15,TimeUnit.SECONDS);}
        assertEquals(1,winners.get());assertNotNull(orderDb.busy(member.actor.playerId()));
    }
    @Test void collaborationSplitsPenniesOnceAndDoesNotClaimTransfersWerePaid(){
        User buyer=user(),lead=qualified("escort"),assistant=qualified("escort");Order o=create(buyer,"escort",104);paid(buyer,o);orders.act(lead.actor,o.id,"accept",Map.of());orders.act(lead.actor,o.id,"add-assistant",map("playerId",assistant.actor.playerId()));orders.act(lead.actor,o.id,"start",Map.of());assertThrows(ApiException.class,()->orders.act(lead.actor,o.id,"remove-assistant",map("playerId",assistant.actor.playerId())));orders.act(lead.actor,o.id,"finish",map("taskCompleted",true,"note","任务已按约定完成"));orders.act(buyer.actor,o.id,"confirm",Map.of());orders.act(buyer.actor,o.id,"confirm",Map.of());
        Income a=ledgerDb.income(o.id,lead.actor.playerId()),b=ledgerDb.income(o.id,assistant.actor.playerId());assertEquals(82,a.amount+b.amount);assertTrue(Math.abs(a.amount-b.amount)<=1);assertEquals("credited",a.status);assertNull(a.paidAt);assertNull(orderDb.busy(lead.actor.playerId()));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM income_entry WHERE order_id=?",Integer.class,o.id));assertEquals(0L,ledger.wallet(lead.actor).get("settled"));
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
    @Test void callbackEventsCannotBeReusedForAnotherPaymentOrAlterAnExistingTransaction(){
        User buyer=user();Order one=create(buyer,"escort",100),two=create(buyer,"escort",100);
        String a=(String)payments.create(buyer.actor,one.id,map("channel","test"),unique()).get("id"),b=(String)payments.create(buyer.actor,two.id,map("channel","test"),unique()).get("id"),event=unique();
        var first=new PaymentGateway.VerifiedPayment("test",a,"txn-"+a,"test-merchant",100,"CNY",event);payments.acceptVerified(first);
        assertEquals(409,assertThrows(ApiException.class,()->payments.acceptVerified(new PaymentGateway.VerifiedPayment("test",b,"txn-"+b,"test-merchant",100,"CNY",event))).status);
        assertEquals(409,assertThrows(ApiException.class,()->payments.acceptVerified(new PaymentGateway.VerifiedPayment("test",a,"changed-transaction","test-merchant",100,"CNY",event))).status);
        payments.acceptVerified(first);assertEquals("unpaid",orderDb.order(two.id).status);assertNull(ledgerDb.payment(b).transactionId);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_notification WHERE event_key=?",Integer.class,"test:"+event));
    }
    @Test void queryAndCloseCannotConfirmADifferentPayment(){
        User buyer=user();Order one=create(buyer,"escort",100),two=create(buyer,"escort",100);
        String a=(String)payments.create(buyer.actor,one.id,map("channel","test"),unique()).get("id"),b=(String)payments.create(buyer.actor,two.id,map("channel","test"),unique()).get("id");
        var wrong=new PaymentGateway.Status("paid",new PaymentGateway.VerifiedPayment("test",b,"txn-"+b,"test-merchant",100,"CNY",unique()));
        try{
            gateway.queryResults.put(a,wrong);assertEquals(400,assertThrows(ApiException.class,()->payments.query(a)).status);
            gateway.queryResults.remove(a);gateway.closeResults.put(a,wrong);clock.advance(600_000);assertEquals(400,assertThrows(ApiException.class,()->payments.query(a)).status);
            assertEquals("unpaid",orderDb.order(one.id).status);assertEquals("unpaid",orderDb.order(two.id).status);assertNull(ledgerDb.payment(b).transactionId);
        }finally{gateway.queryResults.remove(a);gateway.closeResults.remove(a);}
    }
    @Test void notificationRouteBindsChannelAndRejectsMalformedVerifiedResults(){
        User buyer=user();Order o=create(buyer,"escort",100);String id=(String)payments.create(buyer.actor,o.id,map("channel","test"),unique()).get("id");
        try{
            gateway.notification=new PaymentGateway.VerifiedPayment("wechat",id,"txn-"+id,"test-merchant",100,"CNY",unique());
            var rejected=assertThrows(ApiException.class,()->payments.notify("test",Map.of(),new byte[0]));assertEquals(400,rejected.status);assertTrue(rejected.getMessage().contains("本次渠道"));
            for(var invalid:List.of(
                new PaymentGateway.VerifiedPayment("test",id," ","test-merchant",100,"CNY",unique()),
                new PaymentGateway.VerifiedPayment("test",id,"txn-"+id,"test-merchant",100,null,unique()),
                new PaymentGateway.VerifiedPayment("test",id,"txn-"+id,"test-merchant",100,"USD",unique()),
                new PaymentGateway.VerifiedPayment("test",id,"txn-"+id,"test-merchant",100,"CNY","bad\nevent")))assertEquals(400,assertThrows(ApiException.class,()->payments.acceptVerified(invalid)).status);
            assertEquals("unpaid",orderDb.order(o.id).status);assertNull(ledgerDb.payment(id).transactionId);
        }finally{gateway.notification=null;}
    }
    @Test void timeoutTaskSurvivesWithoutClientRequestsAndLatePaymentCannotReopenOrder(){
        User buyer=user();Order plain=create(buyer,"escort",100),paidLate=create(buyer,"escort",100);String id=(String)payments.create(buyer.actor,paidLate.id,map("channel","test"),unique()).get("id");payments.prepare(id);clock.advance(600_000);
        assertFalse(orders.closeExpired(paidLate.id));assertTrue(payments.query(id));assertTrue(orders.closeExpired(paidLate.id));
        var recreated=new TaskService(taskDb,clock);for(int n=0;n<200;n++){Task task=recreated.claim();if(task==null)break;runner.execute(task);}assertEquals("cancelled",orderDb.order(plain.id).status);
        payments.acceptVerified(new PaymentGateway.VerifiedPayment("test",id,"late-"+id,"test-merchant",100,"CNY",unique()));assertEquals("cancelled",orderDb.order(paidLate.id).status);assertEquals("late_paid",ledgerDb.payment(id).status);
    }
    @Test void sqlInjectionUnknownFieldsAndOtherUsersOrdersAreRejected()throws Exception{
        User owner=user(),other=user();Order o=create(owner,"escort",100);assertEquals(403,request("GET","/v1/orders/"+o.id,null,bearer(other)).statusCode());String query=java.net.URLEncoder.encode("' OR 1=1 --",java.nio.charset.StandardCharsets.UTF_8);var result=request("GET","/v1/orders?query="+query,null,bearer(other));assertEquals(200,result.statusCode());assertEquals(0,data(result).size());
        Map<String,Object> body=map("gameId","delta","serviceType","escort","region","国服","contactPhone","13800000000","quantity",1,"task","测试任务","taskPrice",100,"commissionBps",0);Map<String,String> headers=new HashMap<>(bearer(owner));headers.put("Idempotency-Key",unique());assertEquals(400,request("POST","/v1/orders",body,headers).statusCode());assertEquals(2000,orderDb.order(o.id).commissionBps);assertEquals(401,request("POST","/v1/auth/staff/login",map("username","' OR 1=1 --","password","anything"),Map.of()).statusCode());
    }
    @Test void csrfOriginLimitsAndSpoofedIpCannotBypassBan()throws Exception{
        assertEquals(403,request("POST","/v1/auth/sms/send",map("phone",phone()),Map.of("Origin","https://attacker.example")).statusCode());
        String ip="203.0.113.9";abuse.ban(ip,"集成测试临时封禁",clock.millis()+60000,"测试");MockHttpServletRequest req=new MockHttpServletRequest("GET","/v1/club");req.setRemoteAddr(ip);req.addHeader("X-Real-IP","198.51.100.99");req.addHeader("X-Forwarded-For","198.51.100.99");MockHttpServletResponse res=new MockHttpServletResponse();AtomicBoolean passed=new AtomicBoolean();new ApiSecurityFilter(auth,abuse,config,json).doFilter(req,res,(r,s)->passed.set(true));assertEquals(403,res.getStatus());assertFalse(passed.get());
        String key=unique();for(int n=0;n<5;n++)assertTrue(abuse.allow("atomic-test",key,5,60000));assertFalse(abuse.allow("atomic-test",key,5,60000));
    }
    @Test void browserLoginRequiresTrustedOriginAndCrossSiteRequestsCannotOmitIt()throws Exception{
        assertEquals(403,request("POST","/v1/auth/staff/login",map("username","missing","password","wrong"),Map.of("X-Client-Type","h5")).statusCode());
        assertEquals(403,request("POST","/v1/auth/sms/send",map("phone",phone()),Map.of("Sec-Fetch-Site","cross-site")).statusCode());
        assertEquals(403,request("GET","/v1/club",null,Map.of("Sec-Fetch-Site","cross-site","Origin","null")).statusCode());
        assertEquals(200,request("GET","/v1/club",null,Map.of("Sec-Fetch-Site","same-origin")).statusCode());
        assertEquals(400,request("POST","/v1/auth/sms/send",map("phone","invalid"),Map.of("X-Client-Type","h5","Origin","http://127.0.0.1:4180","Sec-Fetch-Site","same-origin")).statusCode());
        User nativeUser=user();assertEquals(200,request("GET","/v1/auth/session",null,bearer(nativeUser)).statusCode());
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
        assertThrows(ApiException.class,()->orders.create(u.actor,map("gameId","delta","serviceType","escort","region","国服","contactPhone","13800000000","quantity",1,"task",List.of("注入对象"),"taskPrice",100),unique()));
    }
    @Test void dashboardUsesDatabaseFinancialAggregates(){
        var before=admin.dashboard(operator);User buyer=user();Order o=create(buyer,"escort",123);paid(buyer,o);var after=admin.dashboard(operator);
        assertEquals(((Number)before.get("todayRevenue")).longValue()+123,((Number)after.get("todayRevenue")).longValue());
        assertEquals(((Number)before.get("todayOrders")).longValue()+1,((Number)after.get("todayOrders")).longValue());
        assertFalse(((List<?>)after.get("recentOrders")).isEmpty());assertEquals(7,((List<?>)after.get("revenueTrend")).size());
    }
    @Test void memberCommissionIsAdminOnlyAndDoesNotControlProductPricing()throws Exception{
        User member=qualified("escort");String pid=member.actor.playerId();assertEquals(2000,profiles.profile(pid).commissionBps);
        for(Object value:List.of(-1,10001,12.5,"2000",true))assertThrows(ApiException.class,()->catalog.updateProfile(operator,pid,map("commissionBps",value)));
        assertEquals(403,request("PATCH","/v1/admin/players/"+pid,map("commissionBps",0),bearer(member)).statusCode());
        assertThrows(ApiException.class,()->catalog.updateProfile(operator,pid,map("price",100)));
        for(int rate:List.of(0,10000,1234))assertEquals(rate,catalog.updateProfile(operator,pid,map("commissionBps",rate)).get("commissionBps"));
        Product item=catalog.publish(member.actor,map("gameId","delta","serviceType","escort","title","独立商品定价","description","成员发布商品自行设置价格","price",12345,"task","约定任务"));assertEquals(12345,item.price);
    }
    @Test void memberCommissionSnapshotsDriveOffersCollaborationRefundAndLedger(){
        User buyer=user(),lead=qualified("escort"),assistant=qualified("escort");
        catalog.updateProfile(operator,lead.actor.playerId(),map("commissionBps",1000));catalog.updateProfile(operator,assistant.actor.playerId(),map("commissionBps",3000));
        Order o=create(buyer,"escort",10001);paid(buyer,o);
        var leadOffer=orders.list(lead.actor,Map.of("scope","hall")).stream().filter(v->o.id.equals(v.get("id"))).findFirst().orElseThrow();assertEquals(9000,leadOffer.get("earnings"));
        var assistantOffer=orders.list(assistant.actor,Map.of("scope","hall")).stream().filter(v->o.id.equals(v.get("id"))).findFirst().orElseThrow();assertEquals(7000,assistantOffer.get("earnings"));assertFalse(leadOffer.containsKey("contactPhone"));
        orders.act(lead.actor,o.id,"accept",Map.of());assertEquals(1000,orderDb.members(o.id).getFirst().commissionBps);
        catalog.updateProfile(operator,lead.actor.playerId(),map("commissionBps",4000));orders.act(lead.actor,o.id,"add-assistant",map("playerId",assistant.actor.playerId()));assertEquals(8000,orderDb.order(o.id).earnings);
        catalog.updateProfile(operator,assistant.actor.playerId(),map("commissionBps",2000));assertEquals(3000,orderDb.members(o.id).get(1).commissionBps);
        orders.act(lead.actor,o.id,"remove-assistant",map("playerId",assistant.actor.playerId()));assertEquals(9000,orderDb.order(o.id).earnings);
        orders.act(lead.actor,o.id,"add-assistant",map("playerId",assistant.actor.playerId()));assertEquals(8500,orderDb.order(o.id).earnings);
        orders.act(buyer.actor,o.id,"refund",map("amount",1001));orders.act(operator,o.id,"review-refund",map("approve",true,"note","核实后部分退款"));assertEquals(8500,orderDb.order(o.id).earnings);payments.processRefund(orderDb.order(o.id).refundId);assertEquals(7650,orderDb.order(o.id).earnings);
        orders.act(lead.actor,o.id,"start",Map.of());orders.act(lead.actor,o.id,"finish",map("taskCompleted",true,"note","约定任务已经完成"));orders.act(buyer.actor,o.id,"confirm",Map.of());orders.act(buyer.actor,o.id,"confirm",Map.of());
        assertEquals(4050,ledgerDb.income(o.id,lead.actor.playerId()).amount);assertEquals(3600,ledgerDb.income(o.id,assistant.actor.playerId()).amount);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM income_entry WHERE order_id=?",Integer.class,o.id));
    }
    @Test void legacyOrderCommissionAndAllocationsDoNotChangeAfterMemberRatesAreAdjusted(){
        User buyer=user(),lead=qualified("escort"),assistant=qualified("escort");Order o=create(buyer,"escort",10000);
        jdbc.update("UPDATE club_order SET member_commission=FALSE,commission_bps=2500,earnings=7500 WHERE id=?",o.id);paid(buyer,o);
        catalog.updateProfile(operator,lead.actor.playerId(),map("commissionBps",0));catalog.updateProfile(operator,assistant.actor.playerId(),map("commissionBps",10000));
        orders.act(lead.actor,o.id,"accept",Map.of());orders.act(lead.actor,o.id,"add-assistant",map("playerId",assistant.actor.playerId()));assertEquals(7500,orderDb.order(o.id).earnings);assertNull(orderDb.members(o.id).getFirst().commissionBps);
        orders.act(lead.actor,o.id,"start",Map.of());orders.act(lead.actor,o.id,"finish",map("taskCompleted",true,"note","旧订单沿用历史规则"));orders.act(buyer.actor,o.id,"confirm",Map.of());
        assertEquals(3750,ledgerDb.income(o.id,lead.actor.playerId()).amount);assertEquals(3750,ledgerDb.income(o.id,assistant.actor.playerId()).amount);
    }
    @Test void zeroMemberIncomeDoesNotEnqueueAZeroValuePayout(){
        User buyer=user(),member=qualified("escort");catalog.updateProfile(operator,member.actor.playerId(),map("commissionBps",10000));Order o=create(buyer,"escort",100);paid(buyer,o);orders.act(member.actor,o.id,"accept",Map.of());assertEquals(0,orderDb.order(o.id).earnings);
        orders.act(member.actor,o.id,"start",Map.of());orders.act(member.actor,o.id,"finish",map("taskCompleted",true,"note","约定服务已完成"));orders.act(buyer.actor,o.id,"confirm",Map.of());Income income=ledgerDb.income(o.id,member.actor.playerId());assertEquals(0,income.amount);assertEquals("paid",income.status);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payout WHERE income_id=?",Integer.class,income.id));
    }
    record WalletFixture(User buyer,User member,Order order,Payment payment){}
    WalletFixture completedWallet(boolean real,int amount){
        User buyer=user(),member=qualified("escort");Order o=create(buyer,"escort",amount);Payment p=paid(buyer,o);
        // Isolated test database fixture only; no external payment request.
        if(real)jdbc.update("UPDATE payment_attempt SET channel='wechat' WHERE id=?",p.id);
        orders.act(member.actor,o.id,"accept",Map.of());orders.act(member.actor,o.id,"start",Map.of());orders.act(member.actor,o.id,"finish",map("taskCompleted",true,"note","钱包集成测试完成服务"));orders.act(buyer.actor,o.id,"confirm",Map.of());
        return new WalletFixture(buyer,member,orderDb.order(o.id),p);
    }
    Actor finance(){var row=authDb.staffByUsername(staffAccount("finance"));return new Actor(row.id,row.name,row.role,null,"","staff",false);}
    void bind(User member){wallets.saveBank(member.actor,map("holder","测试身份","bankName","测试银行","bankNumber","4111111111111111","password",PASSWORD,"consent",true));}
    long available(User member){return ((Number)wallets.summary(member.actor).get("available")).longValue();}
    String withdrawal(User member,int amount,String key){return (String)wallets.withdraw(member.actor,map("amount",amount,"password",PASSWORD),key).get("id");}
    Map<String,Object> review(String note){return map("note",note,"password",PASSWORD);}
    @Test void walletCreditsRealAndSimulationSeparatelyAndNeverBackfillsLegacy(){
        var demo=completedWallet(false,10000);var summary=wallets.summary(demo.member.actor);assertEquals(8000L,summary.get("simulation"));assertEquals(0L,summary.get("total"));clock.advance(86400001);assertEquals(0,available(demo.member));bind(demo.member);
        assertEquals(409,assertThrows(ApiException.class,()->withdrawal(demo.member,100,unique())).status);
        var real=completedWallet(true,10000);assertEquals(8000L,wallets.summary(real.member.actor).get("pending"));assertEquals(0,available(real.member));clock.advance(86400001);assertEquals(8000,available(real.member));
        String income=ledgerDb.income(real.order.id,real.member.actor.playerId()).id;jdbc.update("DELETE FROM wallet_entry WHERE reference_id=?",income);jdbc.update("DELETE FROM wallet_income WHERE income_id=?",income);
        new TransactionTemplate(transactions).executeWithoutResult(t->ledger.credit(orderDb.lock(real.order.id)));assertEquals(0,available(real.member));assertNull(walletDb.income(income));
    }
    @Test void bankBindingIsEncryptedReauthenticatedAndNotShownToOtherUsers()throws Exception{
        var f=completedWallet(true,10000);String pid=f.member.actor.playerId();assertFalse(cn.normanclub.wallet.WalletService.bankNumber("0".repeat(16)));assertFalse(cn.normanclub.wallet.WalletService.bankNumber("4111111111111112"));
        assertEquals(403,assertThrows(ApiException.class,()->wallets.saveBank(f.member.actor,map("holder","测试身份","bankName","测试银行","bankNumber","4111111111111111","password","wrong","consent",true))).status);assertNull(walletDb.bank(pid));
        assertThrows(ApiException.class,()->wallets.saveBank(f.member.actor,map("holder","其他姓名","bankName","测试银行","bankNumber","4111111111111111","password",PASSWORD,"consent",true)));bind(f.member);
        assertNotEquals("4111111111111111",walletDb.bank(pid).numberCipher);assertEquals("4111111111111111",crypto.decrypt(walletDb.bank(pid).numberCipher));String serialized=json.writeValueAsString(wallets.summary(f.member.actor));assertFalse(serialized.contains("4111111111111111"));assertFalse(serialized.contains("测试身份"));
        assertNull(wallets.summary(f.buyer.actor).get("bank"));assertEquals(401,request("GET","/v1/wallet",null,Map.of()).statusCode());assertEquals(403,request("GET","/v1/admin/withdrawals",null,bearer(f.member)).statusCode());
    }
    @Test void withdrawalIsAtomicIdempotentAndCancelRestoresExactlyOnce(){
        var f=completedWallet(true,10000);bind(f.member);clock.advance(86400001);String key=unique(),id=withdrawal(f.member,5000,key);assertEquals(id,withdrawal(f.member,5000,key));assertEquals(3000,available(f.member));assertEquals(5000L,wallets.summary(f.member.actor).get("frozen"));
        assertEquals(409,assertThrows(ApiException.class,()->withdrawal(f.member,6000,key)).status);assertEquals(409,assertThrows(ApiException.class,()->bind(f.member)).status);assertEquals(403,assertThrows(ApiException.class,()->wallets.cancel(f.buyer.actor,id)).status);
        wallets.cancel(f.member.actor,id);assertEquals(8000,available(f.member));assertThrows(ApiException.class,()->wallets.cancel(f.member.actor,id));assertEquals(8000,available(f.member));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_entry WHERE reference_id=?",Integer.class,id));
    }
    @Test void concurrentWithdrawalsCannotOverspendOneIncome()throws Exception{
        var f=completedWallet(true,10000);bind(f.member);clock.advance(86400001);var gate=new CountDownLatch(1);try(var executor=Executors.newFixedThreadPool(2)){var work=new ArrayList<Future<Integer>>();for(int n=0;n<2;n++)work.add(executor.submit(()->{gate.await();try{withdrawal(f.member,5000,unique());return 200;}catch(ApiException e){return e.status;}}));gate.countDown();var result=new ArrayList<Integer>();for(var future:work)result.add(future.get(20,TimeUnit.SECONDS));Collections.sort(result);assertEquals(List.of(200,409),result);}assertEquals(3000,available(f.member));assertEquals(5000L,wallets.summary(f.member.actor).get("frozen"));
    }
    @Test void manualPaymentRequiresApprovalReauthAndUniqueActualReference(){
        var f=completedWallet(true,10000);bind(f.member);clock.advance(86400001);String id=withdrawal(f.member,4000,unique());Actor fin=finance();
        assertThrows(ApiException.class,()->wallets.details(fin,id,map("password",PASSWORD)));assertThrows(ApiException.class,()->wallets.action(fin,id,"paid",map("note","已核对流水","password",PASSWORD,"reference",unique(),"confirm",true)));
        assertThrows(ApiException.class,()->wallets.action(fin,id,"approve",map("note","审核测试","password","wrong")));assertEquals("pending",walletDb.withdrawal(id).status);
        wallets.action(fin,id,"approve",review("资料人工审核通过"));assertEquals("4111111111111111",wallets.details(fin,id,map("password",PASSWORD)).get("bankNumber"));wallets.action(fin,id,"start",review("开始人工转账处理"));
        assertThrows(ApiException.class,()->wallets.cancel(f.member.actor,id));assertThrows(ApiException.class,()->wallets.action(fin,id,"reject",review("不得解冻不明付款")));assertThrows(ApiException.class,()->wallets.action(fin,id,"not-paid",review("未确认银行结果")));
        String ref=unique();var payload=map("note","已对账确认银行付款","password",PASSWORD,"reference",ref,"confirm",true);wallets.action(fin,id,"paid",payload);wallets.action(fin,id,"paid",payload);
        assertEquals(4000L,wallets.summary(f.member.actor).get("settled"));assertEquals(4000,available(f.member));assertEquals("credited",ledgerDb.income(f.order.id,f.member.actor.playerId()).status);
        clock.advance(300001);String second=withdrawal(f.member,4000,unique());wallets.action(fin,second,"approve",review("人工审核通过"));wallets.action(fin,second,"start",review("开始付款处理"));assertThrows(ApiException.class,()->wallets.action(fin,second,"paid",payload));assertEquals(4000L,wallets.summary(f.member.actor).get("frozen"));
        wallets.action(fin,second,"not-paid",map("note","银行确认没有发生付款","password",PASSWORD,"confirm",true));assertEquals(4000,available(f.member));assertThrows(ApiException.class,()->wallets.action(fin,id,"not-paid",map("note","已付款不得解冻","password",PASSWORD,"confirm",true)));
    }
    @Test void completedRefundFreezesIncomeAndCannotRaceAnAllocatedWithdrawal(){
        var f=completedWallet(true,10000);bind(f.member);clock.advance(86400001);String id=withdrawal(f.member,1000,unique());
        assertEquals(409,assertThrows(ApiException.class,()->orders.act(f.buyer.actor,f.order.id,"refund",map("amount",1000,"reason","核实部分服务退款"))).status);wallets.cancel(f.member.actor,id);
        orders.act(f.buyer.actor,f.order.id,"refund",map("amount",1000,"reason","核实部分服务退款"));assertEquals(0,available(f.member));assertThrows(ApiException.class,()->withdrawal(f.member,100,unique()));
        orders.act(operator,f.order.id,"review-refund",map("approve",false,"note","核实服务无需退款"));assertEquals(8000,available(f.member));
        orders.act(f.buyer.actor,f.order.id,"refund",map("amount",1000,"reason","再次核实后部分退款"));orders.act(operator,f.order.id,"review-refund",map("approve",true,"note","核实部分退款通过"));
        // Only the isolated gateway fixture processes this test refund.
        jdbc.update("UPDATE payment_attempt SET channel='test' WHERE id=?",f.payment.id);assertTrue(payments.processRefund(orderDb.order(f.order.id).refundId));assertEquals(7200,available(f.member));assertEquals(7200,ledgerDb.income(f.order.id,f.member.actor.playerId()).amount);
    }

    @Test void normalRefundProcessingDoesNotExhaustNetworkFailureRetries(){
        var payment=org.mockito.Mockito.mock(PaymentService.class);var refund=new Refund();refund.id=unique();refund.paymentId=unique();
        org.mockito.Mockito.when(payment.refund(refund.id)).thenReturn(refund);org.mockito.Mockito.when(payment.configuredFor(org.mockito.ArgumentMatchers.any())).thenReturn(true);org.mockito.Mockito.when(payment.processRefund(refund.id)).thenReturn(false);
        Task task=new Task();task.id=unique();task.type="REFUND";task.aggregateId=refund.id;task.runAt=clock.millis();taskDb.enqueue(task);task.attempts=8;
        new TaskRunner(tasks,orders,orderDb,payment,config,clock,authDb,org.mockito.Mockito.mock(SecurityMapper.class)).execute(task);
        assertEquals("pending",jdbc.queryForObject("SELECT state FROM background_task WHERE id=?",String.class,task.id));assertEquals(0,jdbc.queryForObject("SELECT attempts FROM background_task WHERE id=?",Integer.class,task.id));
    }

}
